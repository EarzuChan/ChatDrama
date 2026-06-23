package me.earzuchan.chatdrama.framework.llm

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlinx.serialization.json.JsonObject as KtJsonObject

interface LlmApi {
    val provider: LlmProvider
    val defaultModel: String
    val capabilities: LlmCapabilities

    suspend fun generate(request: LlmRequest): LlmResponse

    fun stream(request: LlmRequest): Flow<LlmEvent>
}

enum class LlmProvider { OpenAiLegacy, OpenAiResponses, Claude, Gemini }

enum class LlmFeature { Text, Streaming, ImageInput, FunctionCalling, ParallelFunctionCalling, JsonOutput, PromptCaching, Reasoning }

data class LlmCapabilities(val native: Set<LlmFeature>, val bestEffort: Set<LlmFeature> = emptySet()) { // BEST EFFORT：尽力
    val supported: Set<LlmFeature> get() = native + bestEffort

    fun supports(feature: LlmFeature): Boolean = feature in supported

    fun emits(feature: LlmFeature): Boolean = feature in native
}

data class LlmRequest(
    val messages: List<LlmMessage>,
    val model: String? = null,
    val system: List<ContentPart> = emptyList(),
    val tools: List<ToolSpec> = emptyList(),
    val toolConfig: ToolConfig = ToolConfig(),
    val output: OutputMode = OutputMode.Text,
    val reasoning: ReasoningMode = ReasoningMode.Default,
    val cache: CachePolicy = CachePolicy.Auto,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
    val providerOptions: KtJsonObject = emptyJsonObject(),
)

sealed interface LlmMessage {
    data class User(val content: List<ContentPart>) : LlmMessage

    data class Model(val content: List<ContentPart> = emptyList(), val toolCalls: List<ToolCall> = emptyList()) : LlmMessage

    data class ToolResult(val toolCallId: String, val name: String, val content: List<ContentPart>, val isError: Boolean = false) : LlmMessage
}

sealed interface ContentPart {
    val cache: CacheMarker

    data class Text(val text: String, override val cache: CacheMarker = CacheMarker.None) : ContentPart

    data class ImageUrl(val url: String, val mimeType: String? = null, override val cache: CacheMarker = CacheMarker.None) : ContentPart

    data class ImageBase64(val base64: String, val mimeType: String, override val cache: CacheMarker = CacheMarker.None) : ContentPart
}

enum class CacheMarker { None, Ephemeral }

data class ToolSpec(val name: String, val description: String, val inputSchema: KtJsonObject, val strict: Boolean = true) // 定义一种工具

data class ToolConfig(val mode: ToolMode = ToolMode.Auto, val allowParallel: Boolean = true) // 工具更多配置

data class ToolCall(val id: String, val name: String, val arguments: KtJsonObject, val providerOptions: KtJsonObject = emptyJsonObject()) // 一次调用

sealed interface ToolMode {
    data object Auto : ToolMode
    data object None : ToolMode
    data object Required : ToolMode

    data class Force(val name: String) : ToolMode
}

sealed interface OutputMode {
    data object Text : OutputMode
    data object JsonObject : OutputMode

    data class JsonSchema(val name: String, val schema: KtJsonObject, val strict: Boolean = true) : OutputMode
}

sealed interface CachePolicy {
    data object Off : CachePolicy
    data object Auto : CachePolicy

    data class Breakpoints(val breakpoints: List<CacheBreakpoint>) : CachePolicy

    data class CachedContent(val name: String) : CachePolicy
}

data class CacheBreakpoint(val messageIndex: Int, val partIndex: Int? = null)

sealed interface ReasoningMode {
    data object Off : ReasoningMode
    data object Default : ReasoningMode

    data class Effort(val level: ReasoningEffort) : ReasoningMode

    data class BudgetTokens(val tokens: Int) : ReasoningMode
}

enum class ReasoningEffort { Low, Medium, High, Max }

data class LlmResponse(val id: String?, val model: String, val content: List<ContentPart>, val toolCalls: List<ToolCall>, val usage: TokenUsage?, val finishReason: FinishReason, val raw: JsonElement? = null)

data class TokenUsage(val inputTokens: Int?, val outputTokens: Int?, val totalTokens: Int?, val cachedInputTokens: Int? = null, val reasoningTokens: Int? = null)

enum class FinishReason { Stop, Length, ToolCalls, ContentFilter, Error, Unknown }

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent

    data class ReasoningDelta(val text: String) : LlmEvent

    data class ToolCallStarted(val id: String, val name: String) : LlmEvent

    data class ToolArgumentsDelta(val id: String, val delta: String) : LlmEvent

    data class ToolCallCompleted(val toolCall: ToolCall) : LlmEvent

    data class Completed(val response: LlmResponse) : LlmEvent
}

data class OpenaiLegacyConfig(val apiKey: String, val defaultModel: String, val baseUrl: String = "https://api.openai.com/v1", val organization: String? = null, val project: String? = null, val includeStreamUsage: Boolean = true, val sendReasoningEffort: Boolean = true, val extraHeaders: Map<String, String> = emptyMap())

data class OpenaiResponsesConfig(val apiKey: String, val defaultModel: String, val baseUrl: String = "https://api.openai.com/v1", val organization: String? = null, val project: String? = null, val extraHeaders: Map<String, String> = emptyMap())

data class ClaudeConfig(val apiKey: String, val defaultModel: String, val baseUrl: String = "https://api.anthropic.com", val anthropicVersion: String = "2023-06-01", val directBrowserAccess: Boolean = true, val defaultMaxOutputTokens: Int = 4096, val extraHeaders: Map<String, String> = emptyMap())

data class GeminiConfig(val apiKey: String, val defaultModel: String, val baseUrl: String = "https://generativelanguage.googleapis.com", val apiVersion: String = "v1beta", val sendApiKeyAsHeader: Boolean = true, val extraHeaders: Map<String, String> = emptyMap())

private val defaultLlmJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

