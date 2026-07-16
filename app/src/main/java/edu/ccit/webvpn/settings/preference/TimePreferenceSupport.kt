package edu.ccit.webvpn.settings.preference

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

data class HmTime(val hourOfDay: Int, val minute: Int)

@Stable
class DialogState internal constructor() {
    var show by mutableStateOf(false)
        private set

    fun show() { show = true }
    fun dismiss() { show = false }
}

@Composable
fun rememberDialogState(): DialogState = remember { DialogState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialTime: HmTime,
    title: (@Composable () -> Unit)? = null,
    onConfirm: (TimePickerState) -> Unit,
    dialogState: DialogState,
) {
    val state = rememberTimePickerState(initialTime.hourOfDay, initialTime.minute)
    AlertDialog(
        onDismissRequest = dialogState::dismiss,
        title = title,
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state); dialogState.dismiss() }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = dialogState::dismiss) { Text("取消") }
        },
    )
}
