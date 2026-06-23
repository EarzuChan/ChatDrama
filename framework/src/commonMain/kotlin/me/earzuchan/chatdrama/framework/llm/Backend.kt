package me.earzuchan.chatdrama.framework.llm

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

interface ProviderBackend {
    val shape: ProviderShape
    val capabilities: LlmCapabilities
    val defaultConfig: LlmCallConfig
    suspend fun request(turn: ProviderTurn, mode: RequestMode): TurnResult
}

abstract class HttpProviderBackend : ProviderBackend, KoinComponent {
    protected val client: HttpClient by inject()
    protected val json: Json = defaultLlmJson

    protected suspend fun postJson(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
        val response = client.post(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(this@HttpProviderBackend.json.encodeToString(JsonObject.serializer(), body))
        }

        return parseJsonResponse(response)
    }

    protected suspend fun postSse(url: String, headers: Map<String, String>, body: JsonObject, onEvent: suspend (event: String?, data: String) -> Unit) = client.preparePost(url) {
        contentType(ContentType.Application.Json)
        accept(ContentType.Text.EventStream)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(this@HttpProviderBackend.json.encodeToString(JsonObject.serializer(), body))
    }.execute { response ->
        if (response.status.value !in 200..299) throw response.toLlmException()

        var event: String? = null
        val data = mutableListOf<String>()
        val channel = response.bodyAsChannel()

        while (true) {
            val line = channel.readLine() ?: break
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    onEvent(event, data.joinToString("\n"))
                    data.clear()
                }

                event = null
                continue
            }

            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()

                line.startsWith("data:") -> data.add(line.removePrefix("data:").trimStart())
            }
        }

        if (data.isNotEmpty()) onEvent(event, data.joinToString("\n"))
    }

    protected fun parseJsonElementOrNull(value: String) = runCatching { json.parseToJsonElement(value) }.getOrNull()

    private suspend fun parseJsonResponse(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()

        if (response.status.value !in 200..299) throw LlmProviderException(response.status.value, text.ifBlank { response.status.description }, text, parseJsonElementOrNull(text))
        return parseJsonElementOrNull(text)?.jsonObjectOrNull() ?: throw LlmProviderException(response.status.value, "Expected a JSON object response.", text, null)
    }

    private suspend fun HttpResponse.toLlmException(): LlmProviderException {
        val text = bodyAsText()
        return LlmProviderException(status.value, text.ifBlank { status.description }, text, parseJsonElementOrNull(text))
    }
}

internal val defaultLlmJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}