class OpenaiLegacyApi(private val config: OpenaiLegacyConfig, httpClient: HttpClient? = null, json: Json? = null) : BaseHttpLlmApi(httpClient, json) {
    override val provider: LlmProvider = LlmProvider.OpenAiLegacy
    override val defaultModel: String = config.defaultModel
    override val capabilities: LlmCapabilities = LlmCapabilities(
        native = setOf(LlmFeature.Text, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.FunctionCalling, LlmFeature.ParallelFunctionCalling, LlmFeature.JsonOutput),
        bestEffort = setOf(LlmFeature.PromptCaching, LlmFeature.Reasoning),
    )

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val raw = postJson(chatCompletionsUrl(), headers(), chatCompletionsBody(request, stream = false))
        return parseOpenAiChatResponse(raw, request.model ?: defaultModel)
    }

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        val accumulator = OpenAiChatStreamAccumulator(request.model ?: defaultModel)

        postSse(chatCompletionsUrl(), headers(), chatCompletionsBody(request, stream = true)) { _, data ->
            if (data == "[DONE]") return@postSse
            val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse
            val usage = parseOpenAiUsage(raw["usage"]?.jsonObjectOrNull())
            if (usage != null) accumulator.usage = usage
            val choice = raw["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull() ?: return@postSse
            accumulator.finishReason = parseOpenAiFinishReason(choice.string("finish_reason"))
            val delta = choice["delta"]?.jsonObjectOrNull() ?: return@postSse
            val text = delta.string("content")

            if (!text.isNullOrEmpty()) {
                accumulator.text.append(text)
                send(LlmEvent.TextDelta(text))
            }

            delta["tool_calls"]?.jsonArrayOrNull()?.forEach { item ->
                val tool = item.jsonObjectOrNull() ?: return@forEach
                val index = tool.int("index") ?: 0
                val id = tool.string("id")
                val function = tool["function"]?.jsonObjectOrNull()
                val name = function?.string("name")
                val args = function?.string("arguments")
                val state = accumulator.tool(index, id, name)
                if (state.justStarted) send(LlmEvent.ToolCallStarted(state.id, state.name))
                if (!args.isNullOrEmpty()) {
                    state.arguments.append(args)
                    send(LlmEvent.ToolArgumentsDelta(state.id, args))
                }
            }
        }
        accumulator.completedToolCalls().forEach { send(LlmEvent.ToolCallCompleted(it)) }
        send(LlmEvent.Completed(accumulator.toResponse()))
    }

    private fun chatCompletionsUrl(): String = "${config.baseUrl.trimEnd('/')}/chat/completions"

    private fun headers(): Map<String, String> = buildMap {
        put(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
        config.organization?.let { put("OpenAI-Organization", it) }
        config.project?.let { put("OpenAI-Project", it) }
        putAll(config.extraHeaders)
    }

    private fun chatCompletionsBody(request: LlmRequest, stream: Boolean): KtJsonObject {
        val effective = effectiveRequest(request, stream)
        val body = buildJsonObject {
            put("model", effective.model ?: defaultModel)
            put("messages", openAiChatMessages(effective))
            effective.temperature?.let { put("temperature", it) }
            effective.maxOutputTokens?.let { put("max_tokens", it) }

            if (stream) {
                put("stream", true)
                if (config.includeStreamUsage) put("stream_options", buildJsonObject { put("include_usage", true) })
            }

            if (effective.tools.isNotEmpty() && effective.toolConfig.mode != ToolMode.None) {
                put("tools", buildJsonArray { effective.tools.forEach { add(openAiTool(it)) } })
                put("tool_choice", openAiToolChoice(effective.toolConfig.mode))
                put("parallel_tool_calls", effective.toolConfig.allowParallel)
            } else if (effective.toolConfig.mode == ToolMode.None) put("tool_choice", "none")

            openAiResponseFormat(effective.output)?.let { put("response_format", it) }

            if (config.sendReasoningEffort) openAiReasoningEffort(effective.reasoning)?.let { put("reasoning_effort", it) }
            if (effective.metadata.isNotEmpty()) put("metadata", effective.metadata.toJsonObject())
        }
        return body.merge(effective.providerOptions)
    }

    private fun openAiChatMessages(request: LlmRequest): JsonArray = buildJsonArray {
        if (request.system.isNotEmpty()) add(buildJsonObject {
            put("role", "system")
            put("content", request.system.plainText())
        })

        request.messages.forEach { message ->
            when (message) {
                is LlmMessage.User -> add(buildJsonObject {
                    put("role", "user")
                    put("content", openAiInputContent(message.content))
                })

                is LlmMessage.Model -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", message.content.plainText())

                    if (message.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                        message.toolCalls.forEach { toolCall ->
                            add(buildJsonObject {
                                put("id", toolCall.id)
                                put("type", "function")

                                put("function", buildJsonObject {
                                    put("name", toolCall.name)
                                    put("arguments", toolCall.arguments.toString())
                                })
                            })
                        }
                    })
                })

                is LlmMessage.ToolResult -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", message.toolCallId)
                    put("name", message.name)
                    put("content", message.content.plainText())
                })
            }
        }
    }
}

class OpenaiResponsesApi(private val config: OpenaiResponsesConfig, httpClient: HttpClient? = null, json: Json? = null) : BaseHttpLlmApi(httpClient, json) {
    override val provider: LlmProvider = LlmProvider.OpenAiResponses
    override val defaultModel: String = config.defaultModel
    override val capabilities: LlmCapabilities = LlmCapabilities(
        native = setOf(LlmFeature.Text, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.FunctionCalling, LlmFeature.ParallelFunctionCalling, LlmFeature.JsonOutput),
        bestEffort = setOf(LlmFeature.PromptCaching, LlmFeature.Reasoning),
    )

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val raw = postJson(responsesUrl(), headers(), responsesBody(request, stream = false))
        return parseOpenAiResponsesResponse(raw, request.model ?: defaultModel)
    }

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        val accumulator = OpenAiResponsesStreamAccumulator(request.model ?: defaultModel)
        postSse(responsesUrl(), headers(), responsesBody(request, stream = true)) { event, data ->
            val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse

            when (event) {
                "response.output_text.delta" -> {
                    val text = raw.string("delta").orEmpty()
                    if (text.isNotEmpty()) {
                        accumulator.text.append(text)
                        send(LlmEvent.TextDelta(text))
                    }
                }

                "response.reasoning_text.delta", "response.reasoning.delta" -> {
                    val text = raw.string("delta").orEmpty()
                    if (text.isNotEmpty()) send(LlmEvent.ReasoningDelta(text))
                }

                "response.output_item.added" -> {
                    val item = raw["item"]?.jsonObjectOrNull() ?: return@postSse
                    if (item.string("type") == "function_call") {
                        val id = item.string("call_id") ?: item.string("id") ?: "call_${accumulator.tools.size}"
                        val name = item.string("name") ?: "function"
                        accumulator.tool(id, name)
                        send(LlmEvent.ToolCallStarted(id, name))
                    }
                }

                "response.function_call_arguments.delta" -> {
                    val id = raw.string("call_id") ?: raw.string("item_id") ?: accumulator.lastToolId
                    val delta = raw.string("delta").orEmpty()
                    if (!id.isNullOrEmpty() && delta.isNotEmpty()) {
                        accumulator.tool(id, raw.string("name") ?: "function").arguments.append(delta)
                        send(LlmEvent.ToolArgumentsDelta(id, delta))
                    }
                }

                "response.completed" -> {
                    val response = raw["response"]?.jsonObjectOrNull()
                    if (response != null) accumulator.fromCompleted(response)
                }
            }
        }

        accumulator.completedToolCalls().forEach { send(LlmEvent.ToolCallCompleted(it)) }
        send(LlmEvent.Completed(accumulator.toResponse()))
    }

    private fun responsesUrl(): String = "${config.baseUrl.trimEnd('/')}/responses"

    private fun headers(): Map<String, String> = buildMap {
        put(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
        config.organization?.let { put("OpenAI-Organization", it) }
        config.project?.let { put("OpenAI-Project", it) }
        putAll(config.extraHeaders)
    }

    private fun responsesBody(request: LlmRequest, stream: Boolean): KtJsonObject {
        val effective = effectiveRequest(request, stream)
        val body = buildJsonObject {
            put("model", effective.model ?: defaultModel)
            if (effective.system.isNotEmpty()) put("instructions", effective.system.plainText())
            put("input", openAiResponsesInput(effective))
            effective.temperature?.let { put("temperature", it) }
            effective.maxOutputTokens?.let { put("max_output_tokens", it) }
            if (stream) put("stream", true)

            if (effective.tools.isNotEmpty() && effective.toolConfig.mode != ToolMode.None) {
                put("tools", buildJsonArray { effective.tools.forEach { add(openAiResponsesTool(it)) } })
                put("tool_choice", openAiResponsesToolChoice(effective.toolConfig.mode))
                put("parallel_tool_calls", effective.toolConfig.allowParallel)
            } else if (effective.toolConfig.mode == ToolMode.None) put("tool_choice", "none")

            openAiResponsesTextFormat(effective.output)?.let { put("text", buildJsonObject { put("format", it) }) }
            openAiResponsesReasoning(effective.reasoning)?.let { put("reasoning", it) }
            if (effective.metadata.isNotEmpty()) put("metadata", effective.metadata.toJsonObject())
        }
        return body.merge(effective.providerOptions)
    }

    private fun openAiResponsesInput(request: LlmRequest) = buildJsonArray {
        request.messages.forEach { message ->
            when (message) {
                is LlmMessage.User -> add(buildJsonObject {
                    put("role", "user")
                    put("content", openAiResponsesInputContent(message.content))
                })

                is LlmMessage.Model -> {
                    if (message.content.isNotEmpty()) add(buildJsonObject {
                        put("role", "assistant")
                        put("content", message.content.plainText())
                    })

                    message.toolCalls.forEach { toolCall ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", toolCall.id)
                            put("name", toolCall.name)
                            put("arguments", toolCall.arguments.toString())
                        })
                    }
                }

                is LlmMessage.ToolResult -> add(buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", message.toolCallId)
                    put("output", message.content.plainText())
                })
            }
        }
    }
}

