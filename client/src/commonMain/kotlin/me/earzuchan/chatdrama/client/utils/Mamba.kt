package me.earzuchan.chatdrama.client.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

// UI

@Composable
fun rememberBlurBackdrop(): LayerBackdrop {
    val surfaceColor = MiuixTheme.colorScheme.surface

    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun Modifier.attachBarBlur(backdrop: Backdrop) = textureBlur(backdrop, RectangleShape, 25f, colors = BlurDefaults.blurColors(listOf(BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)))))