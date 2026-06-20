@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package top.yukonga.miuix.kmp.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.ColorSchemeMode.*

val isDarkTheme: Boolean
    @Composable get() = when (LocalColorSchemeMode.current) {
        System, MonetSystem, null -> isSystemInDarkTheme()
        Light, MonetLight -> false
        Dark, MonetDark -> true
    }