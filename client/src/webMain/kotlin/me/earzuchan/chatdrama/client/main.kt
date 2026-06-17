package me.earzuchan.chatdrama.client

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.FontMamba
import androidx.compose.ui.window.ComposeViewport

@OptIn(ExperimentalComposeUiApi::class)
fun main() = ComposeViewport { FontMamba { Client() } }