class ClaudeApi(private val config: ClaudeConfig, httpClient: HttpClient? = null, json: Json? = null) : BaseHttpLlmApi(httpClient, json) {
    override val provider: LlmProvider = LlmProvider.Claude
    override val defaultModel: String = config.defaultModel
    override val capabilities: LlmCapabilities = LlmCapabilities(
        native = setOf(LlmFeature.Text, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.FunctionCalling, LlmFeature.ParallelFunctionCalling, LlmFeature.JsonOutput),
        bestEffort = setOf(LlmFeature.PromptCaching, LlmFeature.Reasoning),
    )

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val effective = effectiveRequest(request, stream = false)
        val syntheticOutput = claudeSyntheticOutputTool(effective)
        val raw = postJson(messagesUrl(), headers(), claudeBody(effective, stream = false, syntheticOutput = syntheticOutput))
        return parseClaudeResponse(raw, effective.model ?: defaultModel, syntheticOutput?.name)
    }

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        val effective = effectiveRequest(request, stream = true)
        val syntheticOutput = claudeSyntheticOutputTool(effective)
        val accumulator = ClaudeStreamAccumulator(effective.model ?: defaultModel, syntheticOutput?.name)
        postSse(messagesUrl(), headers(), claudeBody(effective, stream = true, syntheticOutput = syntheticOutput)) { event, data ->
            val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse

            when (event) {
                "message_start" -> accumulator.messageId = raw["message"]?.jsonObjectOrNull()?.string("id")

                "content_block_start" -> {
                    val index = raw.int("index") ?: 0
                    val block = raw["content_block"]?.jsonObjectOrNull() ?: return@postSse
                    if (block.string("type") == "tool_use") {
                        val id = block.string("id") ?: "tool_$index"
                        val name = block.string("name") ?: "function"
                        accumulator.tool(index, id, name)
                        if (name != syntheticOutput?.name) send(LlmEvent.ToolCallStarted(id, name))
                    }
                }

                "content_block_delta" -> {
                    val index = raw.int("index") ?: 0
                    val delta = raw["delta"]?.jsonObjectOrNull() ?: return@postSse

                    when (delta.string("type")) {
                        "text_delta" -> {
                            val text = delta.string("text").orEmpty()
                            if (text.isNotEmpty()) {
                                accumulator.text.append(text)
                                send(LlmEvent.TextDelta(text))
                            }
                        }

                        "thinking_delta" -> {
                            val text = delta.string("thinking").orEmpty()
                            if (text.isNotEmpty()) send(LlmEvent.ReasoningDelta(text))
                        }

                        "input_json_delta" -> {
                            val partial = delta.string("partial_json").orEmpty()
                            val state = accumulator.tools[index]

                            if (state != null && partial.isNotEmpty()) {
                                state.arguments.append(partial)
                                if (state.name != syntheticOutput?.name) send(LlmEvent.ToolArgumentsDelta(state.id, partial))
                            }
                        }
                    }
                }

                "message_delta" -> {
                    val usage = raw["usage"]?.jsonObjectOrNull()
                    if (usage != null) accumulator.usage = parseClaudeUsage(usage)

                    raw["delta"]?.jsonObjectOrNull()?.string("stop_reason")?.let { accumulator.finishReason = parseClaudeFinishReason(it) }
                }
            }
        }

        accumulator.completedToolCalls().forEach { send(LlmEvent.ToolCallCompleted(it)) }
        send(LlmEvent.Completed(accumulator.toResponse()))
    }

    private fun messagesUrl(): String = "${config.baseUrl.trimEnd('/')}/v1/messages"

    private fun headers(): Map<String, String> = buildMap {
        put("x-api-key", config.apiKey)
        put("anthropic-version", config.anthropicVersion)
        if (config.directBrowserAccess) put("anthropic-dangerous-direct-browser-access", "true")
        putAll(config.extraHeaders)
    }

    private fun claudeBody(request: LlmRequest, stream: Boolean, syntheticOutput: ToolSpec?): KtJsonObject {
        val body = buildJsonObject {
            put("model", request.model ?: defaultModel)
            put("max_tokens", request.maxOutputTokens ?: config.defaultMaxOutputTokens)
            put("messages", claudeMessages(request))

            if (request.system.isNotEmpty() || (request.output !is OutputMode.Text && syntheticOutput == null)) put("system", claudeSystem(request, syntheticOutput))

            request.temperature?.let { put("temperature", it) }
            if (stream) put("stream", true)

            val tools = buildList {
                if (request.toolConfig.mode != ToolMode.None) addAll(request.tools)
                syntheticOutput?.let { add(it) }
            }

            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray { tools.forEach { add(claudeTool(it)) } })
                put("tool_choice", claudeToolChoice(request.toolConfig, syntheticOutput))
            }

            claudeThinking(request.reasoning)?.let { put("thinking", it) }
            if (request.metadata.isNotEmpty()) put("metadata", request.metadata.toJsonObject())
        }
        return body.merge(request.providerOptions)
    }

    private fun claudeMessages(request: LlmRequest): JsonArray = buildJsonArray {
        request.messages.forEachIndexed { index, message ->
            when (message) {
                is LlmMessage.User -> add(buildJsonObject {
                    put("role", "user")
                    put("content", claudeContent(message.content, request.cache, index))
                })

                is LlmMessage.Model -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        message.content.forEachIndexed { partIndex, part -> add(claudeContentBlock(part, request.cache.shouldCache(index, partIndex))) }

                        message.toolCalls.forEach { toolCall ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", toolCall.id)
                                put("name", toolCall.name)
                                put("input", toolCall.arguments)
                            })
                        }
                    })
                })

                is LlmMessage.ToolResult -> add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", message.toolCallId)
                            put("content", message.content.plainText())
                            if (message.isError) put("is_error", true)
                        })
                    })
                })
            }
        }
    }

    private fun claudeSystem(request: LlmRequest, syntheticOutput: ToolSpec?): JsonArray = buildJsonArray {
        request.system.forEachIndexed { index, part -> add(claudeContentBlock(part, request.cache.shouldCache(-1, index))) }

        if (request.output !is OutputMode.Text && syntheticOutput == null) add(buildJsonObject {
            put("type", "text")
            put("text", request.output.jsonInstruction())
        })
    }

    private fun claudeSyntheticOutputTool(request: LlmRequest): ToolSpec? {
        if (request.output is OutputMode.Text) return null
        if (request.tools.isNotEmpty()) return null

        val schema: KtJsonObject = when (val output = request.output) {
            OutputMode.JsonObject -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", true)
            }

            is OutputMode.JsonSchema -> output.schema
            OutputMode.Text -> emptyJsonObject()
        }

        return ToolSpec("__structured_output", "Return the final answer as validated JSON.", schema, true)
    }
}

