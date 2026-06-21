package me.earzuchan.chatdrama.client.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.isDarkTheme

// UI

@Composable
fun rememberBlurBackdrop(padColor: Color = MiuixTheme.colorScheme.surface) = rememberLayerBackdrop {
    drawRect(padColor)
    drawContent()
}

@Composable
fun Modifier.attachTopBarBlur(backdrop: Backdrop) = textureBlur(
    backdrop, RectangleShape, if (isDarkTheme) 70f else 25f, colors = BlurDefaults.blurColors(
        if (isDarkTheme) listOf(BlendColorEntry(Color(0x75000000), BlurBlendMode.ColorBurn), BlendColorEntry(Color(0x52000000), BlurBlendMode.SrcOver))
        else listOf(BlendColorEntry(Color(0x33F9F9F9), BlurBlendMode.Overlay), BlendColorEntry(Color(0xB3F7F7F7), BlurBlendMode.HardLight))
    )
)