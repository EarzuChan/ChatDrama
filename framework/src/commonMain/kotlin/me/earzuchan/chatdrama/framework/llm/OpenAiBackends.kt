package me.earzuchan.chatdrama.framework.llm

import io.ktor.http.*
import kotlinx.serialization.json.*

data class OpenAiLegacyBackendConfig(val apiKey: String, val defaultModel: String, val baseUrl: String = "https://api.openai.com/v1", val organization: String? = null, val project: String? = null, val includeStreamUsage: Boolean = true, val sendReasoningEffort: Boolean = true, val extraHeaders: Map<String, String> = emptyMap())

data class OpenAiResponsesBackendConfig(val apiKey: String, val defaultModel: String, val baseUrl: String = "https://api.openai.com/v1", val organization: String? = null, val project: String? = null, val extraHeaders: Map<String, String> = emptyMap())

private fun openAiHeaders(apiKey: String, organization: String?, project: String?, extraHeaders: Map<String, String>) = buildMap {
    put(HttpHeaders.Authorization, "Bearer $apiKey")
    organization?.let { put("OpenAI-Organization", it) }
    project?.let { put("OpenAI-Project", it) }
    putAll(extraHeaders)
}

class OpenAiLegacyBackend(private val config: OpenAiLegacyBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.OpenAiLegacy
    override val defaultConfig = LlmCallConfig(model = config.defaultModel, cache = CachePreference.Prefer, remoteState = RemoteStatePreference.Off)
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput), setOf(LlmFeature.PromptCaching, LlmFeature.Reasoning))

    override suspend fun request(turn: ProviderTurn, mode: RequestMode) = when (mode) {
        RequestMode.Static -> parseOpenAiChatResponse(postJson(chatCompletionsUrl(), headers(), chatCompletionsBody(turn, stream = false)), turn)
        is RequestMode.Streamed -> stream(turn, mode.observer)
    }

    private suspend fun stream(turn: ProviderTurn, observer: TurnObserver?): TurnResult {
        val draft = TurnDraft(turn.config.output, observer)
        var usage: TokenUsage? = null
        var finishReason: String? = null
        val toolIndices = mutableMapOf<Int, Pair<String, String>>()

        try {
            postSse(chatCompletionsUrl(), headers(), chatCompletionsBody(turn, stream = true)) { _, data ->
                if (data == "[DONE]") return@postSse
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse
                parseOpenAiUsage(raw["usage"]?.jsonObjectOrNull())?.let { usage = it }
                val choice = raw["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull() ?: return@postSse
                choice.string("finish_reason")?.let { finishReason = it }
                val delta = choice["delta"]?.jsonObjectOrNull() ?: return@postSse

                delta.string("reasoning_content")?.let { draft.appendReasoning(it, ReasoningKind.Raw) }
                delta.string("reasoning")?.let { draft.appendReasoning(it, ReasoningKind.Raw) }
                delta.string("content")?.let { draft.appendContent(it) }
                delta.string("refusal")?.let { draft.appendRefusal(it) }

                delta["tool_calls"]?.jsonArrayOrNull().orEmpty().forEach { itemElement ->
                    val item = itemElement.jsonObjectOrNull() ?: return@forEach
                    val index = item.int("index") ?: 0
                    val function = item["function"]?.jsonObjectOrNull()
                    val existing = toolIndices[index]
                    val id = item.string("id") ?: existing?.first ?: "call_$index"
                    val name = function?.string("name") ?: existing?.second ?: "function"
                    if (existing == null) draft.startTool(id, name)
                    toolIndices[index] = id to name
                    function?.string("arguments")?.let { draft.appendToolArguments(id, name, it) }
                }
            }
            toolIndices.values.forEach { (id, _) -> draft.completeTool(id) }
            return draft.complete(usage, TurnTrace(shape, turn.config.model, finishReason))
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: "OpenAI legacy stream failed", throwable, draft.partial(usage, TurnTrace(shape, turn.config.model, finishReason)))
        }
    }

    private fun chatCompletionsUrl() = "${config.baseUrl.trimEnd('/')}/chat/completions"

    private fun headers() = openAiHeaders(config.apiKey, config.organization, config.project, config.extraHeaders)

    private fun chatCompletionsBody(turn: ProviderTurn, stream: Boolean): JsonObject {
        val body = buildJsonObject {
            put("model", turn.config.model)
            put("messages", openAiChatMessages(turn))
            turn.config.temperature?.let { put("temperature", it) }
            if (stream) {
                put("stream", true)
                if (config.includeStreamUsage) put("stream_options", buildJsonObject { put("include_usage", true) })
            }

            if (turn.rootRevision.root.tools.isNotEmpty()) {
                put("tools", buildJsonArray { turn.rootRevision.root.tools.forEach { add(openAiChatTool(it)) } })
                put("parallel_tool_calls", true)
            }

            openAiChatResponseFormat(turn.config.output)?.let { put("response_format", it) }
            deepSeekCompatibleThinking(turn.config.reasoning)?.let { put("thinking", it) }
            if (config.sendReasoningEffort) openAiReasoningEffort(turn.config.reasoning)?.let { put("reasoning_effort", it) }
        }
        return body.merge(turn.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun openAiChatMessages(turn: ProviderTurn) = buildJsonArray {
        if (turn.rootRevision.root.instructions.isNotEmpty()) add(buildJsonObject {
            put("role", "system")
            put("content", turn.rootRevision.root.instructions.plainText())
        })

        turn.nodes.forEach { node ->
            when (node) {
                is TurnRequestNode -> node.request.items.forEach { item ->
                    when (item) {
                        is TurnInputItem.Content -> add(buildJsonObject {
                            put("role", "user")
                            put("content", openAiChatInputContent(item.parts))
                        })

                        is TurnInputItem.ToolResult -> add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", item.toolCallId)
                            item.name?.let { put("name", it) }
                            put("content", item.parts.plainText())
                        })
                    }
                }

                is TurnResultNode -> add(openAiChatAssistantMessage(node.result))
            }
        }
    }

    private fun openAiChatAssistantMessage(result: TurnResult) = buildJsonObject {
        put("role", "assistant")
        val text = result.items.mapNotNull {
            when (it) {
                is TurnItem.Content -> (it.body as? OutputBody.Text)?.text ?: (it.body as? OutputBody.Json)?.rawText ?: it.body.toString()
                is TurnItem.Refusal -> it.text
                is TurnItem.Reasoning, is TurnItem.ToolCall -> null
            }
        }.joinToString("\n").ifBlank { null }
        val toolCalls = result.items.filterIsInstance<TurnItem.ToolCall>()
        if (text != null) put("content", text) else put("content", JsonNull)
        if (toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
            toolCalls.forEach { toolCall ->
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
    }
}

class OpenAiResponsesBackend(private val config: OpenAiResponsesBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.OpenAiResponses
    override val defaultConfig = LlmCallConfig(model = config.defaultModel, cache = CachePreference.Prefer, remoteState = RemoteStatePreference.Off)
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput, LlmFeature.Reasoning), setOf(LlmFeature.PromptCaching, LlmFeature.RemoteState))

    override suspend fun request(turn: ProviderTurn, mode: RequestMode) = when (mode) {
        RequestMode.Static -> parseOpenAiResponsesResponse(postJson(responsesUrl(), headers(), responsesBody(turn, stream = false)), turn)
        is RequestMode.Streamed -> stream(turn, mode.observer)
    }

    private suspend fun stream(turn: ProviderTurn, observer: TurnObserver?): TurnResult {
        val draft = TurnDraft(turn.config.output, observer)
        var completedRaw: JsonObject? = null
        val tools = mutableMapOf<String, Pair<String, String>>()

        try {
            postSse(responsesUrl(), headers(), responsesBody(turn, stream = true)) { event, data ->
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse

                when (event) {
                    "response.output_text.delta" -> draft.appendContent(raw.string("delta").orEmpty())

                    "response.refusal.delta" -> draft.appendRefusal(raw.string("delta"))

                    "response.reasoning_text.delta", "response.reasoning.delta", "response.reasoning_summary_text.delta" -> draft.appendReasoning(raw.string("delta").orEmpty())

                    "response.output_item.added" -> {
                        val item = raw["item"]?.jsonObjectOrNull() ?: return@postSse
                        if (item.string("type") == "function_call") {
                            val id = item.string("call_id") ?: item.string("id") ?: raw.int("output_index")?.let { "call_$it" } ?: "call"
                            val name = item.string("name") ?: "function"
                            raw.openAiResponsesToolKeys(item).forEach { tools[it] = id to name }
                            draft.startTool(id, name)
                        }
                    }

                    "response.function_call_arguments.delta" -> {
                        val tool = raw.openAiResponsesToolKey()?.let(tools::get)
                        val id = raw.string("call_id") ?: tool?.first ?: raw.string("item_id") ?: raw.int("output_index")?.let { "call_$it" } ?: "call"
                        val name = raw.string("name") ?: tool?.second ?: "function"
                        draft.appendToolArguments(id, name, raw.string("delta").orEmpty())
                    }

                    "response.completed" -> completedRaw = raw["response"]?.jsonObjectOrNull()
                }
            }
            completedRaw?.let {
                val result = parseOpenAiResponsesResponse(it, turn)
                if (draft.isEmpty()) return draft.completeWith(result)
                return draft.complete(result.usage, result.trace, result.blackboard)
            }
            return draft.complete(trace = TurnTrace(shape, turn.config.model))
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: "OpenAI responses stream failed", throwable, draft.partial(trace = TurnTrace(shape, turn.config.model)))
        }
    }

    private fun responsesUrl() = "${config.baseUrl.trimEnd('/')}/responses"

    private fun headers() = openAiHeaders(config.apiKey, config.organization, config.project, config.extraHeaders)

    private fun responsesBody(turn: ProviderTurn, stream: Boolean): JsonObject {
        val body = buildJsonObject {
            put("model", turn.config.model)
            if (turn.rootRevision.root.instructions.isNotEmpty()) put("instructions", turn.rootRevision.root.instructions.plainText())
            put("input", openAiResponsesInput(turn))
            turn.config.temperature?.let { put("temperature", it) }
            if (stream) put("stream", true)

            if (turn.rootRevision.root.tools.isNotEmpty()) {
                put("tools", buildJsonArray { turn.rootRevision.root.tools.forEach { add(openAiResponsesTool(it)) } })
                put("parallel_tool_calls", true)
            }

            openAiResponsesTextFormat(turn.config.output)?.let { put("text", buildJsonObject { put("format", it) }) }
            openAiResponsesReasoning(turn.config.reasoning)?.let { put("reasoning", it) }
        }
        return body.merge(turn.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun openAiResponsesInput(turn: ProviderTurn) = buildJsonArray {
        turn.nodes.forEach { node ->
            when (node) {
                is TurnRequestNode -> node.request.items.forEach { item ->
                    when (item) {
                        is TurnInputItem.Content -> add(buildJsonObject {
                            put("role", "user")
                            put("content", openAiResponsesInputContent(item.parts))
                        })

                        is TurnInputItem.ToolResult -> add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", item.toolCallId)
                            put("output", item.parts.plainText())
                        })
                    }
                }

                is TurnResultNode -> node.result.items.forEach { item ->
                    when (item) {
                        is TurnItem.Content -> add(buildJsonObject {
                            put("role", "assistant")
                            put("content", item.asOutputText())
                        })

                        is TurnItem.ToolCall -> add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", item.id)
                            put("name", item.name)
                            put("arguments", item.arguments.toString())
                        })

                        is TurnItem.Reasoning, is TurnItem.Refusal -> Unit
                    }
                }
            }
        }
    }
}

