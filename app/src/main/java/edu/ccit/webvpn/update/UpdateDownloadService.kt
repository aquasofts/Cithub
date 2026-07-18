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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class UpdateDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
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
        if (intent?.action == ActionCancel) {
            cancelRequested = true
        } else {
            cancelRequested = false
        }
        if (worker?.isActive != true) {
            worker = serviceScope.launch { runWorker() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
                runCatching {
                    val client = GopeedUpdateEngine.start(this)
                    deleteTaskAndFile(client, record)
                }
                runCatching { File(record.destinationPath).delete() }
                val message = error.message ?: "Gopeed 下载服务运行失败"
                val failed = record.copy(
                    status = UpdateDownloadStatus.Failed,
                    gopeedTaskId = null,
                    speedBytesPerSecond = 0L,
                    errorMessage = message,
                )
                UpdateDownloadStore.write(this, failed)
                outcome = DownloadOutcome.Failed(failed)
            }
        } finally {
            runCatching { GopeedUpdateEngine.stop() }
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

        val client = GopeedUpdateEngine.start(this)
        while (serviceScope.isActive) {
            record = UpdateDownloadStore.read(this) ?: return DownloadOutcome.Cancelled
            if (cancelRequested || record.status == UpdateDownloadStatus.Cancelled) {
                deleteTaskAndFile(client, record)
                UpdateDownloadStore.clear(this)
                return DownloadOutcome.Cancelled
            }

            if (record.gopeedTaskId == null) {
                record = createCurrentTask(client, record) ?: continue
            }

            val taskId = requireNotNull(record.gopeedTaskId)
            val task = runCatching { client.getTask(taskId) }.getOrElse { error ->
                recoverCompletedDownload(client, record, taskId)?.let { ready ->
                    return DownloadOutcome.Ready(ready)
                }
                record = failCurrentCandidate(client, record, error.message ?: "无法读取 Gopeed 下载任务")
                    ?: return DownloadOutcome.Failed(requireNotNull(UpdateDownloadStore.read(this)))
                continue
            }

            when (task.status) {
                "ready", "pause" -> {
                    val continued = runCatching { client.continueTask(taskId) }
                    if (continued.isFailure) {
                        record = failCurrentCandidate(
                            client,
                            record,
                            continued.exceptionOrNull()?.message ?: "无法继续 Gopeed 下载任务",
                        ) ?: return DownloadOutcome.Failed(requireNotNull(UpdateDownloadStore.read(this)))
                        continue
                    }
                    record = persistProgress(record, task)
                }
                "running", "wait" -> record = persistProgress(record, task)
                "error" -> {
                    recoverCompletedDownload(client, record, taskId)?.let { ready ->
                        return DownloadOutcome.Ready(ready)
                    }
                    record = failCurrentCandidate(client, record, "当前下载线路不可用")
                        ?: return DownloadOutcome.Failed(requireNotNull(UpdateDownloadStore.read(this)))
                    continue
                }
                "done" -> {
                    val verified = verifyDownloadedPackage(record)
                    val apk = verified.getOrNull()
                    if (apk != null) {
                        return DownloadOutcome.Ready(markReady(client, record, taskId, apk))
                    }
                    record = failCurrentCandidate(
                        client,
                        record,
                        verified.exceptionOrNull()?.message ?: "安装包安全校验失败",
                        retrySameRouteWithSingleConnection = false,
                    ) ?: return DownloadOutcome.Failed(requireNotNull(UpdateDownloadStore.read(this)))
                    continue
                }
                else -> {
                    record = failCurrentCandidate(client, record, "Gopeed 返回了未知下载状态")
                        ?: return DownloadOutcome.Failed(requireNotNull(UpdateDownloadStore.read(this)))
                    continue
                }
            }

            updateForeground(record)
            delay(PollIntervalMillis)
        }
        return DownloadOutcome.NoWork
    }

    private fun createCurrentTask(
        client: GopeedUpdateClient,
        record: UpdateDownloadRecord,
    ): UpdateDownloadRecord? {
        val parsedUrl = record.currentUrl.toHttpUrlOrNull()
        if (parsedUrl == null || !parsedUrl.isHttps) {
            return failCurrentCandidate(client, record, "更新下载线路不是有效的 HTTPS 地址")
        }
        val destination = File(record.destinationPath)
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            return failCurrentCandidate(client, record, "无法替换旧的更新安装包")
        }
        val created = runCatching {
            client.createTask(
                url = record.currentUrl,
                destinationDirectory = requireNotNull(destination.parentFile),
                fileName = destination.name,
                connections = record.activeConnections,
                userAgent = "Cithub/${BuildConfig.VERSION_NAME} (Android)",
            )
        }
        val taskId = created.getOrNull()
        if (taskId == null) {
            return failCurrentCandidate(
                client,
                record,
                created.exceptionOrNull()?.message ?: "无法创建 Gopeed 下载任务",
            )
        }
        return record.copy(
            status = UpdateDownloadStatus.Downloading,
            gopeedTaskId = taskId,
            downloadedBytes = 0L,
            speedBytesPerSecond = 0L,
            verifiedVersionCode = null,
            errorMessage = null,
        ).also {
            UpdateDownloadStore.write(this, it)
            updateForeground(it)
        }
    }

    private fun persistProgress(record: UpdateDownloadRecord, task: GopeedTask): UpdateDownloadRecord =
        record.copy(
            status = UpdateDownloadStatus.Downloading,
            downloadedBytes = task.progress.downloaded.coerceAtLeast(0L),
            speedBytesPerSecond = task.progress.speed.coerceAtLeast(0L),
            errorMessage = null,
        ).also { UpdateDownloadStore.write(this, it) }

    private fun failCurrentCandidate(
        client: GopeedUpdateClient,
        record: UpdateDownloadRecord,
        message: String,
        retrySameRouteWithSingleConnection: Boolean = true,
    ): UpdateDownloadRecord? {
        deleteTaskAndFile(client, record)
        record.nextDownloadAttempt(retrySameRouteWithSingleConnection)?.let { next ->
            RuntimeLog.get(this).warning(
                source = "update_download",
                event = "candidate_fallback",
                fields = mapOf(
                    "url_index" to record.urlIndex,
                    "connections" to record.activeConnections,
                    "next_url_index" to next.urlIndex,
                    "next_connections" to next.activeConnections,
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
            gopeedTaskId = null,
            speedBytesPerSecond = 0L,
            verifiedVersionCode = null,
            errorMessage = message,
        )
        UpdateDownloadStore.write(this, failed)
        return null
    }

    private fun recoverCompletedDownload(
        client: GopeedUpdateClient,
        record: UpdateDownloadRecord,
        taskId: String,
    ): UpdateDownloadRecord? = verifyDownloadedPackage(record).getOrNull()?.let { apk ->
        RuntimeLog.get(this).info(
            source = "update_download",
            event = "completed_file_recovered",
            fields = mapOf(
                "url_index" to record.urlIndex,
                "connections" to record.activeConnections,
                "downloaded_bytes" to File(record.destinationPath).length(),
            ),
        )
        markReady(client, record, taskId, apk)
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
        client: GopeedUpdateClient,
        record: UpdateDownloadRecord,
        taskId: String,
        apk: VerifiedUpdateApk,
    ): UpdateDownloadRecord {
        runCatching { client.deleteTask(taskId, force = false) }
        return record.copy(
            status = UpdateDownloadStatus.Ready,
            version = apk.version.toString(),
            gopeedTaskId = null,
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
                    "connections" to ready.activeConnections,
                    "downloaded_bytes" to ready.downloadedBytes,
                ),
            )
        }
    }

    private fun deleteTaskAndFile(client: GopeedUpdateClient, record: UpdateDownloadRecord) {
        record.gopeedTaskId?.let { taskId ->
            runCatching { client.deleteTask(taskId, force = true) }
        }
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
                    ?.let { "${record.connectionModeLabel()} · ${formatNotificationSize(it)}/s" }
                    ?: record?.connectionModeLabel()
                    ?: "Gopeed 下载",
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

    companion object {
        private const val ActionStart = "edu.ccit.webvpn.update.START"
        private const val ActionCancel = "edu.ccit.webvpn.update.CANCEL"
        private const val NotificationChannelId = "app_update_download"
        private const val ForegroundNotificationId = 2_201
        private const val ResultNotificationId = 2_202
        private const val AppRequestCode = 2_201
        private const val CancelRequestCode = 2_202
        private const val PollIntervalMillis = 750L
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

private fun UpdateDownloadRecord.connectionModeLabel(): String =
    if (singleConnectionFallback) "Gopeed 单连接回退" else "Gopeed · $activeConnections 连接"

private fun formatNotificationSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
