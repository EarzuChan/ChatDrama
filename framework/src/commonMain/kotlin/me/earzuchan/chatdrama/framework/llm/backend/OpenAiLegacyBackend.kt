package me.earzuchan.chatdrama.framework.llm.backend

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

data class OpenAiLegacyBackendConfig(val apiKey: String, val baseUrl: String = "https://api.openai.com/v1", val organization: String? = null, val project: String? = null, val includeStreamUsage: Boolean = true, val sendReasoningEffort: Boolean = true, val extraHeaders: Map<String, String> = emptyMap()) // CHECK：SendReasoningEffort，你干嘛？！

private data class OpenAiLegacyRoot(val systemMessage: JsonObject? = null, val tools: JsonArray? = null)

private data class OpenAiLegacyLaneB(override val rootRevisionId: LlmNodeId, override val anchorNodeId: LlmNodeId?, override val configKey: ProviderLaneBConfigKey, val root: OpenAiLegacyRoot, val messages: List<JsonObject>, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : ProviderLaneB {
    override val shape = ProviderShape.OpenAiLegacy
}

class OpenAiLegacyBackend(private val config: OpenAiLegacyBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.OpenAiLegacy
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput), setOf(LlmFeature.PromptCaching, LlmFeature.Reasoning))

    override fun rebuildLaneB(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB = OpenAiLegacyLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), openAiLegacyRoot(rootRevision.root), openAiLegacyMessages(nodes), sessionBlackboard.onlyPrefixed("openai."))

    override fun debugLaneB(laneB: ProviderLaneB?) = laneB?.let { debugLaneB(it as? OpenAiLegacyLaneB ?: return@let providerLaneBDebug("OpenAiLegacyLaneB(wrong type)", it)) } ?: "OpenAiLegacyLaneB(null)"

    override suspend fun request(turn: ProviderTurn<ProviderLaneB>, mode: RequestMode): ProviderTurnCommit<ProviderLaneB> = internalRealRequest(turn.typedTurn("OpenAI legacy") { it as? OpenAiLegacyLaneB }, mode)

    private suspend fun internalRealRequest(turn: ProviderTurn<OpenAiLegacyLaneB>, mode: RequestMode) = when (mode) {
        RequestMode.Static -> commit(turn, parseOpenAiChatResponse(postJson(chatCompletionsUrl(), headers(), chatCompletionsBody(turn, stream = false)), turn))
        is RequestMode.Streamed -> commit(turn, stream(turn, mode.observer))
    }

    private suspend fun stream(turn: ProviderTurn<OpenAiLegacyLaneB>, observer: TurnObserver?): TurnResult {
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

    private fun debugLaneB(lane: OpenAiLegacyLaneB) = buildString {
        appendLine(providerLaneBDebug("OpenAiLegacyLaneB", lane))
        appendLine("  root.systemMessage=${lane.root.systemMessage}")
        appendLine("  root.tools=${lane.root.tools}")
        appendLine("  messages:")
        lane.messages.forEachIndexed { index, message -> appendLine("    [$index] $message") }
    }

    private fun chatCompletionsBody(turn: ProviderTurn<OpenAiLegacyLaneB>, stream: Boolean): JsonObject {
        val lane = turn.laneB
        val body = buildJsonObject {
            put("model", turn.config.model)
            put("messages", openAiLegacyMessages(lane, turn.requestNode.request))
            turn.config.temperature?.let { put("temperature", it) }
            if (stream) {
                put("stream", true)
                if (config.includeStreamUsage) put("stream_options", buildJsonObject { put("include_usage", true) })
            }

            lane.root.tools?.takeIf { it.isNotEmpty() }?.let {
                put("tools", it)
                put("parallel_tool_calls", true)
            }

            openAiLegacyResponseFormat(turn.config.output)?.let { put("response_format", it) }
            deepSeekCompatibleThinking(turn.config.reasoning)?.let { put("thinking", it) }
            if (config.sendReasoningEffort) openAiReasoningEffort(turn.config.reasoning)?.let { put("reasoning_effort", it) }
        }
        return body.merge(turn.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun commit(turn: ProviderTurn<OpenAiLegacyLaneB>, result: TurnResult): ProviderTurnCommit<OpenAiLegacyLaneB> {
        val lane = turn.laneB
        val next = lane.copy(anchorNodeId = turn.resultNodeId, messages = lane.messages + openAiLegacyRequestMessages(turn.requestNode.request) + openAiLegacyAssistantMessage(result), blackboard = lane.blackboard.withAll(result.blackboard.onlyPrefixed("openai."))).tidyAfterAppend()
        return ProviderTurnCommit(next, result)
    }
}

private fun openAiLegacyRoot(root: SessionRoot) = OpenAiLegacyRoot(
    systemMessage = root.instructions.takeIf { it.isNotEmpty() }?.let { buildJsonObject {
        put("role", "system")
        put("content", it.plainText())
    } },
    tools = root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonArray { it.forEach { tool -> add(openAiLegacyTool(tool)) } } }
)

private fun openAiLegacyMessages(lane: OpenAiLegacyLaneB, request: TurnRequest) = buildJsonArray {
    lane.root.systemMessage?.let { add(it) }
    lane.messages.forEach { add(it) }
    openAiLegacyRequestMessages(request).forEach { add(it) }
}

// TIPS：进行转译+转译中有顺手处理。OAI Legacy 需处理的较少
private fun openAiLegacyMessages(nodes: List<SessionNode>) = buildList {
    nodes.forEach { node ->
        when (node) {
            is TurnRequestNode -> addAll(openAiLegacyRequestMessages(node.request))
            is TurnResultNode -> add(openAiLegacyAssistantMessage(node.result))
        }
    }
}

private fun openAiLegacyRequestMessages(request: TurnRequest) = buildList {
    request.items.forEach { item ->
        when (item) {
            is TurnInputItem.Content -> add(buildJsonObject {
                put("role", "user")
                put("content", openAiLegacyInputContent(item.parts))
            })

            is TurnInputItem.ToolResult -> add(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", item.toolCallId)
                item.name?.let { put("name", it) }
                put("content", item.parts.plainText())
            })
        }
    }
}

