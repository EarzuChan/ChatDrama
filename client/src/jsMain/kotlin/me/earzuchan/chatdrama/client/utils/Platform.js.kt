package me.earzuchan.chatdrama.client.utils

import web.navigator.navigator

private object JsPlatform {
    private val userAgent = navigator.userAgent
    private val browserList = listOf("Chrome", "Firefox", "Safari", "Edge")

    val name: String = (userAgent.findAnyOf(browserList, ignoreCase = true)?.let { (startIndex) -> userAgent.substring(startIndex).substringBefore(" ") } ?: "未知浏览器") + "上的 JS"
}

actual val platform = JsPlatform.name