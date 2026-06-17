package me.earzuchan.chatdrama.client

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontMamba
import androidx.compose.ui.window.ComposeViewport
import me.earzuchan.chatdrama.client.debug.probeSkikoWebFonts

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    probeSkikoWebFonts()
    ComposeViewport { FontMamba { Client() } }
}

