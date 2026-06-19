package me.earzuchan.chatdrama.client

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.Out as InjectSans
import androidx.compose.ui.window.ComposeViewport
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
fun main() = ComposeViewport("compose-root") { InjectSans { Client() } }