class GeminiApi(private val config: GeminiConfig, httpClient: HttpClient? = null, json: Json? = null) : BaseHttpLlmApi(httpClient, json) {
    override val provider: LlmProvider = LlmProvider.Gemini
    override val defaultModel: String = config.defaultModel
    override val capabilities: LlmCapabilities = LlmCapabilities(
        native = setOf(LlmFeature.Text, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.FunctionCalling, LlmFeature.ParallelFunctionCalling, LlmFeature.JsonOutput),
        bestEffort = setOf(LlmFeature.PromptCaching, LlmFeature.Reasoning),
    )

    override suspend fun generate(request: LlmRequest): LlmResponse {
        val effective = effectiveRequest(request, stream = false)
        val raw = postGeminiJson(geminiUrl(effective.model ?: defaultModel, stream = false), headers(), geminiBody(effective))
        return parseGeminiResponse(raw, effective.model ?: defaultModel)
    }

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        val effective = effectiveRequest(request, stream = true)
        val model = effective.model ?: defaultModel
        val accumulator = GeminiStreamAccumulator(model)

        postGeminiSse(geminiUrl(model, stream = true), headers(), geminiBody(effective)) { _, data ->
            val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postGeminiSse
            val candidate = raw["candidates"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
            val parts = candidate?.get("content")?.jsonObjectOrNull()?.get("parts")?.jsonArrayOrNull().orEmpty()
            val firstToolOrdinal = accumulator.toolOrdinal
            var localToolOrdinal = 0

            parts.forEach { partElement ->
                val part = partElement.jsonObjectOrNull() ?: return@forEach
                val text = part.string("text")
                if (!text.isNullOrEmpty()) {
                    if (part.boolean("thought") == true) send(LlmEvent.ReasoningDelta(text)) else send(LlmEvent.TextDelta(text))
                }
                val functionCall = part["functionCall"]?.jsonObjectOrNull()

                if (functionCall != null) {
                    val name = functionCall.string("name") ?: "function"
                    val id = functionCall.string("id") ?: "gemini:$name:${firstToolOrdinal + localToolOrdinal}"
                    localToolOrdinal += 1
                    val args = functionCall["args"]?.jsonObjectOrNull() ?: emptyJsonObject()
                    val call = ToolCall(id, name, args, part.geminiToolProviderOptions())
                    send(LlmEvent.ToolCallStarted(call.id, call.name))
                    send(LlmEvent.ToolCallCompleted(call))
                }
            }
            accumulator.absorb(raw)
        }
        send(LlmEvent.Completed(accumulator.toResponse()))
    }

    private fun geminiUrl(model: String, stream: Boolean): String {
        val action = if (stream) "streamGenerateContent" else "generateContent"
        val base = "${config.baseUrl.trimEnd('/')}/${config.apiVersion}/models/$model:$action"
        return if (stream) "$base?alt=sse" else base
    }

    private fun headers() = buildMap {
        if (config.sendApiKeyAsHeader) put("x-goog-api-key", config.apiKey)
        putAll(config.extraHeaders)
    }

    private suspend fun postGeminiJson(url: String, headers: Map<String, String>, body: KtJsonObject): KtJsonObject {
        val finalUrl = if (config.sendApiKeyAsHeader) url else appendQuery(url, "key", config.apiKey)

        return super.postJson(finalUrl, headers, body)
    }

    private suspend fun postGeminiSse(url: String, headers: Map<String, String>, body: KtJsonObject, onEvent: suspend (event: String?, data: String) -> Unit) {
        val finalUrl = if (config.sendApiKeyAsHeader) url else appendQuery(url, "key", config.apiKey)
        super.postSse(finalUrl, headers, body, onEvent)
    }

    private fun geminiBody(request: LlmRequest): KtJsonObject {
        val body = buildJsonObject {
            put("contents", geminiContents(request))

            if (request.system.isNotEmpty()) put("systemInstruction", buildJsonObject { put("parts", geminiParts(request.system)) })

            val generationConfig = geminiGenerationConfig(request)
            if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)

            if (request.tools.isNotEmpty() && request.toolConfig.mode != ToolMode.None) {
                put("tools", buildJsonArray {
                    add(buildJsonObject { put("functionDeclarations", buildJsonArray { request.tools.forEach { add(geminiFunctionDeclaration(it)) } }) })
                })
                put("toolConfig", geminiToolConfig(request.toolConfig))
            }

            if (request.cache is CachePolicy.CachedContent) put("cachedContent", request.cache.name)
        }
        return body.merge(request.providerOptions)
    }

    private fun geminiContents(request: LlmRequest): JsonArray = buildJsonArray {
        request.messages.forEach { message ->
            when (message) {
                is LlmMessage.User -> add(buildJsonObject {
                    put("role", "user")
                    put("parts", geminiParts(message.content))
                })

                is LlmMessage.Model -> add(buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        geminiParts(message.content).forEach { add(it) }
                        message.toolCalls.forEach { toolCall -> add(geminiFunctionCallPart(toolCall)) }
                    })
                })

                is LlmMessage.ToolResult -> add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", message.name)
                                put("response", buildJsonObject {
                                    put("toolCallId", message.toolCallId)
                                    put("result", message.content.plainText())
                                    put("isError", message.isError)
                                })
                            })
                        })
                    })
                })
            }
        }
    }
}

abstract class BaseHttpLlmApi(httpClient: HttpClient?, json: Json?) : LlmApi, KoinComponent {
    private val injectedHttpClient: HttpClient by inject()

    protected val client: HttpClient = httpClient ?: injectedHttpClient
    protected val json: Json = json ?: defaultLlmJson

    protected fun effectiveRequest(request: LlmRequest, stream: Boolean): LlmRequest {
        val requiredUnsupported = request.requiredFeatures(stream).filterNot { capabilities.emits(it) }

        if (requiredUnsupported.isNotEmpty()) throw UnsupportedCapabilityException(provider, requiredUnsupported.toSet())

        return if (request.toolConfig.allowParallel && LlmFeature.ParallelFunctionCalling !in capabilities.native) request.copy(toolConfig = request.toolConfig.copy(allowParallel = false)) else request
    }

    protected suspend fun postJson(url: String, headers: Map<String, String>, body: KtJsonObject): KtJsonObject {
        val response = client.post(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(KtJsonObject.serializer(), body))
        }

