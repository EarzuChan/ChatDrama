package me.earzuchan.chatdrama.client

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.font.Out as InjectSans
import androidx.compose.ui.window.ComposeViewport
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
fun main() {
    registerServiceWorker()

    ComposeViewport("compose-root") { InjectSans { Client() } }
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun registerServiceWorker() {
    js(
        """
        if ("serviceWorker" in navigator) {
            window.addEventListener("load", function () {
                navigator.serviceWorker.register("./sw.js").catch(function (error) {
                    console.warn("Service worker registration failed", error)
                })
            })
        }
        """
    )
}
