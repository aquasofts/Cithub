package edu.ccit.webvpn.update

import android.content.Context
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Immutable
internal sealed interface AppUpdateUiState {
    data object Hidden : AppUpdateUiState
    data class Available(val release: AppRelease) : AppUpdateUiState
    data class Downloading(
        val release: AppRelease,
        val progress: Float?,
        val speedBytesPerSecond: Long,
    ) : AppUpdateUiState
    data class Ready(val release: AppRelease, val apk: VerifiedUpdateApk) : AppUpdateUiState
    data class Failed(
        val release: AppRelease,
        val message: String,
        val customDownload: Boolean = false,
    ) : AppUpdateUiState
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
    private val mutableState = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Hidden)
    internal val state: StateFlow<AppUpdateUiState> = mutableState.asStateFlow()
    private val mutableManualChecking = MutableStateFlow(false)
    val manualChecking: StateFlow<Boolean> = mutableManualChecking.asStateFlow()
    private val mutableManualResults = MutableSharedFlow<ManualUpdateCheckResult>(extraBufferCapacity = 1)
    val manualResults: SharedFlow<ManualUpdateCheckResult> = mutableManualResults.asSharedFlow()
    private val mutableAcceleratorAvailability = MutableStateFlow<Map<String, AcceleratorAvailability>>(emptyMap())
    internal val acceleratorAvailability: StateFlow<Map<String, AcceleratorAvailability>> =
        mutableAcceleratorAvailability.asStateFlow()
    private var acceleratorCheckJob: Job? = null
    private var downloadMonitorJob: Job? = null
    private var downloadDialogInBackground = false

    init {
        viewModelScope.launch { resumeDownloadOrCheck() }
    }

    internal fun dismiss() {
        if (mutableState.value is AppUpdateUiState.Failed) {
            viewModelScope.launch(Dispatchers.IO) { UpdateDownloadStore.clear(context) }
        }
        mutableState.value = AppUpdateUiState.Hidden
    }

    internal fun startDownload(release: AppRelease) = startDownload(release, customDownload = false)

    internal fun continueDownloadInBackground() {
        if (mutableState.value !is AppUpdateUiState.Downloading) return
        downloadDialogInBackground = true
        mutableState.value = AppUpdateUiState.Hidden
    }

    internal fun startCustomDownload(rawUrl: String) {
        val url = normalizeCustomUpdateUrl(rawUrl)
        val currentVersion = SemanticVersion.parse(BuildConfig.VERSION_NAME)
        if (url == null || currentVersion == null) {
            mutableManualResults.tryEmit(ManualUpdateCheckResult.Failed("请输入有效的 HTTPS 下载链接"))
            return
        }
        startDownload(
            release = AppRelease(
                version = currentVersion,
                tagName = "custom-download",
                title = "自定义更新",
                notes = "通过设置中的自定义 HTTPS 链接下载",
                pageUrl = url,
                asset = UpdateAsset(customUpdateFileName(url), 0L, url),
                prerelease = false,
            ),
            customDownload = true,
        )
    }

    private fun startDownload(release: AppRelease, customDownload: Boolean) {
        val asset = release.asset
        if (asset == null) {
            mutableState.value = AppUpdateUiState.Failed(
                release,
                "这个 Release 没有与当前 Full/Lite 版本匹配的 APK",
                customDownload,
            )
            return
        }
        if (mutableState.value is AppUpdateUiState.Downloading || downloadMonitorJob?.isActive == true) return

        viewModelScope.launch {
            runCatching {
                val settings = updateSettings.snapshot()
                val urls = if (customDownload) {
                    listOf(asset.downloadUrl)
                } else {
                    githubUrlCandidates(asset.downloadUrl, settings.githubAccelerators)
                }
                require(urls.all { url ->
                    val parsed = url.toHttpUrlOrNull()
                    parsed != null && parsed.isHttps
                }) { "Release APK 下载地址无效" }
                val destination = UpdateInstaller.destinationFile(context, asset.name)
                val record = UpdateDownloadRecord.create(
                    destination = destination,
                    release = release,
                    downloadUrls = urls,
                    customDownload = customDownload,
                )
                withContext(Dispatchers.IO) { UpdateDownloadStore.write(context, record) }
                record
            }.onSuccess { record ->
                downloadDialogInBackground = false
                mutableState.value = record.toDownloadingState()
                UpdateDownloadService.start(context)
                monitorDownload(record)
            }.onFailure { error ->
                mutableState.value = AppUpdateUiState.Failed(
                    release,
                    error.message ?: "无法开始下载安装包",
                    customDownload,
                )
            }
        }
    }

    internal fun cancelDownload() {
        val record = UpdateDownloadStore.read(context) ?: run {
            mutableState.value = AppUpdateUiState.Hidden
            return
        }
        downloadMonitorJob?.cancel()
        downloadDialogInBackground = false
        mutableState.value = if (record.customDownload) {
            AppUpdateUiState.Hidden
        } else {
            AppUpdateUiState.Available(record.toRelease())
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                UpdateDownloadStore.write(
                    context,
                    record.copy(
                        status = UpdateDownloadStatus.Cancelled,
                        speedBytesPerSecond = 0L,
                    ),
                )
            }
            UpdateDownloadService.cancel(context)
        }
    }

    internal fun retry(release: AppRelease, customDownload: Boolean) {
        startDownload(release, customDownload)
    }

    internal fun refreshStoredDownload() {
        if (mutableState.value !is AppUpdateUiState.Hidden) return
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { UpdateDownloadStore.read(context) } ?: return@launch
            when (record.status) {
                UpdateDownloadStatus.Queued,
                UpdateDownloadStatus.Downloading,
                -> {
                    downloadDialogInBackground = true
                    if (downloadMonitorJob?.isActive != true) {
                        UpdateDownloadService.start(context)
                        monitorDownload(record)
                    }
                }
                UpdateDownloadStatus.Ready -> {
                    val apk = record.toVerifiedApk()?.takeIf { File(it.path).isFile } ?: return@launch
                    downloadDialogInBackground = false
                    mutableState.value = AppUpdateUiState.Ready(record.toRelease(), apk)
                }
                UpdateDownloadStatus.Failed -> {
                    downloadDialogInBackground = false
                    mutableState.value = AppUpdateUiState.Failed(
                        record.toRelease(),
                        record.errorMessage ?: "下载失败",
                        record.customDownload,
                    )
                }
                UpdateDownloadStatus.Cancelled -> Unit
            }
        }
    }

    fun checkNow() {
        if (mutableManualChecking.value) return
        viewModelScope.launch { checkLatestInternal(manual = true) }
    }

    internal fun checkAccelerators(accelerators: List<String>) {
        val candidates = accelerators.distinct()
        acceleratorCheckJob?.cancel()
        if (candidates.isEmpty()) {
            mutableAcceleratorAvailability.value = emptyMap()
            return
        }

        mutableAcceleratorAvailability.value = candidates.associateWith { AcceleratorAvailability.Checking }
        acceleratorCheckJob = viewModelScope.launch {
            val checker = GitHubAcceleratorChecker(
                userAgent = "Cithub/${BuildConfig.VERSION_NAME} (Android)",
            )
            mutableAcceleratorAvailability.value = coroutineScope {
                candidates.map { accelerator ->
                    async { accelerator to checker.check(accelerator) }
                }.awaitAll().toMap()
            }
        }
    }

    private suspend fun resumeDownloadOrCheck() {
        val record = withContext(Dispatchers.IO) { UpdateDownloadStore.read(context) }
        when (record?.status) {
            UpdateDownloadStatus.Queued,
            UpdateDownloadStatus.Downloading,
            -> {
                downloadDialogInBackground = true
                mutableState.value = AppUpdateUiState.Hidden
                UpdateDownloadService.start(context)
                monitorDownload(record)
            }
            UpdateDownloadStatus.Ready -> {
                val apk = record.toVerifiedApk()?.takeIf { File(it.path).isFile }
                if (apk != null) {
                    downloadDialogInBackground = false
                    mutableState.value = AppUpdateUiState.Ready(record.toRelease(), apk)
                } else {
                    withContext(Dispatchers.IO) { UpdateDownloadStore.clear(context) }
                    checkLatestInternal(manual = false)
                }
            }
            UpdateDownloadStatus.Failed -> {
                downloadDialogInBackground = false
                mutableState.value = AppUpdateUiState.Failed(
                    record.toRelease(),
                    record.errorMessage ?: "下载失败",
                    record.customDownload,
                )
            }
            UpdateDownloadStatus.Cancelled -> {
                withContext(Dispatchers.IO) { UpdateDownloadStore.clear(context) }
                checkLatestInternal(manual = false)
            }
            null -> checkLatestInternal(manual = false)
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
                } else if (manual) {
                    mutableManualResults.tryEmit(ManualUpdateCheckResult.UpToDate)
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

    private fun monitorDownload(initial: UpdateDownloadRecord) {
        downloadMonitorJob?.cancel()
        downloadMonitorJob = viewModelScope.launch {
            var release = initial.toRelease()
            while (true) {
                val record = withContext(Dispatchers.IO) { UpdateDownloadStore.read(context) }
                if (record == null) {
                    mutableState.value = if (initial.customDownload) {
                        AppUpdateUiState.Hidden
                    } else {
                        AppUpdateUiState.Available(release)
                    }
                    return@launch
                }
                release = record.toRelease()
                when (record.status) {
                    UpdateDownloadStatus.Queued,
                    UpdateDownloadStatus.Downloading,
                    -> if (!downloadDialogInBackground) {
                        mutableState.value = record.toDownloadingState()
                    }
                    UpdateDownloadStatus.Ready -> {
                        val apk = record.toVerifiedApk()
                        if (apk != null && File(apk.path).isFile) {
                            downloadDialogInBackground = false
                            mutableState.value = AppUpdateUiState.Ready(release, apk)
                        } else {
                            mutableState.value = AppUpdateUiState.Failed(
                                release,
                                "下载的安装包不存在",
                                record.customDownload,
                            )
                        }
                        return@launch
                    }
                    UpdateDownloadStatus.Failed -> {
                        downloadDialogInBackground = false
                        mutableState.value = AppUpdateUiState.Failed(
                            release,
                            record.errorMessage ?: "下载失败",
                            record.customDownload,
                        )
                        return@launch
                    }
                    UpdateDownloadStatus.Cancelled -> {
                        downloadDialogInBackground = false
                        mutableState.value = if (record.customDownload) {
                            AppUpdateUiState.Hidden
                        } else {
                            AppUpdateUiState.Available(release)
                        }
                        return@launch
                    }
                }
                delay(500L)
            }
        }
    }
}

private fun UpdateDownloadRecord.toDownloadingState(): AppUpdateUiState.Downloading =
    AppUpdateUiState.Downloading(
        release = toRelease(),
        progress = if (assetSize > 0L) {
            (downloadedBytes.toFloat() / assetSize).coerceIn(0f, 1f)
        } else {
            null
        },
        speedBytesPerSecond = speedBytesPerSecond,
    )