        return parseJsonResponse(response)
    }

    protected suspend fun postSse(url: String, headers: Map<String, String>, body: KtJsonObject, onEvent: suspend (event: String?, data: String) -> Unit) {
        client.preparePost(url) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            headers.forEach { (name, value) -> header(name, value) }
            setBody(json.encodeToString(KtJsonObject.serializer(), body))
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

    private suspend fun parseJsonResponse(response: HttpResponse): KtJsonObject {
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) throw LlmApiException(
            statusCode = response.status.value,
            message = text.ifBlank { response.status.description },
            body = text,
            raw = parseJsonElementOrNull(text),
        )

        return parseJsonElementOrNull(text)?.jsonObjectOrNull() ?: throw LlmApiException(response.status.value, "Expected a JSON object response.", text, null)
    }

    private suspend fun HttpResponse.toLlmException(): LlmApiException {
        val text = bodyAsText()

        return LlmApiException(status.value, text.ifBlank { status.description }, text, parseJsonElementOrNull(text))
    }

    protected fun parseJsonElementOrNull(value: String) = runCatching { json.parseToJsonElement(value) }.getOrNull()
}

class LlmApiException(val statusCode: Int, override val message: String, val body: String, val raw: JsonElement?) : RuntimeException(message)

class UnsupportedCapabilityException(val provider: LlmProvider, val features: Set<LlmFeature>) : IllegalArgumentException("Provider $provider does not support requested capabilities: ${features.joinToString()}")

private data class MutableToolCall(val id: String, val name: String, val arguments: StringBuilder = StringBuilder(), val justStarted: Boolean = false) {
    fun toToolCall() = ToolCall(id, name, arguments.toString().toJsonObjectLenient())
}

private class OpenAiChatStreamAccumulator(private val model: String) {
    val text: StringBuilder = StringBuilder()
    var usage: TokenUsage? = null
    var finishReason: FinishReason = FinishReason.Unknown
    private val toolsByIndex: MutableMap<Int, MutableToolCall> = mutableMapOf()

    fun tool(index: Int, id: String?, name: String?): MutableToolCall {
        val existing = toolsByIndex[index]
        if (existing != null) return existing
        val newTool = MutableToolCall(id ?: "call_$index", name ?: "function", justStarted = true)
        toolsByIndex[index] = newTool
        return newTool
    }

    fun completedToolCalls(): List<ToolCall> = toolsByIndex.entries.sortedBy { it.key }.map { it.value.toToolCall() }

    fun toResponse() = LlmResponse(null, model, text.toString().toContentParts(), completedToolCalls(), usage, if (completedToolCalls().isNotEmpty()) FinishReason.ToolCalls else finishReason)
}

private class OpenAiResponsesStreamAccumulator(private val model: String) {
    val text: StringBuilder = StringBuilder()
    val tools: MutableMap<String, MutableToolCall> = mutableMapOf()
    var lastToolId: String? = null
    var completedRaw: KtJsonObject? = null

    fun tool(id: String, name: String): MutableToolCall {
        lastToolId = id
        return tools.getOrPut(id) { MutableToolCall(id, name) }
    }

    fun fromCompleted(response: KtJsonObject) {
        completedRaw = response
    }

    fun completedToolCalls(): List<ToolCall> = completedRaw?.let { parseOpenAiResponsesResponse(it, model).toolCalls } ?: tools.values.map { it.toToolCall() }

    fun toResponse() = completedRaw?.let { parseOpenAiResponsesResponse(it, model) } ?: LlmResponse(null, model, text.toString().toContentParts(), completedToolCalls(), null, if (tools.isNotEmpty()) FinishReason.ToolCalls else FinishReason.Unknown)
}

private class ClaudeStreamAccumulator(private val model: String, private val syntheticToolName: String?) {
    var messageId: String? = null
    val text: StringBuilder = StringBuilder()
    val tools: MutableMap<Int, MutableToolCall> = mutableMapOf()
    var usage: TokenUsage? = null
    var finishReason: FinishReason = FinishReason.Unknown

    fun tool(index: Int, id: String, name: String): MutableToolCall = tools.getOrPut(index) { MutableToolCall(id, name) }

    fun completedToolCalls(): List<ToolCall> = tools.values.filter { it.name != syntheticToolName }.map { it.toToolCall() }

    fun toResponse(): LlmResponse {
        val syntheticOutput = tools.values.firstOrNull { it.name == syntheticToolName }

        return LlmResponse(messageId, model, syntheticOutput?.arguments?.toString()?.toContentParts() ?: text.toString().toContentParts(), completedToolCalls(), usage, if (completedToolCalls().isNotEmpty()) FinishReason.ToolCalls else finishReason)
    }
}

private class GeminiStreamAccumulator(private val model: String) {
    private val text: StringBuilder = StringBuilder()
    private val toolCalls: MutableList<ToolCall> = mutableListOf()
    private var usage: TokenUsage? = null
    private var finishReason: FinishReason = FinishReason.Unknown
    var toolOrdinal: Int = 0; private set

    fun absorb(raw: KtJsonObject) {
        usage = parseGeminiUsage(raw["usageMetadata"]?.jsonObjectOrNull()) ?: usage
        val candidate = raw["candidates"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull() ?: return
        finishReason = parseGeminiFinishReason(candidate.string("finishReason"))

        candidate["content"]?.jsonObjectOrNull()?.get("parts")?.jsonArrayOrNull().orEmpty().forEach { partElement ->
            val part = partElement.jsonObjectOrNull() ?: return@forEach
            if (part.boolean("thought") != true) part.string("text")?.let { text.append(it) }
            part["functionCall"]?.jsonObjectOrNull()?.let { functionCall ->
                val name = functionCall.string("name") ?: "function"
                val id = functionCall.string("id") ?: "gemini:$name:$toolOrdinal"
                val args = functionCall["args"]?.jsonObjectOrNull() ?: emptyJsonObject()
                toolCalls.add(ToolCall(id, name, args, part.geminiToolProviderOptions()))
                toolOrdinal += 1
            }
        }
    }

    fun toResponse(): LlmResponse = LlmResponse(null, model, text.toString().toContentParts(), toolCalls, usage, if (toolCalls.isNotEmpty()) FinishReason.ToolCalls else finishReason)
}

private fun openAiInputContent(parts: List<ContentPart>): JsonElement {
    if (parts.all { it is ContentPart.Text }) return JsonPrimitive(parts.plainText())

    return buildJsonArray {
        parts.forEach { part ->
            when (part) {
                is ContentPart.Text -> add(buildJsonObject {
                    put("type", "text")
                    put("text", part.text)
                })

                is ContentPart.ImageUrl -> add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", part.url) })
                })

                is ContentPart.ImageBase64 -> add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", "data:${part.mimeType};base64,${part.base64}") })
                })
            }
        }
    }
}

private fun openAiResponsesInputContent(parts: List<ContentPart>): JsonElement {
    if (parts.all { it is ContentPart.Text }) return JsonPrimitive(parts.plainText())

    return buildJsonArray {
        parts.forEach { part ->
            when (part) {
                is ContentPart.Text -> add(buildJsonObject {
                    put("type", "input_text")
                    put("text", part.text)
                })

                is ContentPart.ImageUrl -> add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", part.url)
                })

                is ContentPart.ImageBase64 -> add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", "data:${part.mimeType};base64,${part.base64}")
                })
            }
        }
    }
}

