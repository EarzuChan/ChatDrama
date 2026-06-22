package me.earzuchan.chatdrama.client.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.isDarkTheme


@Composable
private fun BaseMsgBarIconButton(icon: ImageVector, bg: Color, fore: Color, enabled: Boolean = true, onClick: () -> Unit) = IconButton(onClick, enabled = enabled, backgroundColor = bg) { Icon(icon, null, tint = fore) }

@Composable
fun BarMsgIconButton(icon: ImageVector, onClick: () -> Unit = {}) {
    val background = if (isDarkTheme) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.06f)

    val foreground = (if (isDarkTheme) Color.White else Color.Black).copy(alpha = 0.8f)

    BaseMsgBarIconButton(icon, background, foreground, onClick = onClick)
}

@Composable
fun MainMsgBarIconButton(icon: ImageVector, active: Boolean = false, onClick: () -> Unit = {}) {
    val background = when {
        active -> MiuixTheme.colorScheme.primary
        isDarkTheme -> Color.White.copy(0.14f)
        else -> Color.Black.copy(0.06f)
    }

    val foreground = when {
        active -> MiuixTheme.colorScheme.onPrimary
        isDarkTheme -> Color.White.copy(0.3f)
        else -> Color.Black.copy(0.3f)
    }

    BaseMsgBarIconButton(icon, background, foreground, active, onClick)
}