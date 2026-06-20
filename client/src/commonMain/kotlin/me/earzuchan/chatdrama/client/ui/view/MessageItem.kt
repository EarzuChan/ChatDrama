package me.earzuchan.chatdrama.client.ui.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.data.model.DisplayTempFakeMessage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FakeMessageItem(message: DisplayTempFakeMessage) = Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start) {
    if (message.time != null) Box(Modifier.padding(start = 16.dp,end = 16.dp,top = 12.dp,bottom = 8.dp)) {
        Text(message.time, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
    }

    Box(Modifier.squircleBackground(if (message.fromMe) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.background, 16.dp).padding(16.dp,12.dp)) {
        Text(message.content, color = if (message.fromMe) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground, style = MiuixTheme.textStyles.body1)
    }
}