private fun openAiTool(tool: ToolSpec) = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", tool.inputSchema)
        put("strict", tool.strict)
    })
}

private fun openAiResponsesTool(tool: ToolSpec) = buildJsonObject {
    put("type", "function")
    put("name", tool.name)
    put("description", tool.description)
    put("parameters", tool.inputSchema)
    put("strict", tool.strict)
}

private fun openAiToolChoice(mode: ToolMode): JsonElement = when (mode) {
    ToolMode.Auto -> JsonPrimitive("auto")

    ToolMode.None -> JsonPrimitive("none")

    ToolMode.Required -> JsonPrimitive("required")

    is ToolMode.Force -> buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject { put("name", mode.name) })
    }
}

private fun openAiResponsesToolChoice(mode: ToolMode): JsonElement = when (mode) {
    ToolMode.Auto -> JsonPrimitive("auto")

    ToolMode.None -> JsonPrimitive("none")

    ToolMode.Required -> JsonPrimitive("required")

    is ToolMode.Force -> buildJsonObject {
        put("type", "function")
        put("name", mode.name)
    }
}

private fun openAiResponseFormat(output: OutputMode): KtJsonObject? = when (output) {
    OutputMode.Text -> null

    OutputMode.JsonObject -> buildJsonObject { put("type", "json_object") }

    is OutputMode.JsonSchema -> buildJsonObject {
        put("type", "json_schema")
        put("json_schema", buildJsonObject {
            put("name", output.name)
            put("schema", output.schema)
            put("strict", output.strict)
        })
    }
}

private fun openAiResponsesTextFormat(output: OutputMode): KtJsonObject? = when (output) {
    OutputMode.Text -> null

    OutputMode.JsonObject -> buildJsonObject { put("type", "json_object") }

    is OutputMode.JsonSchema -> buildJsonObject {
        put("type", "json_schema")
        put("name", output.name)
        put("schema", output.schema)
        put("strict", output.strict)
    }
}

private fun openAiReasoningEffort(reasoning: ReasoningMode): String? = when (reasoning) {
    ReasoningMode.Default, ReasoningMode.Off -> null

    is ReasoningMode.Effort -> reasoning.level.apiValue()

    is ReasoningMode.BudgetTokens -> null
}

private fun openAiResponsesReasoning(reasoning: ReasoningMode): KtJsonObject? = when (reasoning) {
    ReasoningMode.Default -> null

    ReasoningMode.Off -> buildJsonObject { put("effort", "minimal") }

    is ReasoningMode.Effort -> buildJsonObject { put("effort", reasoning.level.apiValue()) }

    is ReasoningMode.BudgetTokens -> buildJsonObject { put("max_output_tokens", reasoning.tokens) }
}

private fun claudeContent(parts: List<ContentPart>, cache: CachePolicy, messageIndex: Int) = buildJsonArray {
    parts.forEachIndexed { partIndex, part ->
        add(claudeContentBlock(part, cache.shouldCache(messageIndex, partIndex)))
    }
}

private fun claudeContentBlock(part: ContentPart, cache: Boolean): KtJsonObject {
    val block = when (part) {
        is ContentPart.Text -> buildJsonObject {
            put("type", "text")
            put("text", part.text)
        }

        is ContentPart.ImageUrl -> buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "url")
                put("url", part.url)
                part.mimeType?.let { put("media_type", it) }
            })
        }

        is ContentPart.ImageBase64 -> buildJsonObject {
            put("type", "image")
            put("source", buildJsonObject {
                put("type", "base64")
                put("media_type", part.mimeType)
                put("data", part.base64)
            })
        }
    }
    val shouldCache = cache || part.cache == CacheMarker.Ephemeral

    return if (!shouldCache) block else block.merge(buildJsonObject { put("cache_control", buildJsonObject { put("type", "ephemeral") }) },)
}

private fun claudeTool(tool: ToolSpec): KtJsonObject = buildJsonObject {
    put("name", tool.name)
    put("description", tool.description)
    put("input_schema", tool.inputSchema)
}

private fun claudeToolChoice(config: ToolConfig, syntheticOutput: ToolSpec?) = when {
    syntheticOutput != null -> buildJsonObject {
        put("type", "tool")
        put("name", syntheticOutput.name)
    }

    config.mode is ToolMode.Force -> buildJsonObject {
        put("type", "tool")
        put("name", config.mode.name)
        if (!config.allowParallel) put("disable_parallel_tool_use", true)
    }

    config.mode == ToolMode.Required -> buildJsonObject {
        put("type", "any")
        if (!config.allowParallel) put("disable_parallel_tool_use", true)
    }

    else -> buildJsonObject {
        put("type", "auto")
        if (!config.allowParallel) put("disable_parallel_tool_use", true)
    }
}

private fun claudeThinking(reasoning: ReasoningMode): KtJsonObject? = when (reasoning) {
    ReasoningMode.Default -> null

    ReasoningMode.Off -> buildJsonObject { put("type", "disabled") }

    is ReasoningMode.Effort -> buildJsonObject {
        put("type", "enabled")
        put("budget_tokens", reasoning.level.defaultClaudeThinkingBudget())
    }

    is ReasoningMode.BudgetTokens -> buildJsonObject {
        put("type", "enabled")
        put("budget_tokens", reasoning.tokens)
    }
}

private fun geminiParts(parts: List<ContentPart>): JsonArray = buildJsonArray {
    parts.forEach { part ->
        when (part) {
            is ContentPart.Text -> add(buildJsonObject { put("text", part.text) })

            is ContentPart.ImageUrl -> add(buildJsonObject {
                put("fileData", buildJsonObject {
                    part.mimeType?.let { put("mimeType", it) }
                    put("fileUri", part.url)
                })
            })

            is ContentPart.ImageBase64 -> add(buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", part.mimeType)
                    put("data", part.base64)
                })
            })
        }
    }
}

private fun geminiFunctionCallPart(toolCall: ToolCall) = buildJsonObject {
    put("functionCall", buildJsonObject {
        put("name", toolCall.name)
        put("args", toolCall.arguments)
        if (!toolCall.id.startsWith("gemini:")) put("id", toolCall.id)
    })

    toolCall.providerOptions.string("thoughtSignature")?.let { put("thoughtSignature", it) }
}

private fun KtJsonObject.geminiToolProviderOptions() = buildJsonObject {
    string("thoughtSignature")?.let { put("thoughtSignature", it) }
}

private fun geminiFunctionDeclaration(tool: ToolSpec) = buildJsonObject {
    put("name", tool.name)
    put("description", tool.description)
    put("parameters", geminiSchema(tool.inputSchema))
}

private fun geminiSchema(schema: KtJsonObject): KtJsonObject = buildJsonObject {
    schema.forEach { (key, value) ->
        when (key) {
            "\$schema", "\$id", "additionalProperties", "patternProperties", "unevaluatedProperties" -> Unit

            "properties" -> value.jsonObjectOrNull()?.let { properties -> put("properties", buildJsonObject {
                properties.forEach { (name, property) -> put(name, property.jsonObjectOrNull()?.let(::geminiSchema) ?: property) }
            }) }

            "items" -> put("items", value.jsonObjectOrNull()?.let(::geminiSchema) ?: value)

            else -> put(key, value)
        }
    }
}

