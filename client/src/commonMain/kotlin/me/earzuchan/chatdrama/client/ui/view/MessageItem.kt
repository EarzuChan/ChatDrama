package me.earzuchan.chatdrama.client.ui.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.toString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.earzuchan.chatdrama.client.data.model.DisplayTempFakeMessage
import me.earzuchan.chatdrama.client.data.model.TempAiMessage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun FakeMessageItem(message: DisplayTempFakeMessage) = Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start) {
    if (message.time != null) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
        Text(message.time, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
    }

    Box(Modifier.squircleBackground(if (message.fromMe) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.background, 16.dp).padding(16.dp, 12.dp)) {
        Text(message.content, color = if (message.fromMe) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground, style = MiuixTheme.textStyles.body1)
    }
}

@Composable
fun AiMessageItem(message: TempAiMessage) {
    val fromMe = message is TempAiMessage.FromUser

    val thought = (message as? TempAiMessage.FromLlm)?.thought
    val content = when {
        message.content.isNotBlank() -> message.content

        (message as? TempAiMessage.FromLlm)?.isStreaming == true -> "（正在等待生成)"

        else -> "（内容为空）"
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (fromMe) Alignment.End else Alignment.Start) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) { Text(message.timestamp.toString(), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1) }

        BoxWithConstraints(Modifier.fillMaxWidth(), if (fromMe) Alignment.TopEnd else Alignment.TopStart) {
            val parentMaxWidth = maxWidth

            Column(Modifier.widthIn(max = parentMaxWidth * 0.7f).squircleBackground(if (fromMe) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.background, 16.dp).padding(16.dp, 12.dp), Arrangement.spacedBy(12.dp)) {
                thought?.let { Text(it, color = MiuixTheme.colorScheme.onBackgroundVariant, style = MiuixTheme.textStyles.body1) }

                Text(content, color = if (fromMe) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onBackground, style = MiuixTheme.textStyles.body1)
            }
        }
    }
}
