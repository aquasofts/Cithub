package edu.ccit.webvpn.update

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import edu.ccit.webvpn.BuildConfig
import edu.ccit.webvpn.core.runtime.RuntimeLog
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal class UpdateDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()
    private var worker: Job? = null
    @Volatile
    private var activeCall: Call? = null
    @Volatile
    private var cancelRequested = false
    @Volatile
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startInForeground(UpdateDownloadStore.read(this))
        cancelRequested = intent?.action == ActionCancel
        if (cancelRequested) activeCall?.cancel()
        if (worker?.isActive != true) {
            worker = serviceScope.launch { runWorker() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeCall?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val record = UpdateDownloadStore.read(this)
        if (record != null && record.status in ActiveStatuses) {
            UpdateDownloadStore.write(
                this,
                record.copy(
                    status = UpdateDownloadStatus.Failed,
                    speedBytesPerSecond = 0L,
                    errorMessage = "系统已停止长时间运行的更新下载，请重试",
                ),
            )
        }
        cancelRequested = true
        activeCall?.cancel()
        worker?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private suspend fun runWorker() {
        var outcome: DownloadOutcome = DownloadOutcome.NoWork
        try {
            outcome = processDownload()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val record = UpdateDownloadStore.read(this)
            if (record != null && record.status in ActiveStatuses) {
                if (runCatching { validateDestination(record) }.isSuccess) {
                    deleteDownloadedFile(record)
                }
                val failed = record.copy(
                    status = UpdateDownloadStatus.Failed,
                    speedBytesPerSecond = 0L,
                    verifiedVersionCode = null,
                    errorMessage = error.message ?: "更新下载服务运行失败",
                )
                UpdateDownloadStore.write(this, failed)
                outcome = DownloadOutcome.Failed(failed)
            }
        } finally {
            activeCall?.cancel()
            activeCall = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            when (val result = outcome) {
                is DownloadOutcome.Ready -> showReadyNotification(result.record)
                is DownloadOutcome.Failed -> showFailedNotification(result.record)
                DownloadOutcome.Cancelled,
                DownloadOutcome.NoWork,
                -> Unit
            }
            stopSelfResult(latestStartId)
        }
    }

    private suspend fun processDownload(): DownloadOutcome {
        var record = UpdateDownloadStore.read(this) ?: return DownloadOutcome.NoWork
        if (record.status == UpdateDownloadStatus.Ready) return DownloadOutcome.Ready(record)
        if (record.status == UpdateDownloadStatus.Failed) return DownloadOutcome.Failed(record)
        validateDestination(record)

        while (serviceScope.isActive) {
            record = UpdateDownloadStore.read(this) ?: return DownloadOutcome.Cancelled
            if (cancelRequested || record.status == UpdateDownloadStatus.Cancelled) {
                return cancelDownload(record)
            }

            val downloaded = try {
                downloadCurrentCandidate(record)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                coroutineContext.ensureActive()
                if (cancelRequested) return cancelDownload(record)
                record = failCurrentCandidate(record, error.message ?: "当前下载线路不可用")
                    ?: return DownloadOutcome.Failed(requireNotNull(UpdateDownloadStore.read(this)))
                continue
            }

            if (cancelRequested) return cancelDownload(downloaded)
            val verified = verifyDownloadedPackage(downloaded)
            val apk = verified.getOrNull()
            if (apk != null) {
                return DownloadOutcome.Ready(markReady(downloaded, apk))
            }
            record = failCurrentCandidate(
                downloaded,
                verified.exceptionOrNull()?.message ?: "安装包安全校验失败",
            ) ?: return DownloadOutcome.Failed(requireNotNull(UpdateDownloadStore.read(this)))
        }
        return DownloadOutcome.NoWork
    }

    private suspend fun downloadCurrentCandidate(record: UpdateDownloadRecord): UpdateDownloadRecord {
        val parsedUrl = record.currentUrl.toHttpUrlOrNull()
        if (parsedUrl == null || !parsedUrl.isHttps) {
            throw IOException("更新下载线路不是有效的 HTTPS 地址")
        }
        val destination = File(record.destinationPath)
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            throw IOException("无法替换旧的更新安装包")
        }

        var current = record.copy(
            status = UpdateDownloadStatus.Downloading,
            downloadedBytes = 0L,
            speedBytesPerSecond = 0L,
            verifiedVersionCode = null,
            errorMessage = null,
        ).also {
            UpdateDownloadStore.write(this, it)
            updateForeground(it)
        }
        val request = Request.Builder()
            .url(parsedUrl)
            .header("User-Agent", "Cithub/${BuildConfig.VERSION_NAME} (Android)")
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        val call = httpClient.newCall(request)
        activeCall = call
        if (cancelRequested) {
            call.cancel()
            throw DownloadCancelledException()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载服务器返回 HTTP ${response.code}")
                if (!response.request.url.isHttps) throw IOException("下载线路跳转到了非 HTTPS 地址")
                val body = response.body
                val responseSize = body.contentLength().takeIf { it > 0L }
                if (record.assetSize > 0L && responseSize != null && responseSize != record.assetSize) {
                    throw IOException("下载文件大小与 Release 记录不一致")
                }
                if (record.assetSize <= 0L && responseSize != null) {
                    current = current.copy(assetSize = responseSize).also {
                        UpdateDownloadStore.write(this, it)
                        updateForeground(it)
                    }
                }

                var downloadedBytes = 0L
                var measuredBytes = 0L
                var measurementStartedAt = System.nanoTime()
                var lastProgressAt = measurementStartedAt
                body.byteStream().use { input ->
                    destination.outputStream().buffered(DownloadBufferSize).use { output ->
                        val buffer = ByteArray(DownloadBufferSize)
                        while (true) {
                            coroutineContext.ensureActive()
                            if (cancelRequested) throw DownloadCancelledException()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloadedBytes += count
                            measuredBytes += count
                            val now = System.nanoTime()
                            if (now - lastProgressAt >= ProgressUpdateNanos) {
                                val elapsed = (now - measurementStartedAt).coerceAtLeast(1L)
                                val speed = (measuredBytes * NanosPerSecond / elapsed).coerceAtLeast(0L)
                                current = persistProgress(current, downloadedBytes, speed)
                                measuredBytes = 0L
                                measurementStartedAt = now
                                lastProgressAt = now
                            }
                        }
                        output.flush()
                    }
                }
                if (record.assetSize > 0L && downloadedBytes != record.assetSize) {
                    throw IOException("下载文件大小与 Release 记录不一致")
                }
                current = persistProgress(current, downloadedBytes, 0L)
            }
            return current
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun persistProgress(
        record: UpdateDownloadRecord,
        downloadedBytes: Long,
        speedBytesPerSecond: Long,
    ): UpdateDownloadRecord = record.copy(
        status = UpdateDownloadStatus.Downloading,
        downloadedBytes = downloadedBytes.coerceAtLeast(0L),
        speedBytesPerSecond = speedBytesPerSecond.coerceAtLeast(0L),
        errorMessage = null,
    ).also {
        UpdateDownloadStore.write(this, it)
        updateForeground(it)
    }

    private fun failCurrentCandidate(
        record: UpdateDownloadRecord,
        message: String,
    ): UpdateDownloadRecord? {
        deleteDownloadedFile(record)
        record.nextDownloadAttempt()?.let { next ->
            RuntimeLog.get(this).warning(
                source = "update_download",
                event = "candidate_fallback",
                fields = mapOf(
                    "url_index" to record.urlIndex,
                    "next_url_index" to next.urlIndex,
                    "reason" to message,
                ),
            )
            return next.also {
                UpdateDownloadStore.write(this, it)
                updateForeground(it)
            }
        }

        val failed = record.copy(
            status = UpdateDownloadStatus.Failed,
            speedBytesPerSecond = 0L,
            verifiedVersionCode = null,
            errorMessage = message,
        )
        UpdateDownloadStore.write(this, failed)
        return null
    }

    private fun verifyDownloadedPackage(record: UpdateDownloadRecord): Result<VerifiedUpdateApk> = runCatching {
        UpdateInstaller.verify(
            context = this,
            apk = File(record.destinationPath),
            release = record.toRelease(),
            flavor = updateFlavor(BuildConfig.FLAVOR),
            verifyReleaseVersion = !record.customDownload,
        )
    }

    private fun markReady(
        record: UpdateDownloadRecord,
        apk: VerifiedUpdateApk,
    ): UpdateDownloadRecord = record.copy(
        status = UpdateDownloadStatus.Ready,
        version = apk.version.toString(),
        downloadedBytes = File(record.destinationPath).length(),
        speedBytesPerSecond = 0L,
        verifiedVersionCode = apk.versionCode,
        errorMessage = null,
    ).also { ready ->
        UpdateDownloadStore.write(this, ready)
        RuntimeLog.get(this).info(
            source = "update_download",
            event = "ready_to_install",
            fields = mapOf(
                "version" to ready.version,
                "version_code" to ready.verifiedVersionCode,
                "url_index" to ready.urlIndex,
                "downloaded_bytes" to ready.downloadedBytes,
            ),
        )
    }

    private fun cancelDownload(record: UpdateDownloadRecord): DownloadOutcome {
        activeCall?.cancel()
        deleteDownloadedFile(record)
        UpdateDownloadStore.clear(this)
        return DownloadOutcome.Cancelled
    }

    private fun deleteDownloadedFile(record: UpdateDownloadRecord) {
        runCatching { File(record.destinationPath).delete() }
    }

    private fun validateDestination(record: UpdateDownloadRecord) {
        val expected = UpdateInstaller.destinationFile(this, record.assetName).canonicalFile
        val actual = File(record.destinationPath).canonicalFile
        if (actual != expected) {
            throw IOException("更新安装包保存路径无效")
        }
    }

    private fun startInForeground(record: UpdateDownloadRecord?) {
        ServiceCompat.startForeground(
            this,
            ForegroundNotificationId,
            buildProgressNotification(record),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    @SuppressLint("MissingPermission")
    private fun updateForeground(record: UpdateDownloadRecord) {
        NotificationManagerCompat.from(this).notify(
            ForegroundNotificationId,
            buildProgressNotification(record),
        )
    }

    private fun buildProgressNotification(record: UpdateDownloadRecord?): Notification {
        val progress = record?.progressPercent()
        return NotificationCompat.Builder(this, NotificationChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                record?.let { if (it.customDownload) "正在下载自定义更新" else "正在下载 Cithub ${it.version}" }
                    ?: "正在准备更新下载",
            )
            .setContentText(
                record?.speedBytesPerSecond?.takeIf { it > 0L }
                    ?.let { "正在下载 · ${formatNotificationSize(it)}/s" }
                    ?: "正在下载",
            )
            .setContentIntent(appPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress ?: 0, progress == null)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                cancelPendingIntent(),
            )
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun showReadyNotification(record: UpdateDownloadRecord) {
        if (record.toVerifiedApk() == null) return
        NotificationManagerCompat.from(this).notify(
            ResultNotificationId,
            NotificationCompat.Builder(this, NotificationChannelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Cithub ${record.version} 已下载")
                .setContentText("点按打开应用并确认安装")
                .setContentIntent(appPendingIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

    @SuppressLint("MissingPermission")
    private fun showFailedNotification(record: UpdateDownloadRecord) {
        NotificationManagerCompat.from(this).notify(
            ResultNotificationId,
            NotificationCompat.Builder(this, NotificationChannelId)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Cithub 更新下载失败")
                .setContentText(record.errorMessage ?: "请打开应用重试")
                .setContentIntent(appPendingIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun appPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        return PendingIntent.getActivity(
            this,
            AppRequestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        CancelRequestCode,
        Intent(this, UpdateDownloadService::class.java).setAction(ActionCancel),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelId,
                "应用更新下载",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private sealed interface DownloadOutcome {
        data object NoWork : DownloadOutcome
        data object Cancelled : DownloadOutcome
        data class Ready(val record: UpdateDownloadRecord) : DownloadOutcome
        data class Failed(val record: UpdateDownloadRecord) : DownloadOutcome
    }

    private class DownloadCancelledException : IOException("下载已取消")

    companion object {
        private const val ActionStart = "edu.ccit.webvpn.update.START"
        private const val ActionCancel = "edu.ccit.webvpn.update.CANCEL"
        private const val NotificationChannelId = "app_update_download"
        private const val ForegroundNotificationId = 2_201
        private const val ResultNotificationId = 2_202
        private const val AppRequestCode = 2_201
        private const val CancelRequestCode = 2_202
        private const val DownloadBufferSize = 64 * 1024
        private const val NanosPerSecond = 1_000_000_000L
        private const val ProgressUpdateNanos = 500_000_000L
        private val ActiveStatuses = setOf(UpdateDownloadStatus.Queued, UpdateDownloadStatus.Downloading)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UpdateDownloadService::class.java).setAction(ActionStart),
            )
        }

        fun cancel(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, UpdateDownloadService::class.java).setAction(ActionCancel),
            )
        }
    }
}

private fun UpdateDownloadRecord.progressPercent(): Int? {
    if (assetSize <= 0L) return null
    return ((downloadedBytes.coerceAtLeast(0L) * 100L) / assetSize)
        .coerceIn(0L, 100L)
        .toInt()
}

private fun formatNotificationSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