private fun geminiToolConfig(config: ToolConfig) = buildJsonObject {
    put("functionCallingConfig", buildJsonObject {
        when (val mode = config.mode) {
            ToolMode.Auto -> put("mode", "AUTO")
            ToolMode.None -> put("mode", "NONE")
            ToolMode.Required -> put("mode", "ANY")
            is ToolMode.Force -> {
                put("mode", "ANY")
                put("allowedFunctionNames", buildJsonArray { add(JsonPrimitive(mode.name)) })
            }
        }
    })
}

private fun geminiGenerationConfig(request: LlmRequest) = buildJsonObject {
    request.temperature?.let { put("temperature", it) }
    request.maxOutputTokens?.let { put("maxOutputTokens", it) }

    when (val output = request.output) {
        OutputMode.Text -> Unit

        OutputMode.JsonObject -> put("responseMimeType", "application/json")

        is OutputMode.JsonSchema -> {
            put("responseMimeType", "application/json")
            put("responseSchema", geminiSchema(output.schema))
        }
    }

    geminiThinkingConfig(request.reasoning)?.let { put("thinkingConfig", it) }
}

private fun geminiThinkingConfig(reasoning: ReasoningMode) = when (reasoning) {
    ReasoningMode.Default -> null
    ReasoningMode.Off -> buildJsonObject { put("thinkingBudget", 0) }
    is ReasoningMode.Effort -> buildJsonObject {
        put("thinkingBudget", reasoning.level.defaultGeminiThinkingBudget())
        put("includeThoughts", true)
    }

    is ReasoningMode.BudgetTokens -> buildJsonObject {
        put("thinkingBudget", reasoning.tokens)
        if (reasoning.tokens != 0) put("includeThoughts", true)
    }
}