private fun openAiLegacyAssistantMessage(result: TurnResult) = buildJsonObject {
    put("role", "assistant")
    val reasoning = result.items.filterIsInstance<TurnItem.Reasoning>().mapNotNull { it.text }.joinToString("\n").takeIf { it.isNotBlank() }
    val text = result.items.mapNotNull {
        when (it) {
            is TurnItem.Content -> (it.body as? OutputBody.Text)?.text ?: (it.body as? OutputBody.Json)?.rawText ?: it.body.toString()
            is TurnItem.Refusal -> it.text
            is TurnItem.Reasoning, is TurnItem.ToolCall -> null
        }
    }.joinToString("\n").ifBlank { null }
    val toolCalls = result.items.filterIsInstance<TurnItem.ToolCall>()
    if (text != null) put("content", text) else put("content", JsonNull)

    // Legacy/DeepSeek 对外家思考也最宽松，直接按 reasoning_content 回填就完事儿，保证工具波不断线
    reasoning?.let { put("reasoning_content", it) }
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

private fun OpenAiLegacyLaneB.tidyAfterAppend(): OpenAiLegacyLaneB {
    return copy(messages = messages.tidyLatestOpenAiLegacyWave())
}

private data class OpenAiLegacyWaveRange(val start: Int, val endExclusive: Int)

private fun List<JsonObject>.tidyLatestOpenAiLegacyWave(): List<JsonObject> {
    val range = latestOpenAiLegacyWaveRange() ?: return this
    val tidied = subList(range.start, range.endExclusive).tidiedOpenAiLegacyWave()
    if (tidied == subList(range.start, range.endExclusive)) return this
    return take(range.start) + tidied + drop(range.endExclusive)
}

private fun List<JsonObject>.latestOpenAiLegacyWaveRange(): OpenAiLegacyWaveRange? {
    if (lastOrNull()?.isOpenAiLegacyFinalAssistant() != true) return null
    var start = indexOfLast { it.string("role") == "user" }.takeIf { it >= 0 } ?: return null
    while (start > 0 && this[start - 1].string("role") == "user") start--
    return OpenAiLegacyWaveRange(start, size)
}

private fun List<JsonObject>.tidiedOpenAiLegacyWave(): List<JsonObject> {
    val promptCount = takeWhile { it.string("role") == "user" }.size
    if (promptCount == 0) return this
    val final = lastOrNull { it.isOpenAiLegacyFinalAssistant() } ?: return this
    return take(promptCount) + final.openAiLegacyFinalOnly()
}

private fun JsonObject.isOpenAiLegacyFinalAssistant() = string("role") == "assistant" && !containsKey("tool_calls") && hasOpenAiLegacyVisibleContent()

private fun JsonObject.hasOpenAiLegacyVisibleContent() = string("content")?.isNotBlank() == true

private fun JsonObject.openAiLegacyFinalOnly() = buildJsonObject {
    put("role", "assistant")
    string("content")?.let { put("content", it) }
}

private fun openAiLegacyInputContent(parts: List<ContentPart>): JsonElement {
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

private fun openAiLegacyTool(tool: ToolDefinition) = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", tool.name)
        tool.description?.let { put("description", it) }
        put("parameters", tool.inputSchema(tool.strict))
        put("strict", tool.strict)
    })
}

private fun openAiLegacyResponseFormat(output: OutputContract): JsonObject? = when (output) {
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

private fun deepSeekCompatibleThinking(reasoning: ReasoningLevel) = if (reasoning == ReasoningLevel.Off) null else buildJsonObject { put("type", "enabled") }

private suspend fun parseOpenAiChatResponse(raw: JsonObject, turn: ProviderTurn<*>): TurnResult {
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

private fun parseOpenAiUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    val promptDetails = usage["prompt_tokens_details"]?.jsonObjectOrNull()
    val completionDetails = usage["completion_tokens_details"]?.jsonObjectOrNull()
    return TokenUsage(usage.int("prompt_tokens"), usage.int("completion_tokens"), usage.int("total_tokens"), promptDetails?.int("cached_tokens"), completionDetails?.int("reasoning_tokens"))
}