private fun openAiChatInputContent(parts: List<ContentPart>): JsonElement {
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

private fun openAiChatTool(tool: ToolDefinition) = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", tool.name)
        tool.description?.let { put("description", it) }
        put("parameters", tool.inputSchema())
        put("strict", tool.strict)
    })
}

private fun openAiResponsesTool(tool: ToolDefinition) = buildJsonObject {
    put("type", "function")
    put("name", tool.name)
    tool.description?.let { put("description", it) }
    put("parameters", tool.inputSchema())
    put("strict", tool.strict)
}

private fun openAiChatResponseFormat(output: OutputContract): JsonObject? = when (output) {
    OutputContract.Text -> null
    OutputContract.Json -> buildJsonObject { put("type", "json_object") }
    is OutputContract.JsonSchema -> buildJsonObject {
        put("type", "json_schema")
        put("json_schema", buildJsonObject {
            put("name", output.name)
            put("schema", output.schema)
            put("strict", output.strict)
        })
    }
}

private fun openAiResponsesTextFormat(output: OutputContract): JsonObject? = when (output) {
    OutputContract.Text -> null
    OutputContract.Json -> buildJsonObject { put("type", "json_object") }
    is OutputContract.JsonSchema -> buildJsonObject {
        put("type", "json_schema")
        put("name", output.name)
        put("schema", output.schema)
        put("strict", output.strict)
    }
}

