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

    // Rebuild出来的爆窗怎么办？没事，发送前应该会过maybeCompact！
    fun rebuildLaneB(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB

    suspend fun request(turn: ProviderTurn<ProviderLaneB>, mode: RequestMode): ProviderTurnCommit<ProviderLaneB>

    suspend fun compact(laneB: ProviderLaneB, config: EffectiveLlmCallConfig): ProviderLaneB {
        // TODO：未来可在这里插入大压缩：手动触发，压 laneB

        // 先优先接入OpenAiResponses、Claude的官方压缩，没有官方压缩的则可能不能压缩（不是不能，但得我们代劳：我们得使用用户的模型，在另外的上下文进行压缩，还有失败的风险）

        return laneB
    }

    suspend fun maybeCompact(laneB: ProviderLaneB, requestNode: TurnRequestNode, config: EffectiveLlmCallConfig): ProviderLaneB {
        // 未来，按阈值触发大压缩：使用场景发新 turn 前
        return laneB
    }

    fun debugLaneB(laneB: ProviderLaneB?) = laneB?.let { providerLaneBDebug("ProviderLaneB", it) } ?: "ProviderLaneB(null)"
}

abstract class HttpProviderBackend : ProviderBackend, KoinComponent {
    protected val client: HttpClient by inject()
    protected val json: Json = defaultLlmJson

    protected suspend fun postJson(url: String, headers: Map<String, String>, body: JsonObject): JsonObject {
        val bodyText = json.encodeToString(JsonObject.serializer(), body)
        printLlmRequestBody(url, bodyText)

        val response = client.post(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)

            headers.forEach { (name, value) -> header(name, value) }
            setBody(bodyText)
        }

        return parseJsonResponse(response)
    }

    protected suspend fun postSse(url: String, headers: Map<String, String>, body: JsonObject, onEvent: suspend (event: String?, data: String) -> Unit): Unit {
        val bodyText = json.encodeToString(JsonObject.serializer(), body)
        printLlmRequestBody(url, bodyText)

        return client.preparePost(url) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)

            headers.forEach { (name, value) -> header(name, value) }
            setBody(bodyText)
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

private const val PRINT_LLM_REQUEST_BODY = true // TIPS：生产时关掉

private fun printLlmRequestBody(url: String, body: String) {
    if (!PRINT_LLM_REQUEST_BODY) return
    println("LLM request -> $url\n$body")
}

internal fun providerLaneBDebug(title: String, laneB: ProviderLaneB) = buildString {
    appendLine(title)
    appendLine("--shape=${laneB.shape}")
    appendLine("--rootRevisionId=${laneB.rootRevisionId}")
    appendLine("--anchorNodeId=${laneB.anchorNodeId}")
    appendLine("--configKey=${laneB.configKey}")
    appendLine("--blackboard=${laneB.blackboard}")
    appendLine("--value=$laneB")
}

internal val defaultLlmJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}
