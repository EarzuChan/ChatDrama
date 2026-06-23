package me.earzuchan.chatdrama.framework.llm

// 这几个比较无聊

fun textPart(text: String) = ContentPart.Text(text)

fun imageUrlPart(url: String, mimeType: String? = null) = ContentPart.ImageUrl(url, mimeType)

fun imageBase64Part(base64: String, mimeType: String) = ContentPart.ImageBase64(base64, mimeType)

// 这几个还算有意思

fun textContentParts(text: String): List<ContentPart> = listOf(textPart(text))

fun imageUrlContentParts(url: String, mimeType: String? = null): List<ContentPart> = listOf(imageUrlPart(url, mimeType))

fun imageBase64ContentParts(base64: String, mimeType: String): List<ContentPart> = listOf(imageBase64Part(base64, mimeType))

// 这几个初有恩情

fun textContent(text: String, label: String? = null, metadata: Map<String, String> = emptyMap()) = TurnInputItem.Content(textContentParts(text), label, metadata)

fun imageUrlContent(url: String, mimeType: String? = null, label: String? = null, metadata: Map<String, String> = emptyMap()) = TurnInputItem.Content(imageUrlContentParts(url, mimeType), label, metadata)

fun imageBase64Content(base64: String, mimeType: String, label: String? = null, metadata: Map<String, String> = emptyMap()) = TurnInputItem.Content(imageBase64ContentParts(base64, mimeType), label, metadata)

fun content(parts: List<ContentPart>, label: String? = null, metadata: Map<String, String> = emptyMap()) = TurnInputItem.Content(parts, label, metadata)

// 最后送一个

fun TurnItem.Content.text() = asText()
