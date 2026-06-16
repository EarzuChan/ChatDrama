package me.earzuchan.chatdrama.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun ClientTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.Dark, keyColor = Color(0xFF07C160)) }

    return MiuixTheme(controller, content = content)
}