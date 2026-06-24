package me.earzuchan.chatdrama.framework.llm.misc

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*

internal fun emptyJsonObject() = JsonObject(emptyMap())

internal fun JsonElement.jsonObjectOrNull() = this as? JsonObject

internal fun JsonElement.jsonArrayOrNull() = this as? JsonArray

internal fun JsonElement.jsonPrimitiveOrNull() = runCatching { jsonPrimitive }.getOrNull()

internal fun JsonObject.string(name: String) = this[name]?.jsonPrimitiveOrNull()?.contentOrNull

internal fun JsonObject.int(name: String) = this[name]?.jsonPrimitiveOrNull()?.intOrNull

internal fun JsonObject.boolean(name: String) = this[name]?.jsonPrimitiveOrNull()?.booleanOrNull

internal fun String.toJsonObjectLenient(): JsonObject {
    val parsed = runCatching { Json.parseToJsonElement(this) }.getOrNull()
    return parsed?.jsonObjectOrNull() ?: buildJsonObject { put("_raw", this@toJsonObjectLenient) }
}

internal fun String.toOutputBody(output: OutputContract): OutputBody {
    if (output == OutputContract.Text) return OutputBody.Text(this)
    val parsed = runCatching { Json.parseToJsonElement(this) }.getOrNull()
    return if (parsed != null) OutputBody.Json(parsed, this) else OutputBody.Text(this)
}

internal fun ContentPart.asPlainText() = when (this) {
    is ContentPart.Text -> text
    is ContentPart.ImageUrl -> "[image:$url]"
    is ContentPart.ImageBase64 -> "[image:$mimeType;base64]"
}

internal fun List<ContentPart>.plainText() = joinToString(separator = "\n") { it.asPlainText() }

internal fun TurnItem.Content.asText() = when (val value = body) {
    is OutputBody.Text -> value.text
    is OutputBody.Json -> value.rawText ?: value.value.toString()
}

internal fun Map<String, String>.toJsonObject() = buildJsonObject { forEach { (key, value) -> put(key, value) } }

internal fun JsonObject.merge(other: JsonObject) = buildJsonObject {
    this@merge.forEach { (key, value) -> put(key, value) }
    other.forEach { (key, value) -> put(key, value) }
}

internal fun LlmBlackboard.onlyPrefixed(vararg prefixes: String) = LlmBlackboard(values.filterKeys { key -> prefixes.any { key.startsWith(it) } })

internal fun appendQuery(url: String, key: String, value: String): String {
    val separator = if (url.contains("?")) "&" else "?"
    return "$url$separator$key=$value"
}