private fun parseOpenAiChatResponse(raw: KtJsonObject, fallbackModel: String): LlmResponse {
    val choice = raw["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
    val message = choice?.get("message")?.jsonObjectOrNull()
    val content = message?.string("content").orEmpty().toContentParts()

    val toolCalls = message?.get("tool_calls")?.jsonArrayOrNull().orEmpty().mapIndexedNotNull { index, item ->
        val tool = item.jsonObjectOrNull() ?: return@mapIndexedNotNull null
        val function = tool["function"]?.jsonObjectOrNull() ?: return@mapIndexedNotNull null
        ToolCall(
            id = tool.string("id") ?: "call_$index",
            name = function.string("name") ?: "function",
            arguments = function.string("arguments").orEmpty().toJsonObjectLenient(),
        )
    }

    return LlmResponse(
        id = raw.string("id"),
        model = raw.string("model") ?: fallbackModel,
        content = content,
        toolCalls = toolCalls,
        usage = parseOpenAiUsage(raw["usage"]?.jsonObjectOrNull()),
        finishReason = parseOpenAiFinishReason(choice?.string("finish_reason")),
        raw = raw,
    )
}

private fun parseOpenAiResponsesResponse(raw: KtJsonObject, fallbackModel: String): LlmResponse {
    val content = mutableListOf<ContentPart>()
    raw.string("output_text")?.takeIf { it.isNotBlank() }?.let { content.add(ContentPart.Text(it)) }
    val toolCalls = mutableListOf<ToolCall>()

    raw["output"]?.jsonArrayOrNull().orEmpty().forEachIndexed { index, itemElement ->
        val item = itemElement.jsonObjectOrNull() ?: return@forEachIndexed
        when (item.string("type")) {
            "message" -> item["content"]?.jsonArrayOrNull().orEmpty().forEach { contentElement ->
                val contentObject = contentElement.jsonObjectOrNull() ?: return@forEach
                val text = contentObject.string("text")
                    ?: contentObject.string("output_text")
                    ?: contentObject.string("refusal")
                if (!text.isNullOrEmpty()) content.add(ContentPart.Text(text))
            }

            "function_call" -> toolCalls.add(
                ToolCall(
                    id = item.string("call_id") ?: item.string("id") ?: "call_$index",
                    name = item.string("name") ?: "function",
                    arguments = item.string("arguments").orEmpty().toJsonObjectLenient(),
                ),
            )
        }
    }
    return LlmResponse(
        id = raw.string("id"),
        model = raw.string("model") ?: fallbackModel,
        content = content.ifEmpty { emptyList() },
        toolCalls = toolCalls,
        usage = parseOpenAiResponsesUsage(raw["usage"]?.jsonObjectOrNull()),
        finishReason = parseOpenAiResponsesFinishReason(raw.string("status"), raw["incomplete_details"]?.jsonObjectOrNull()?.string("reason")),
        raw = raw,
    )
}

private fun parseClaudeResponse(raw: KtJsonObject, fallbackModel: String, syntheticToolName: String?): LlmResponse {
    val content = mutableListOf<ContentPart>()
    val toolCalls = mutableListOf<ToolCall>()

    raw["content"]?.jsonArrayOrNull().orEmpty().forEachIndexed { index, itemElement ->
        val item = itemElement.jsonObjectOrNull() ?: return@forEachIndexed
        when (item.string("type")) {
            "text" -> item.string("text")?.let { content.add(ContentPart.Text(it)) }

            "tool_use" -> {
                val name = item.string("name") ?: "function"
                val input = item["input"]?.jsonObjectOrNull() ?: emptyJsonObject()

                if (name == syntheticToolName) content.add(ContentPart.Text(input.toString())) else toolCalls.add(ToolCall(item.string("id") ?: "tool_$index", name, input))
            }
        }
    }

    return LlmResponse(raw.string("id"), raw.string("model") ?: fallbackModel, content, toolCalls, parseClaudeUsage(raw["usage"]?.jsonObjectOrNull()), parseClaudeFinishReason(raw.string("stop_reason")), raw)
}

private fun parseGeminiResponse(raw: KtJsonObject, fallbackModel: String): LlmResponse {
    val candidate = raw["candidates"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
    val content = mutableListOf<ContentPart>()
    val toolCalls = mutableListOf<ToolCall>()

    candidate?.get("content")?.jsonObjectOrNull()?.get("parts")?.jsonArrayOrNull().orEmpty().forEachIndexed { index, partElement ->
        val part = partElement.jsonObjectOrNull() ?: return@forEachIndexed
        if (part.boolean("thought") != true) part.string("text")?.let { content.add(ContentPart.Text(it)) }
        part["functionCall"]?.jsonObjectOrNull()?.let { functionCall ->
            val name = functionCall.string("name") ?: "function"

            toolCalls.add(ToolCall(functionCall.string("id") ?: "gemini:$name:$index", name, functionCall["args"]?.jsonObjectOrNull() ?: emptyJsonObject(), part.geminiToolProviderOptions()))
        }
    }

    return LlmResponse(null, fallbackModel, content, toolCalls, parseGeminiUsage(raw["usageMetadata"]?.jsonObjectOrNull()), parseGeminiFinishReason(candidate?.string("finishReason")), raw)
}

private fun parseOpenAiUsage(usage: KtJsonObject?): TokenUsage? {
    if (usage == null) return null

    val promptDetails = usage["prompt_tokens_details"]?.jsonObjectOrNull()

    val completionDetails = usage["completion_tokens_details"]?.jsonObjectOrNull()

    return TokenUsage(
        inputTokens = usage.int("prompt_tokens"),
        outputTokens = usage.int("completion_tokens"),
        totalTokens = usage.int("total_tokens"),
        cachedInputTokens = promptDetails?.int("cached_tokens"),
        reasoningTokens = completionDetails?.int("reasoning_tokens"),
    )
}

private fun parseOpenAiResponsesUsage(usage: KtJsonObject?): TokenUsage? {
    if (usage == null) return null

    val inputDetails = usage["input_tokens_details"]?.jsonObjectOrNull()

    val outputDetails = usage["output_tokens_details"]?.jsonObjectOrNull()

    return TokenUsage(
        inputTokens = usage.int("input_tokens"),
        outputTokens = usage.int("output_tokens"),
        totalTokens = usage.int("total_tokens"),
        cachedInputTokens = inputDetails?.int("cached_tokens"),
        reasoningTokens = outputDetails?.int("reasoning_tokens"),
    )
}

private fun parseClaudeUsage(usage: KtJsonObject?): TokenUsage? {
    if (usage == null) return null

    val input = usage.int("input_tokens")

    val output = usage.int("output_tokens")

    return TokenUsage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = listOfNotNull(input, output).takeIf { it.isNotEmpty() }?.sum(),
        cachedInputTokens = usage.int("cache_read_input_tokens"),
    )
}

private fun parseGeminiUsage(usage: KtJsonObject?): TokenUsage? {
    if (usage == null) return null

    return TokenUsage(
        inputTokens = usage.int("promptTokenCount"),
        outputTokens = usage.int("candidatesTokenCount"),
        totalTokens = usage.int("totalTokenCount"),
        cachedInputTokens = usage.int("cachedContentTokenCount"),
        reasoningTokens = usage.int("thoughtsTokenCount"),
    )
}

private fun parseOpenAiFinishReason(value: String?) = when (value) {
    null -> FinishReason.Unknown

    "stop" -> FinishReason.Stop

    "length" -> FinishReason.Length

    "tool_calls", "function_call" -> FinishReason.ToolCalls

    "content_filter" -> FinishReason.ContentFilter

    else -> FinishReason.Unknown
}

private fun parseOpenAiResponsesFinishReason(status: String?, incompleteReason: String?) = when {
    status == "completed" -> FinishReason.Stop

    incompleteReason == "max_output_tokens" -> FinishReason.Length

    status == "failed" -> FinishReason.Error

    else -> FinishReason.Unknown
}

private fun parseClaudeFinishReason(value: String?) = when (value) {
    "end_turn", "stop_sequence" -> FinishReason.Stop

    "max_tokens" -> FinishReason.Length

    "tool_use" -> FinishReason.ToolCalls

    "refusal" -> FinishReason.ContentFilter

    else -> FinishReason.Unknown
}

private fun parseGeminiFinishReason(value: String?) = when (value) {
    "STOP" -> FinishReason.Stop

    "MAX_TOKENS" -> FinishReason.Length

    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> FinishReason.ContentFilter

    else -> FinishReason.Unknown
}

private fun ContentPart.asPlainText() = when (this) {
    is ContentPart.Text -> text

    is ContentPart.ImageUrl -> "[image:$url]"

    is ContentPart.ImageBase64 -> "[image:$mimeType;base64]"
}

private fun List<ContentPart>.plainText() = joinToString(separator = "\n") { it.asPlainText() }

private fun String.toContentParts() = if (isEmpty()) emptyList() else listOf(ContentPart.Text(this))

private fun LlmRequest.requiredFeatures(stream: Boolean) = buildSet {
    add(LlmFeature.Text)

    if (stream) add(LlmFeature.Streaming)

    if (system.hasImageParts() || messages.any { it.hasImageParts() }) add(LlmFeature.ImageInput)

    if (tools.isNotEmpty() || messages.any { it.hasToolProtocol() }) add(LlmFeature.FunctionCalling)

    if (output !is OutputMode.Text) add(LlmFeature.JsonOutput)
}

private fun List<ContentPart>.hasImageParts() = any { it is ContentPart.ImageUrl || it is ContentPart.ImageBase64 }

private fun LlmMessage.hasImageParts() = when (this) {
    is LlmMessage.User -> content.hasImageParts()

    is LlmMessage.Model -> content.hasImageParts()

    is LlmMessage.ToolResult -> content.hasImageParts()
}

private fun LlmMessage.hasToolProtocol() = when (this) {
    is LlmMessage.User -> false

    is LlmMessage.Model -> toolCalls.isNotEmpty()

    is LlmMessage.ToolResult -> true
}

private fun OutputMode.jsonInstruction() = when (this) {
    OutputMode.Text -> ""

    OutputMode.JsonObject -> "Return only a valid JSON object. Do not include markdown fences or commentary."

    is OutputMode.JsonSchema -> "Return only valid JSON matching this schema. Do not include markdown fences or commentary.\n${schema}"
}

private fun CachePolicy.shouldCache(messageIndex: Int, partIndex: Int) = when (this) {
    CachePolicy.Auto, CachePolicy.Off, is CachePolicy.CachedContent -> false
    is CachePolicy.Breakpoints -> breakpoints.any { it.messageIndex == messageIndex && (it.partIndex == null || it.partIndex == partIndex) }
}

private fun ReasoningEffort.apiValue() = when (this) {
    ReasoningEffort.Low -> "low"

    ReasoningEffort.Medium -> "medium"

    ReasoningEffort.High -> "high"

    ReasoningEffort.Max -> "high"
}

private fun ReasoningEffort.defaultClaudeThinkingBudget() = when (this) {
    ReasoningEffort.Low -> 1024

    ReasoningEffort.Medium -> 4096

    ReasoningEffort.High -> 8192

    ReasoningEffort.Max -> 16000
}

private fun ReasoningEffort.defaultGeminiThinkingBudget() = when (this) {
    ReasoningEffort.Low -> 1024

    ReasoningEffort.Medium -> 4096

    ReasoningEffort.High -> 8192

    ReasoningEffort.Max -> 16000
}

private fun Map<String, String>.toJsonObject() = buildJsonObject {
    forEach { (key, value) -> put(key, value) }
}

private fun KtJsonObject.merge(other: KtJsonObject) = buildJsonObject {
    this@merge.forEach { (key, value) -> put(key, value) }
    other.forEach { (key, value) -> put(key, value) }
}

private fun KtJsonObject.string(name: String) = this[name]?.jsonPrimitiveOrNull()?.contentOrNull

private fun KtJsonObject.int(name: String) = this[name]?.jsonPrimitiveOrNull()?.intOrNull

private fun KtJsonObject.boolean(name: String) = this[name]?.jsonPrimitiveOrNull()?.booleanOrNull

private fun JsonElement.jsonObjectOrNull() = this as? KtJsonObject

private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement.jsonPrimitiveOrNull() = runCatching { jsonPrimitive }.getOrNull()

private fun emptyJsonObject() = KtJsonObject(emptyMap())

private fun String.toJsonObjectLenient(): KtJsonObject {
    val parsed = runCatching { Json.parseToJsonElement(this) }.getOrNull()

    return parsed?.jsonObjectOrNull() ?: buildJsonObject { put("_raw", this@toJsonObjectLenient) }
}

private fun appendQuery(url: String, key: String, value: String): String {
    val separator = if (url.contains("?")) "&" else "?"
    return "$url$separator$key=$value"
}