private fun openAiReasoningEffort(reasoning: ReasoningLevel) = when (reasoning) {
    ReasoningLevel.Off -> null
    ReasoningLevel.Minimal -> "minimal"
    ReasoningLevel.Low -> "low"
    ReasoningLevel.Medium -> "medium"
    ReasoningLevel.High -> "high"
    ReasoningLevel.Max -> "high"
}

private fun deepSeekCompatibleThinking(reasoning: ReasoningLevel) = if (reasoning == ReasoningLevel.Off) null else buildJsonObject { put("type", "enabled") }

private fun openAiResponsesReasoning(reasoning: ReasoningLevel): JsonObject? = when (reasoning) {
    ReasoningLevel.Off -> null
    else -> buildJsonObject {
        put("effort", if (reasoning == ReasoningLevel.Max) "xhigh" else openAiReasoningEffort(reasoning) ?: "medium")
        put("summary", "auto")
    }
}

private fun JsonObject.openAiResponsesToolKey() = string("call_id") ?: string("item_id") ?: int("output_index")?.toString()

private fun JsonObject.openAiResponsesToolKeys(item: JsonObject) = listOfNotNull(string("call_id"), string("item_id"), item.string("call_id"), item.string("id"), int("output_index")?.toString()).distinct()

private suspend fun parseOpenAiChatResponse(raw: JsonObject, turn: ProviderTurn): TurnResult {
    val draft = TurnDraft(turn.config.output)
    val choice = raw["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
    val message = choice?.get("message")?.jsonObjectOrNull()
    message?.string("reasoning_content")?.let { draft.appendReasoning(it, ReasoningKind.Raw) }
    message?.string("reasoning")?.let { draft.appendReasoning(it, ReasoningKind.Raw) }
    message?.string("content")?.let { draft.appendContent(it) }
    message?.string("refusal")?.let { draft.appendRefusal(it) }

    message?.get("tool_calls")?.jsonArrayOrNull().orEmpty().forEachIndexed { index, itemElement ->
        val item = itemElement.jsonObjectOrNull() ?: return@forEachIndexed
        val function = item["function"]?.jsonObjectOrNull() ?: return@forEachIndexed
        val id = item.string("id") ?: "call_$index"
        draft.startTool(id, function.string("name") ?: "function")
        draft.appendToolArguments(id, function.string("name") ?: "function", function.string("arguments").orEmpty())
        draft.completeTool(id)
    }

    return draft.partial(parseOpenAiUsage(raw["usage"]?.jsonObjectOrNull()), TurnTrace(ProviderShape.OpenAiLegacy, raw.string("model") ?: turn.config.model, choice?.string("finish_reason"), raw))
}

private suspend fun parseOpenAiResponsesResponse(raw: JsonObject, turn: ProviderTurn, observer: TurnObserver? = null): TurnResult {
    val draft = TurnDraft(turn.config.output, observer)
    raw["output"]?.jsonArrayOrNull().orEmpty().forEachIndexed { index, itemElement ->
        val item = itemElement.jsonObjectOrNull() ?: return@forEachIndexed
        when (item.string("type")) {
            "message" -> item["content"]?.jsonArrayOrNull().orEmpty().forEach { contentElement ->
                val content = contentElement.jsonObjectOrNull() ?: return@forEach
                when (content.string("type")) {
                    "output_text" -> draft.appendContent(content.string("text").orEmpty())
                    "refusal" -> draft.appendRefusal(content.string("refusal") ?: content.string("text"))
                    else -> content.string("text")?.let { draft.appendContent(it) }
                }
            }

            "function_call" -> {
                val id = item.string("call_id") ?: item.string("id") ?: "call_$index"
                val name = item.string("name") ?: "function"
                draft.startTool(id, name)
                draft.appendToolArguments(id, name, item.string("arguments").orEmpty())
                draft.completeTool(id)
            }

            "reasoning" -> {
                item["summary"]?.jsonArrayOrNull().orEmpty().forEach { summaryElement ->
                    val summary = summaryElement.jsonObjectOrNull()
                    draft.appendReasoning(summary?.string("text").orEmpty(), ReasoningKind.Summary)
                }
            }
        }
    }

    raw.string("output_text")?.takeIf { it.isNotBlank() && draft.partial().items.none { item -> item is TurnItem.Content } }?.let { draft.appendContent(it) }
    val blackboard = raw.string("id")?.let { Blackboard.Empty.with("openai.responses.response_id", it) } ?: Blackboard.Empty
    return draft.complete(parseOpenAiResponsesUsage(raw["usage"]?.jsonObjectOrNull()), TurnTrace(ProviderShape.OpenAiResponses, raw.string("model") ?: turn.config.model, raw.string("status"), raw, blackboard), blackboard)
}

private fun parseOpenAiUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    val promptDetails = usage["prompt_tokens_details"]?.jsonObjectOrNull()
    val completionDetails = usage["completion_tokens_details"]?.jsonObjectOrNull()
    return TokenUsage(usage.int("prompt_tokens"), usage.int("completion_tokens"), usage.int("total_tokens"), promptDetails?.int("cached_tokens"), completionDetails?.int("reasoning_tokens"))
}

private fun parseOpenAiResponsesUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    val inputDetails = usage["input_tokens_details"]?.jsonObjectOrNull()
    val outputDetails = usage["output_tokens_details"]?.jsonObjectOrNull()
    return TokenUsage(usage.int("input_tokens"), usage.int("output_tokens"), usage.int("total_tokens"), inputDetails?.int("cached_tokens"), outputDetails?.int("reasoning_tokens"))
}

private fun TurnItem.Content.asOutputText() = asText()
