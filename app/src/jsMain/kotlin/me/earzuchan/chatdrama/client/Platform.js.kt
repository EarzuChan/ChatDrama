package me.earzuchan.chatdrama.client

import web.navigator.navigator

private object JsPlatform {
    private val userAgent = navigator.userAgent
    private val browserList = listOf("Chrome", "Firefox", "Safari", "Edge")

    val name: String = "JS CANVAS 在 " + (userAgent.findAnyOf(browserList, ignoreCase = true)?.let { (startIndex) -> userAgent.substring(startIndex).substringBefore(" ") } ?: "未知浏览器")
}

actual fun getPlatform(): String = JsPlatform.name