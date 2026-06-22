package me.earzuchan.chatdrama.client.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun TextPreference(value: String, onValueChange: (String) -> Unit, label: String) {
    var showDialog by remember { mutableStateOf(false) }
    var tempValue by remember { mutableStateOf(value) }

    ArrowPreference(label, summary = value.ifEmpty { "你还没设置" }, onClick = {
        showDialog = true
        tempValue = value
    })

    OverlayDialog(showDialog, title = label, onDismissRequest = { showDialog = false }) {
        Column {
            TextField(tempValue, { tempValue = it }, Modifier.padding(top = 12.dp), label = label, useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(24.dp))
            TextButton("确定", {
                onValueChange(tempValue)
                showDialog = false
            }, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}