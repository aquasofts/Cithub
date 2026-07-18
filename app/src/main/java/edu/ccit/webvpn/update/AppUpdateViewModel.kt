package edu.ccit.webvpn.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import edu.ccit.webvpn.BuildConfig
import edu.ccit.webvpn.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Immutable
internal sealed interface AppUpdateUiState {
    data object Hidden : AppUpdateUiState
    data class Available(val release: AppRelease) : AppUpdateUiState
    data class Downloading(val release: AppRelease, val progress: Float?) : AppUpdateUiState
    data class Ready(val release: AppRelease, val apk: VerifiedUpdateApk) : AppUpdateUiState
    data class Failed(val release: AppRelease, val message: String) : AppUpdateUiState
}

sealed interface ManualUpdateCheckResult {
    data object UpToDate : ManualUpdateCheckResult
    data class Failed(val message: String) : ManualUpdateCheckResult
}

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    private val flavor = updateFlavor(BuildConfig.FLAVOR)
    private val updateSettings = settingsRepository.updateSettings
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Hidden)
    internal val state: StateFlow<AppUpdateUiState> = mutableState.asStateFlow()
    private val mutableManualChecking = MutableStateFlow(false)
    val manualChecking: StateFlow<Boolean> = mutableManualChecking.asStateFlow()
    private val mutableManualResults = MutableSharedFlow<ManualUpdateCheckResult>(extraBufferCapacity = 1)
    val manualResults: SharedFlow<ManualUpdateCheckResult> = mutableManualResults.asSharedFlow()

    init {
        viewModelScope.launch { resumeDownloadOrCheck() }
    }

    internal fun dismiss() {
        mutableState.value = AppUpdateUiState.Hidden
    }

    internal fun startDownload(release: AppRelease) {
        val asset = release.asset
        if (asset == null) {
            mutableState.value = AppUpdateUiState.Failed(release, "这个 Release 没有与当前 Full/Lite 版本匹配的 APK")
            return
        }
        if (mutableState.value is AppUpdateUiState.Downloading) return

        viewModelScope.launch {
            runCatching {
                val accelerators = updateSettings.snapshot().githubAccelerators
                val urls = githubUrlCandidates(asset.downloadUrl, accelerators)
                withContext(Dispatchers.IO) { enqueue(release, urls, 0) }
            }.onSuccess { pending ->
                savePending(pending)
                monitor(pending)
            }.onFailure { error ->
                mutableState.value = AppUpdateUiState.Failed(
                    release,
                    error.message ?: "无法开始下载安装包",
                )
            }
        }
    }

    internal fun cancelDownload() {
        val pending = readPending() ?: run {
            mutableState.value = AppUpdateUiState.Hidden
            return
        }
        downloadManager.remove(pending.downloadId)
        File(pending.destinationPath).delete()
        clearPending()
        mutableState.value = AppUpdateUiState.Available(pending.toRelease())
    }

    internal fun retry(release: AppRelease) {
        startDownload(release)
    }

    fun checkNow() {
        if (mutableManualChecking.value) return
        viewModelScope.launch { checkLatestInternal(manual = true) }
    }

    private suspend fun resumeDownloadOrCheck() {
        val pending = readPending()
        if (pending != null) {
            monitor(pending)
        } else {
            checkLatestInternal(manual = false)
        }
    }

    private suspend fun checkLatestInternal(manual: Boolean) {
        if (manual) mutableManualChecking.value = true
        runCatching {
            val settings = updateSettings.snapshot()
            val releaseClient = GitHubReleaseClient(
                userAgent = "Cithub/${BuildConfig.VERSION_NAME} (Android)",
            )
            releaseClient.latest(
                flavor = flavor,
                includePrereleases = settings.previewReleases,
                accelerators = settings.githubAccelerators,
            )
        }
            .onSuccess { release ->
                val current = SemanticVersion.parse(BuildConfig.VERSION_NAME)
                if (release != null && current != null && release.version > current) {
                    mutableState.value = AppUpdateUiState.Available(release)
                } else {
                    if (manual) mutableManualResults.tryEmit(ManualUpdateCheckResult.UpToDate)
                }
            }
            .onFailure { error ->
                if (manual) {
                    mutableManualResults.tryEmit(
                        ManualUpdateCheckResult.Failed(error.message ?: "检查更新失败"),
                    )
                }
            }
        if (manual) mutableManualChecking.value = false
    }

    private fun enqueue(release: AppRelease, urls: List<String>, urlIndex: Int): PendingDownload {
        val downloadUri = Uri.parse(urls[urlIndex])
        require(downloadUri.scheme == "https" && !downloadUri.host.isNullOrBlank()) {
            "Release APK 下载地址无效"
        }
        val asset = requireNotNull(release.asset)
        val destination = UpdateInstaller.destinationFile(context, asset.name)
        if (destination.exists() && !destination.delete()) {
            throw IllegalStateException("无法替换旧的更新安装包")
        }

        val request = DownloadManager.Request(downloadUri)
            .setTitle("Cithub ${release.version}")
            .setDescription("正在下载与当前版本匹配的更新安装包")
            .setMimeType(ApkMimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "updates/${destination.name}",
            )
        val id = downloadManager.enqueue(request)
        return PendingDownload.from(id, destination, release, urls, urlIndex)
    }

    private suspend fun monitor(initial: PendingDownload) {
        var pending = initial
        var release = pending.toRelease()
        mutableState.value = AppUpdateUiState.Downloading(release, null)

        while (viewModelScope.isActive) {
            when (val snapshot = downloadSnapshot(pending.downloadId)) {
                null -> {
                    val fallback = fallbackDownload(pending)
                    if (fallback != null) {
                        pending = fallback
                        release = pending.toRelease()
                        savePending(pending)
                        mutableState.value = AppUpdateUiState.Downloading(release, null)
                    } else {
                        clearPending()
                        mutableState.value = AppUpdateUiState.Failed(release, "下载失败")
                        return
                    }
                }
                is DownloadSnapshot.InProgress -> {
                    mutableState.value = AppUpdateUiState.Downloading(release, snapshot.progress)
                }
                DownloadSnapshot.Failed -> {
                    val fallback = fallbackDownload(pending)
                    if (fallback != null) {
                        pending = fallback
                        release = pending.toRelease()
                        savePending(pending)
                        mutableState.value = AppUpdateUiState.Downloading(release, null)
                    } else {
                        clearPending()
                        mutableState.value = AppUpdateUiState.Failed(release, "下载失败")
                        return
                    }
                }
                DownloadSnapshot.Complete -> {
                    val verified = runCatching {
                        withContext(Dispatchers.IO) {
                            UpdateInstaller.verify(context, File(pending.destinationPath), release, flavor)
                        }
                    }
                    val apk = verified.getOrNull()
                    if (apk != null) {
                        mutableState.value = AppUpdateUiState.Ready(release, apk)
                        return
                    }

                    val fallback = fallbackDownload(pending)
                    if (fallback != null) {
                        pending = fallback
                        release = pending.toRelease()
                        savePending(pending)
                        mutableState.value = AppUpdateUiState.Downloading(release, null)
                    } else {
                        val error = verified.exceptionOrNull()
                        clearPending()
                        File(pending.destinationPath).delete()
                        mutableState.value = AppUpdateUiState.Failed(
                            release,
                            error?.message ?: "安装包安全校验失败",
                        )
                        return
                    }
                }
            }
            delay(750)
        }
    }

    private suspend fun fallbackDownload(pending: PendingDownload): PendingDownload? = withContext(Dispatchers.IO) {
        runCatching {
            downloadManager.remove(pending.downloadId)
            File(pending.destinationPath).delete()
            val nextIndex = pending.urlIndex + 1
            if (nextIndex >= pending.downloadUrls.size) return@runCatching null
            enqueue(pending.toRelease(), pending.downloadUrls, nextIndex)
        }.getOrNull()
    }

    private suspend fun downloadSnapshot(id: Long): DownloadSnapshot? = withContext(Dispatchers.IO) {
        val query = DownloadManager.Query().setFilterById(id)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadSnapshot.Complete
                DownloadManager.STATUS_FAILED -> DownloadSnapshot.Failed
                else -> {
                    val downloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    )
                    val total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    )
                    DownloadSnapshot.InProgress(
                        progress = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null,
                    )
                }
            }
        }
    }

    private fun savePending(pending: PendingDownload) {
        preferences.edit().putString(PendingKey, json.encodeToString(pending)).apply()
    }

    private fun readPending(): PendingDownload? = preferences.getString(PendingKey, null)
        ?.let { encoded -> runCatching { json.decodeFromString<PendingDownload>(encoded) }.getOrNull() }
        ?.takeIf { SemanticVersion.parse(it.version) != null }

    private fun clearPending() {
        preferences.edit().remove(PendingKey).apply()
    }

    private sealed interface DownloadSnapshot {
        data class InProgress(val progress: Float?) : DownloadSnapshot
        data object Failed : DownloadSnapshot
        data object Complete : DownloadSnapshot
    }

    @Serializable
    private data class PendingDownload(
        val downloadId: Long,
        val destinationPath: String,
        val version: String,
        val tagName: String,
        val title: String,
        val notes: String,
        val pageUrl: String,
        val assetName: String,
        val assetSize: Long,
        val assetUrl: String,
        val prerelease: Boolean,
        val downloadUrls: List<String>,
        val urlIndex: Int,
    ) {
        fun toRelease(): AppRelease = AppRelease(
            version = requireNotNull(SemanticVersion.parse(version)),
            tagName = tagName,
            title = title,
            notes = notes,
            pageUrl = pageUrl,
            asset = UpdateAsset(assetName, assetSize, assetUrl),
            prerelease = prerelease,
        )

        companion object {
            fun from(
                id: Long,
                destination: File,
                release: AppRelease,
                downloadUrls: List<String>,
                urlIndex: Int,
            ): PendingDownload {
                val asset = requireNotNull(release.asset)
                return PendingDownload(
                    downloadId = id,
                    destinationPath = destination.absolutePath,
                    version = release.version.toString(),
                    tagName = release.tagName,
                    title = release.title,
                    notes = release.notes,
                    pageUrl = release.pageUrl,
                    assetName = asset.name,
                    assetSize = asset.size,
                    assetUrl = asset.downloadUrl,
                    prerelease = release.prerelease,
                    downloadUrls = downloadUrls,
                    urlIndex = urlIndex,
                )
            }
        }
    }

    companion object {
        private const val PreferencesName = "app_update_download"
        private const val PendingKey = "pending_download"
        private const val ApkMimeType = "application/vnd.android.package-archive"
    }
}
