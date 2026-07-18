package edu.ccit.webvpn.update

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun AppUpdateHost(
    updateViewModel: AppUpdateViewModel = viewModel(),
) {
    val state by updateViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var pendingInstallPath by remember { mutableStateOf<String?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }

    fun launchInstaller(path: String) {
        try {
            context.startActivity(UpdateInstaller.installIntent(context, path))
            installError = null
        } catch (_: ActivityNotFoundException) {
            installError = "系统中没有可用的安装器，请从 Release 页面手动安装"
        } catch (error: RuntimeException) {
            installError = error.message ?: "无法打开系统安装器"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val path = pendingInstallPath
        pendingInstallPath = null
        if (path != null && UpdateInstaller.canRequestInstalls(context)) {
            launchInstaller(path)
        } else if (path != null) {
            installError = "需要允许 Cithub 安装未知应用，才能继续覆盖更新"
        }
    }

    LaunchedEffect(state) {
        if (state !is AppUpdateUiState.Ready) installError = null
    }

    when (val current = state) {
        AppUpdateUiState.Hidden -> Unit
        is AppUpdateUiState.Available -> {
            val release = current.release
            AlertDialog(
                onDismissRequest = updateViewModel::dismiss,
                title = { Text(if (release.prerelease) "发现预览版 ${release.version}" else "发现新版本 ${release.version}") },
                text = {
                    ReleaseDetails(release = release)
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (release.asset != null) {
                                updateViewModel.startDownload(release)
                            } else {
                                uriHandler.openUri(release.pageUrl)
                                updateViewModel.dismiss()
                            }
                        },
                    ) {
                        Text(if (release.asset == null) "查看 Release" else "下载更新")
                    }
                },
                dismissButton = {
                    TextButton(onClick = updateViewModel::dismiss) { Text("稍后") }
                },
            )
        }
        is AppUpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = {
                    Text(
                        if (current.release.tagName == "custom-download") {
                            "正在下载自定义更新"
                        } else {
                            "正在下载 ${current.release.version}"
                        },
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val progress = current.progress
                        if (progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("已下载 ${(progress * 100).toInt()}%")
                        }
                        Text(
                            (if (current.singleConnectionFallback) {
                                "Gopeed · 已自动回退单连接"
                            } else {
                                "Gopeed · ${current.connections} 连接"
                            }) +
                                current.speedBytesPerSecond.takeIf { it > 0L }
                                    ?.let { speed -> " · ${formatSize(speed)}/s" }
                                    .orEmpty(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = updateViewModel::cancelDownload) { Text("取消下载") }
                },
            )
        }
        is AppUpdateUiState.Ready -> {
            AlertDialog(
                onDismissRequest = updateViewModel::dismiss,
                title = { Text("更新已下载") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Cithub ${current.release.version}")
                        installError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val path = current.apk.path
                            if (UpdateInstaller.canRequestInstalls(context)) {
                                launchInstaller(path)
                            } else {
                                pendingInstallPath = path
                                permissionLauncher.launch(UpdateInstaller.unknownSourcesSettingsIntent(context))
                            }
                        },
                    ) { Text("安装更新") }
                },
                dismissButton = {
                    TextButton(onClick = updateViewModel::dismiss) { Text("稍后安装") }
                },
            )
        }
        is AppUpdateUiState.Failed -> {
            AlertDialog(
                onDismissRequest = updateViewModel::dismiss,
                title = { Text("更新未完成") },
                text = { Text(current.message) },
                confirmButton = {
                    TextButton(
                        onClick = { updateViewModel.retry(current.release, current.customDownload) },
                    ) { Text("重试") }
                },
                dismissButton = {
                    if (current.customDownload) {
                        TextButton(onClick = updateViewModel::dismiss) { Text("关闭") }
                    } else {
                        TextButton(
                            onClick = {
                                uriHandler.openUri(current.release.pageUrl)
                            },
                        ) { Text("查看 Release") }
                    }
                },
            )
        }
    }
}

@Composable
private fun ReleaseDetails(release: AppRelease) {
    Column(
        modifier = Modifier
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        release.asset?.takeIf { it.size > 0L }?.let { asset -> Text("大小：${formatSize(asset.size)}") }
        if (release.asset == null) {
            Text("未找到匹配安装包", color = MaterialTheme.colorScheme.error)
        }
        Text(release.notes.ifBlank { "未提供更新说明。" })
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
