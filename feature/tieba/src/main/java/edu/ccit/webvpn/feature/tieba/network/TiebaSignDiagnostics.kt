package edu.ccit.webvpn.feature.tieba.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import edu.ccit.webvpn.feature.tieba.ForumSummary
import edu.ccit.webvpn.feature.tieba.data.AccountEntity
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * A small, rotating, sign-in-only diagnostic log.
 *
 * Credentials, TBS values, client identifiers and request signatures are never persisted. Their
 * SHA-256 prefixes and lengths are sufficient to tell whether two stages used the same value.
 */
internal class TiebaSignDiagnostics private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val directory = File(appContext.filesDir, "diagnostics")
    private val currentFile = File(directory, "tieba-sign.jsonl")
    private val previousFile = File(directory, "tieba-sign.previous.jsonl")
    private val lock = Any()

    fun startAttempt(source: String, account: AccountEntity, forum: ForumSummary): String {
        val attemptId = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        record(
            attemptId = attemptId,
            event = "attempt_started",
            fields = mapOf(
                "source" to source,
                "app_version" to packageInfo?.versionName.orEmpty(),
                @Suppress("DEPRECATION")
                "app_version_code" to packageInfo?.versionCode?.toLong(),
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                "forum_id" to forum.id,
                "forum_name" to forum.name,
                "forum_tbs_fp" to fingerprint(forum.tbs),
                "account_tbs_fp" to fingerprint(account.tbs),
                "account_uid_fp" to fingerprint(account.uid.toString()),
                "credential_flags" to mapOf(
                    "bduss" to account.bduss.isNotBlank(),
                    "stoken" to account.sToken.isNotBlank(),
                    "cookie" to account.cookie.isNotBlank(),
                    "zid" to !account.zid.isNullOrBlank(),
                ),
            ),
        )
        return attemptId
    }

    fun recordForumSnapshot(account: AccountEntity?, forum: ForumSummary) {
        record(
            attemptId = null,
            event = "frs_snapshot",
            fields = mapOf(
                "forum_id" to forum.id,
                "forum_name" to forum.name,
                "tbs_fp" to fingerprint(forum.tbs),
                "signed" to forum.signed,
                "signed_days" to forum.signedDays,
                "followed" to forum.isFollowed,
                "account_uid_fp" to account?.uid?.toString()?.let(::fingerprint),
            ),
        )
    }

    fun recordStage(attemptId: String?, event: String, fields: Map<String, Any?> = emptyMap()) {
        record(attemptId, event, fields)
    }

    fun recordHttpRequest(attemptId: String?, channel: String, request: Request) {
        val body = request.body as? FormBody
        val fields = body?.let { form ->
            (0 until form.size).associate { form.name(it) to form.value(it) }
        }.orEmpty()
        val safeValues = listOf(
            "_client_version",
            "_client_type",
            "_os_version",
            "fid",
            "kw",
            "from",
            "subapp_type",
            "net_type",
            "stErrorNums",
            "stMethod",
            "stMode",
            "stTimesNum",
            "stTime",
            "stSize",
            "ie",
        ).mapNotNull { name -> fields[name]?.let { name to it } }.toMap()
        val sensitiveFingerprints = listOf(
            "BDUSS",
            "tbs",
            "sign",
            "_client_id",
            "sample_id",
            "cuid",
            "cuid_galaxy2",
            "c3_aid",
            "android_id",
            "baiduid",
        ).mapNotNull { name -> fields[name]?.let { name to fingerprint(it) } }.toMap()
        record(
            attemptId = attemptId,
            event = "http_request",
            fields = mapOf(
                "channel" to channel,
                "scheme" to request.url.scheme,
                "host" to request.url.host,
                "path" to request.url.encodedPath,
                "method" to request.method,
                "field_names" to fields.keys.sorted(),
                "safe_fields" to safeValues,
                "sensitive_fingerprints" to sensitiveFingerprints,
                "header_names" to request.headers.names().sorted(),
                "user_agent" to request.header("User-Agent"),
                "client_user_token_fp" to request.header("client_user_token")?.let(::fingerprint),
                "cookie_fp" to request.header("Cookie")?.let(::fingerprint),
            ),
        )
    }

    fun recordHttpResponse(
        attemptId: String?,
        channel: String,
        response: Response,
        elapsedMs: Long,
    ) {
        val responseText = runCatching { response.peekBody(MAX_RESPONSE_LOG_BYTES).string() }.getOrDefault("")
        val root = runCatching { JsonParser.parseString(responseText).asJsonObject }.getOrNull()
        val errorCode = listOf("error_code", "errno", "no")
            .firstNotNullOfOrNull { key ->
                root?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            }
        val errorMessage = listOf("error_msg", "errmsg", "error")
            .firstNotNullOfOrNull { key -> root?.get(key)?.tiebaErrorMessage()?.takeIf(String::isNotBlank) }
        record(
            attemptId = attemptId,
            event = "http_response",
            fields = mapOf(
                "channel" to channel,
                "http_code" to response.code,
                "content_type" to response.body?.contentType()?.toString(),
                "content_length" to response.body?.contentLength(),
                "elapsed_ms" to elapsedMs,
                "service_code" to errorCode,
                "service_message" to errorMessage?.let(::redactText),
                "has_user_info" to (
                    root?.has("user_info") == true ||
                        root?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.has("uinfo") == true
                    ),
            ),
        )
    }

    fun recordHttpFailure(attemptId: String?, channel: String, error: Throwable, elapsedMs: Long) {
        record(
            attemptId = attemptId,
            event = "http_failure",
            fields = mapOf(
                "channel" to channel,
                "elapsed_ms" to elapsedMs,
                "exception" to error.javaClass.name,
                "message" to error.message?.let(::redactText),
            ),
        )
    }

    suspend fun exportText(): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            buildString {
                appendLine("Cithub 贴吧签到诊断日志")
                appendLine("生成时间：${Instant.now()}")
                appendLine("隐私说明：BDUSS、STOKEN、Cookie、TBS、CUID、设备标识和请求签名均未明文记录。")
                appendLine()
                if (previousFile.exists()) append(previousFile.readText())
                if (currentFile.exists()) append(currentFile.readText())
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

    private fun record(attemptId: String?, event: String, fields: Map<String, Any?>) {
        runCatching {
            val entry = linkedMapOf<String, Any?>(
                "time" to Instant.now().toString(),
                "attempt" to attemptId,
                "event" to event,
            ).apply { putAll(fields.mapValues { (_, value) -> sanitizeValue(value) }) }
            val line = gson.toJson(entry) + System.lineSeparator()
            synchronized(lock) {
                directory.mkdirs()
                rotateIfNeeded(line.toByteArray().size)
                currentFile.appendText(line)
            }
        }.onFailure { Log.w(TAG, "Unable to write sign diagnostics", it) }
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is String -> redactText(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to sanitizeValue(it.value) }
        is Iterable<*> -> value.map(::sanitizeValue)
        else -> value
    }

    private fun rotateIfNeeded(incomingBytes: Int) {
        if (currentFile.exists() && currentFile.length() + incomingBytes > MAX_FILE_BYTES) {
            previousFile.delete()
            currentFile.renameTo(previousFile)
        }
    }

    private fun fingerprint(value: String?): String? {
        if (value == null) return null
        if (value.isEmpty()) return "empty"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val prefix = digest.take(6).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
        return "sha256:$prefix,len=${value.length}"
    }

    private fun redactText(value: String): String = value
        .replace(Regex("(?i)(BDUSS|STOKEN|TBS|SIGN|COOKIE|CUID|TOKEN)\\s*[=:]\\s*[^\\s,;]+"), "$1=<redacted>")
        .take(MAX_TEXT_LENGTH)

    companion object {
        private const val TAG = "TiebaSignDiagnostics"
        private const val MAX_FILE_BYTES = 512L * 1024L
        private const val MAX_RESPONSE_LOG_BYTES = 64L * 1024L
        private const val MAX_TEXT_LENGTH = 1_000

        @Volatile private var instance: TiebaSignDiagnostics? = null

        fun get(context: Context): TiebaSignDiagnostics = instance ?: synchronized(this) {
            instance ?: TiebaSignDiagnostics(context).also { instance = it }
        }
    }
}

internal class TiebaSignDiagnosticsInterceptor(
    private val diagnostics: TiebaSignDiagnostics,
    private val channel: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val taggedRequest = chain.request()
        val attemptId = taggedRequest.header(SIGN_DIAGNOSTIC_ATTEMPT_HEADER)
        val request = taggedRequest.newBuilder()
            .removeHeader(SIGN_DIAGNOSTIC_ATTEMPT_HEADER)
            .build()
        runCatching { diagnostics.recordHttpRequest(attemptId, channel, request) }
        val startedAt = System.nanoTime()
        return try {
            chain.proceed(request).also { response ->
                runCatching { diagnostics.recordHttpResponse(
                    attemptId = attemptId,
                    channel = channel,
                    response = response,
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                ) }
            }
        } catch (error: Throwable) {
            runCatching { diagnostics.recordHttpFailure(
                attemptId = attemptId,
                channel = channel,
                error = error,
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
            ) }
            throw error
        }
    }
}

internal const val SIGN_DIAGNOSTIC_ATTEMPT_HEADER = "X-Cithub-Sign-Attempt"
