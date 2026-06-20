package me.earzuchan.chatdrama.client.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MessageTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    val textStyle = MiuixTheme.textStyles.body1.copy(MiuixTheme.colorScheme.onSurface)

    val background = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.17f) // 神秘计算有感觉吗

    BasicTextField(
        value, onValueChange, modifier, textStyle = textStyle, cursorBrush = SolidColor(MiuixTheme.colorScheme.primary), decorationBox = { innerTextField ->
            Box(Modifier.squircleBackground(background, 16.dp).padding(16.dp, 12.dp), Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = textStyle)
                innerTextField()
            }
        }
    )
}
