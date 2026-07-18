package edu.ccit.webvpn.core.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer

/**
 * Process-wide rotating runtime log.
 *
 * This log deliberately does not redact values. Callers may include credentials, cookies,
 * complete request bodies and personal information when that is useful for diagnosing failures.
 */
class RuntimeLog private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    private val directory = File(appContext.filesDir, "runtime-log")
    private val currentFile = File(directory, "cithub-runtime.jsonl")
    private val previousFile = File(directory, "cithub-runtime.previous.jsonl")
    private val lock = Any()
    private val installed = AtomicBoolean(false)

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        val packageInfo = runCatching {
            application.packageManager.getPackageInfo(application.packageName, 0)
        }.getOrNull()
        info(
            source = "application",
            event = "process_started",
            fields = mapOf(
                "package" to application.packageName,
                "version_name" to packageInfo?.versionName,
                @Suppress("DEPRECATION")
                "version_code" to packageInfo?.versionCode?.toLong(),
                "pid" to Process.myPid(),
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            ),
        )
        installUncaughtExceptionHandler()
        application.registerActivityLifecycleCallbacks(ActivityLogger(this))
    }

    fun info(source: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        record("INFO", source, event, fields)
    }

    fun warning(source: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        record("WARN", source, event, fields)
    }

    fun error(
        source: String,
        event: String,
        error: Throwable,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        record(
            level = "ERROR",
            source = source,
            event = event,
            fields = fields + mapOf(
                "exception" to error.javaClass.name,
                "message" to error.message,
                "stack_trace" to error.stackTraceText(),
            ),
        )
    }

    fun recordHttpRequest(
        channel: String,
        request: Request,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        info(
            source = "http",
            event = "request",
            fields = fields + mapOf(
                "channel" to channel,
                "method" to request.method,
                "url" to request.url.toString(),
                "headers" to request.headers.toMultimap(),
                "body" to request.body?.let(::captureRequestBody),
            ),
        )
    }

    fun recordHttpResponse(
        channel: String,
        response: Response,
        elapsedMs: Long,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        info(
            source = "http",
            event = "response",
            fields = fields + mapOf(
                "channel" to channel,
                "method" to response.request.method,
                "url" to response.request.url.toString(),
                "http_code" to response.code,
                "elapsed_ms" to elapsedMs,
                "headers" to response.headers.toMultimap(),
                "body" to captureResponseBody(response),
            ),
        )
    }

    fun recordHttpFailure(
        channel: String,
        request: Request,
        elapsedMs: Long,
        error: Throwable,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        error(
            source = "http",
            event = "failure",
            error = error,
            fields = fields + mapOf(
                "channel" to channel,
                "method" to request.method,
                "url" to request.url.toString(),
                "elapsed_ms" to elapsedMs,
                "headers" to request.headers.toMultimap(),
                "body" to request.body?.let(::captureRequestBody),
            ),
        )
    }

    suspend fun exportText(): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            buildString {
                appendLine("Cithub 运行日志")
                appendLine("生成时间：${Instant.now()}")
                appendLine(PRIVACY_WARNING)
                appendLine(
                    "说明：单个请求或响应正文最多记录 ${MAX_BODY_BYTES / 1024} KiB，" +
                        "超出部分会标记为截断；图片、音频、视频和字体正文不写入日志。",
                )
                appendLine()
                if (previousFile.exists()) append(previousFile.readText(Charsets.UTF_8))
                if (currentFile.exists()) append(currentFile.readText(Charsets.UTF_8))
            }.trimEnd()
        }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            currentFile.delete()
            previousFile.delete()
            Unit
        }
    }

    private fun record(level: String, source: String, event: String, fields: Map<String, Any?>) {
        runCatching {
            val entry = linkedMapOf<String, Any?>(
                "time" to Instant.now().toString(),
                "level" to level,
                "thread" to Thread.currentThread().name,
                "source" to source,
                "event" to event,
            ).apply { putAll(fields) }
            val line = gson.toJson(entry) + System.lineSeparator()
            synchronized(lock) {
                directory.mkdirs()
                rotateIfNeeded(line.toByteArray(Charsets.UTF_8).size)
                currentFile.appendText(line, Charsets.UTF_8)
            }
        }.onFailure { Log.w(TAG, "Unable to write runtime log", it) }
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            error(
                source = "application",
                event = "uncaught_exception",
                error = throwable,
                fields = mapOf("crashed_thread" to thread.name),
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun captureRequestBody(body: okhttp3.RequestBody): Map<String, Any?> = runCatching {
        val contentType = body.contentType()
        val contentLength = runCatching { body.contentLength() }.getOrDefault(-1L)
        if (contentType.isBinaryMedia()) {
            return@runCatching skippedBinaryBody(contentType, contentLength)
        }
        if (body.isDuplex() || body.isOneShot()) {
            return@runCatching mapOf(
                "content_type" to contentType?.toString(),
                "content_length" to contentLength,
                "capture" to "skipped_one_shot_or_duplex",
            )
        }
        val buffer = Buffer()
        val captureSink = PrefixCaptureSink(buffer, MAX_BODY_BYTES.toLong())
        captureSink.buffer().use { sink -> body.writeTo(sink) }
        capturedBody(
            bytes = buffer.readByteArray(),
            contentType = contentType,
            declaredLength = contentLength,
            observedLength = captureSink.totalBytes,
        )
    }.getOrElse { error ->
        mapOf(
            "content_type" to body.contentType()?.toString(),
            "content_length" to runCatching { body.contentLength() }.getOrNull(),
            "capture_error" to error.stackTraceText(),
        )
    }

    private fun captureResponseBody(response: Response): Map<String, Any?>? {
        val body = response.body ?: return null
        val contentType = body.contentType()
        val contentLength = body.contentLength()
        if (contentType.isBinaryMedia()) return skippedBinaryBody(contentType, contentLength)
        return runCatching {
            val bytes = response.peekBody(MAX_BODY_BYTES.toLong()).bytes()
            capturedBody(bytes, contentType, contentLength)
        }.getOrElse { error ->
            mapOf(
                "content_type" to body.contentType()?.toString(),
                "content_length" to body.contentLength(),
                "capture_error" to error.stackTraceText(),
            )
        }
    }

    private fun capturedBody(
        bytes: ByteArray,
        contentType: MediaType?,
        declaredLength: Long,
        observedLength: Long = bytes.size.toLong(),
    ): Map<String, Any?> {
        val limited = if (bytes.size > MAX_BODY_BYTES) bytes.copyOf(MAX_BODY_BYTES) else bytes
        val isText = contentType.isTextual()
        return linkedMapOf(
            "content_type" to contentType?.toString(),
            "content_length" to declaredLength,
            "captured_bytes" to limited.size,
            "truncated" to (observedLength > limited.size || declaredLength > limited.size),
            (if (isText) "text" else "base64") to if (isText) {
                limited.toString(contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
            } else {
                android.util.Base64.encodeToString(limited, android.util.Base64.NO_WRAP)
            },
        )
    }

    private fun skippedBinaryBody(contentType: MediaType?, declaredLength: Long): Map<String, Any?> = mapOf(
        "content_type" to contentType?.toString(),
        "content_length" to declaredLength,
        "capture" to "skipped_binary_media",
    )

    private fun rotateIfNeeded(incomingBytes: Int) {
        if (currentFile.exists() && currentFile.length() + incomingBytes > MAX_FILE_BYTES) {
            previousFile.delete()
            currentFile.renameTo(previousFile)
        }
    }

    companion object {
        const val PRIVACY_WARNING =
            "警告：运行日志不做脱敏，可能包含账号密码、Cookie、BDUSS/STOKEN、请求正文及个人信息，请勿公开分享。"

        private const val TAG = "RuntimeLog"
        private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
        private const val MAX_BODY_BYTES = 64 * 1024

        @Volatile private var instance: RuntimeLog? = null

        fun get(context: Context): RuntimeLog = instance ?: synchronized(this) {
            instance ?: RuntimeLog(context).also { instance = it }
        }
    }
}

class RuntimeLogInterceptor(
    context: Context,
    private val channel: String,
) : Interceptor {
    private val runtimeLog = RuntimeLog.get(context)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        runtimeLog.recordHttpRequest(channel, request)
        val startedAt = System.nanoTime()
        return try {
            chain.proceed(request).also { response ->
                runtimeLog.recordHttpResponse(
                    channel = channel,
                    response = response,
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                )
            }
        } catch (error: Throwable) {
            runtimeLog.recordHttpFailure(
                channel = channel,
                request = request,
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                error = error,
            )
            throw error
        }
    }
}

private class ActivityLogger(private val log: RuntimeLog) : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = log(activity, "created")
    override fun onActivityStarted(activity: Activity) = log(activity, "started")
    override fun onActivityResumed(activity: Activity) = log(activity, "resumed")
    override fun onActivityPaused(activity: Activity) = log(activity, "paused")
    override fun onActivityStopped(activity: Activity) = log(activity, "stopped")
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = log(activity, "save_instance_state")
    override fun onActivityDestroyed(activity: Activity) = log(activity, "destroyed")

    private fun log(activity: Activity, state: String) {
        log.info(
            source = "activity",
            event = state,
            fields = mapOf("activity" to activity.javaClass.name),
        )
    }
}

private fun MediaType?.isTextual(): Boolean {
    if (this == null) return false
    if (type == "text") return true
    return subtype.contains("json", ignoreCase = true) ||
        subtype.contains("xml", ignoreCase = true) ||
        subtype.contains("html", ignoreCase = true) ||
        subtype.contains("javascript", ignoreCase = true) ||
        subtype.contains("x-www-form-urlencoded", ignoreCase = true)
}

private fun MediaType?.isBinaryMedia(): Boolean = when (this?.type?.lowercase()) {
    "image", "audio", "video", "font" -> true
    else -> false
}

private class PrefixCaptureSink(
    private val destination: Buffer,
    private val byteLimit: Long,
) : Sink {
    var totalBytes: Long = 0
        private set

    override fun write(source: Buffer, byteCount: Long) {
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        val remaining = (byteLimit - destination.size).coerceAtLeast(0)
        val captured = minOf(byteCount, remaining)
        if (captured > 0) destination.write(source, captured)
        if (byteCount > captured) source.skip(byteCount - captured)
        totalBytes += byteCount
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

private fun Throwable.stackTraceText(): String = StringWriter().also { writer ->
    printStackTrace(PrintWriter(writer))
}.toString()
