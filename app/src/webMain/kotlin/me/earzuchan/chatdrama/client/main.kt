package me.earzuchan.chatdrama.client

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import me.earzuchan.chatdrama.client.laztfont.LocalLazyTextController
import me.earzuchan.chatdrama.client.lazyfont.LazyWebFontFamily
import me.earzuchan.chatdrama.client.lazyfont.loadLazyWebFontFamily

private const val MI_SANS_URL = "https://cdn-font.hyperos.mi.com/font/css?family=MiSans_VF:VF:Chinese_Simplify&display=swap"

@OptIn(ExperimentalComposeUiApi::class)
fun main() = ComposeViewport {
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<LazyWebFontFamily?>(null) }
    LaunchedEffect(Unit) { controller = loadLazyWebFontFamily(MI_SANS_URL, scope) }

    CompositionLocalProvider(LocalLazyTextController provides controller) { Client() }
}