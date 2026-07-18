package edu.ccit.webvpn.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ccit.webvpn.BuildConfig
import edu.ccit.webvpn.update.AcceleratorAvailability
import edu.ccit.webvpn.update.AppUpdateViewModel
import edu.ccit.webvpn.update.ManualUpdateCheckResult
import java.net.URI
import kotlinx.coroutines.flow.collectLatest

private data class AcceleratorEditor(val index: Int? = null, val initialValue: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSettingsScreen(
    settings: Settings<UpdateSettings>,
    initial: UpdateSettings,
    updateViewModel: AppUpdateViewModel,
    onBack: () -> Unit,
) {
    var current by remember(initial) { mutableStateOf(initial) }
    var editor by remember { mutableStateOf<AcceleratorEditor?>(null) }
    val checking by updateViewModel.manualChecking.collectAsStateWithLifecycle()
    val acceleratorAvailability by updateViewModel.acceleratorAvailability.collectAsStateWithLifecycle()
    val checkingAccelerators = acceleratorAvailability.values.any { it == AcceleratorAvailability.Checking }
    val snackbar = remember { SnackbarHostState() }

    fun persist(value: UpdateSettings) {
        current = value
        settings.set(value)
    }

    LaunchedEffect(updateViewModel) {
        updateViewModel.manualResults.collectLatest { result ->
            snackbar.showSnackbar(
                when (result) {
                    ManualUpdateCheckResult.UpToDate -> "已是最新版本"
                    is ManualUpdateCheckResult.Failed -> result.message
                },
            )
        }
    }

    LaunchedEffect(updateViewModel) {
        updateViewModel.checkAccelerators(current.githubAccelerators)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("更新") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = updateViewModel::checkNow, enabled = !checking) {
                        Text(if (checking) "检查中" else "检查更新")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "version") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("当前版本", modifier = Modifier.weight(1f))
                        Text(BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "preview") {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            persist(current.copy(previewReleases = !current.previewReleases))
                        },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("预览版试用", modifier = Modifier.weight(1f))
                        Switch(checked = current.previewReleases, onCheckedChange = null)
                    }
                }
            }
            item(key = "accelerator-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GitHub 加速", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = { updateViewModel.checkAccelerators(current.githubAccelerators) },
                        enabled = current.githubAccelerators.isNotEmpty() && !checkingAccelerators,
                    ) {
                        Text(if (checkingAccelerators) "检测中" else "检测")
                    }
                    TextButton(onClick = { editor = AcceleratorEditor() }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                }
            }
            if (current.githubAccelerators.isEmpty()) {
                item(key = "accelerator-empty") {
                    Text("未配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                itemsIndexed(
                    items = current.githubAccelerators,
                    key = { _, url -> url },
                ) { index, url ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = acceleratorDisplayName(url),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = acceleratorAvailability[url].displayText(),
                                    color = acceleratorAvailability[url].displayColor(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(
                                onClick = {
                                    persist(current.copy(githubAccelerators = current.githubAccelerators.move(index, index - 1)))
                                },
                                enabled = index > 0,
                            ) { Icon(Icons.Outlined.ArrowUpward, contentDescription = "提高优先级") }
                            IconButton(
                                onClick = {
                                    persist(current.copy(githubAccelerators = current.githubAccelerators.move(index, index + 1)))
                                },
                                enabled = index < current.githubAccelerators.lastIndex,
                            ) { Icon(Icons.Outlined.ArrowDownward, contentDescription = "降低优先级") }
                            IconButton(onClick = { editor = AcceleratorEditor(index, url) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                            }
                            IconButton(
                                onClick = {
                                    persist(
                                        current.copy(
                                            githubAccelerators = current.githubAccelerators.filterIndexed { item, _ -> item != index },
                                        ),
                                    )
                                },
                            ) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
                        }
                    }
                }
            }
        }
    }

    editor?.let { editing ->
        AcceleratorDialog(
            editor = editing,
            existing = current.githubAccelerators,
            onDismiss = { editor = null },
            onConfirm = { url ->
                val next = if (editing.index == null) {
                    current.githubAccelerators + url
                } else {
                    current.githubAccelerators.mapIndexed { index, old -> if (index == editing.index) url else old }
                }
                persist(current.copy(githubAccelerators = next))
                updateViewModel.checkAccelerators(next)
                editor = null
            },
        )
    }
}

@Composable
private fun AcceleratorDialog(
    editor: AcceleratorEditor,
    existing: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(editor) { mutableStateOf(editor.initialValue) }
    var error by remember(editor) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.index == null) "添加加速地址" else "编辑加速地址") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("HTTPS 地址") },
                placeholder = { Text("https://mirror.example") },
                supportingText = error?.let { message -> ({ Text(message) }) },
                isError = error != null,
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = normalizeGithubAccelerator(value)
                    error = when {
                        normalized == null -> "请输入有效的 HTTPS 地址"
                        existing.withIndex().any { (index, url) ->
                            index != editor.index && url.equals(normalized, ignoreCase = true)
                        } -> "地址已存在"
                        else -> null
                    }
                    if (error == null) onConfirm(requireNotNull(normalized))
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun List<String>.move(from: Int, to: Int): List<String> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun acceleratorDisplayName(url: String): String = runCatching {
    URI(url).let { uri -> uri.host + uri.rawPath.orEmpty() }
}.getOrDefault(url)

@Composable
private fun AcceleratorAvailability?.displayColor() = when (this) {
    is AcceleratorAvailability.Available -> MaterialTheme.colorScheme.primary
    AcceleratorAvailability.Unavailable -> MaterialTheme.colorScheme.error
    AcceleratorAvailability.Checking, null -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun AcceleratorAvailability?.displayText(): String = when (this) {
    is AcceleratorAvailability.Available -> "可用 · ${latencyMillis} ms"
    AcceleratorAvailability.Unavailable -> "不可用"
    AcceleratorAvailability.Checking -> "检测中"
    null -> "未检测"
}
