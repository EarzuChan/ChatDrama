package me.earzuchan.chatdrama.framework.llm.backend

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

data class OpenAiLegacyBackendConfig(val apiKey: String, val baseUrl: String = "https://api.openai.com/v1", val organization: String? = null, val project: String? = null, val includeStreamUsage: Boolean = true, val sendReasoningEffort: Boolean = true, val extraHeaders: Map<String, String> = emptyMap()) // CHECK：SendReasoningEffort，你干嘛？！

private data class OpenAiLegacyRoot(val systemMessage: JsonObject? = null, val tools: JsonArray? = null)

private data class OpenAiLegacyLaneB(override val rootRevisionId: LlmNodeId, override val anchorNodeId: LlmNodeId?, override val configKey: ProviderLaneBConfigKey, val root: OpenAiLegacyRoot, val messages: List<JsonObject>, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : ProviderLaneB {
    override val shape = ProviderShape.OpenAiLegacy
}

private data class OpenAiLegacyReceived(val nativeMessage: JsonObject?, val result: TurnResult)

class OpenAiLegacyBackend(private val config: OpenAiLegacyBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.OpenAiLegacy
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput), setOf(LlmFeature.PromptCaching, LlmFeature.Reasoning))

    override fun rebuildLaneB(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB = OpenAiLegacyLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), openAiLegacyRoot(rootRevision.root), projectThinLaneAToOpenAiLegacyMessages(nodes), sessionBlackboard.onlyPrefixed("openai."))

    override fun debugLaneB(laneB: ProviderLaneB?) = laneB?.let { debugLaneB(it as? OpenAiLegacyLaneB ?: return@let providerLaneBDebug("OpenAiLegacyLaneB(wrong type)", it)) } ?: "OpenAiLegacyLaneB(null)"

    override suspend fun request(providerRequest: ProviderTurnRequest<ProviderLaneB>, mode: RequestMode): ProviderTurnResultCommit<ProviderLaneB> = performRequest(providerRequest.typedRequest("OpenAI legacy") { it as? OpenAiLegacyLaneB }, mode)

    private suspend fun performRequest(providerRequest: ProviderTurnRequest<OpenAiLegacyLaneB>, mode: RequestMode): ProviderTurnResultCommit<OpenAiLegacyLaneB> {
        val received = when (mode) {
            RequestMode.Static -> receiveStatic(providerRequest)
            is RequestMode.Streamed -> receiveStream(providerRequest, mode.observer)
        }

        return commitReceived(providerRequest, received)
    }

    private suspend fun receiveStatic(providerRequest: ProviderTurnRequest<OpenAiLegacyLaneB>) = parseOpenAiChatReceived(postJson(chatCompletionsUrl(), headers(), chatCompletionsBody(providerRequest, stream = false)), providerRequest)

    private suspend fun receiveStream(providerRequest: ProviderTurnRequest<OpenAiLegacyLaneB>, observer: TurnObserver?): OpenAiLegacyReceived {
        val draft = OpenAiLegacyAssistantMessageDraft(providerRequest.config.output, observer)
        var usage: TokenUsage? = null
        var finishReason: String? = null
        var model = providerRequest.config.model

        try {
            postSse(chatCompletionsUrl(), headers(), chatCompletionsBody(providerRequest, stream = true)) { _, data ->
                if (data == "[DONE]") return@postSse
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse
                model = raw.string("model") ?: model
                parseOpenAiUsage(raw["usage"]?.jsonObjectOrNull())?.let { usage = it }
                val choice = raw["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull() ?: return@postSse
                choice.string("finish_reason")?.let { finishReason = it }
                val delta = choice["delta"]?.jsonObjectOrNull() ?: return@postSse

                draft.delta(delta)
            }
            return draft.complete(usage, TurnTrace(shape, model, finishReason))
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: "OpenAI legacy stream failed", throwable, draft.partial(usage, TurnTrace(shape, model, finishReason)))
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

    private fun chatCompletionsBody(providerRequest: ProviderTurnRequest<OpenAiLegacyLaneB>, stream: Boolean): JsonObject {
        val lane = providerRequest.laneB
        val body = buildJsonObject {
            put("model", providerRequest.config.model)
            put("messages", openAiLegacyMessagesForSending(lane, providerRequest.requestNode.request))
            providerRequest.config.temperature?.let { put("temperature", it) }

            if (stream) {
                put("stream", true)
                if (config.includeStreamUsage) put("stream_options", buildJsonObject { put("include_usage", true) })
            }

            lane.root.tools?.takeIf { it.isNotEmpty() }?.let {
                put("tools", it)
                put("parallel_tool_calls", true)
            }

            openAiLegacyResponseFormat(providerRequest.config.output)?.let { put("response_format", it) }
            deepSeekCompatibleThinking(providerRequest.config.reasoning)?.let { put("thinking", it) }
            if (config.sendReasoningEffort) openAiReasoningEffort(providerRequest.config.reasoning)?.let { put("reasoning_effort", it) }
        }
        return body.merge(providerRequest.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun commitReceived(providerRequest: ProviderTurnRequest<OpenAiLegacyLaneB>, received: OpenAiLegacyReceived): ProviderTurnResultCommit<OpenAiLegacyLaneB> {
        val lane = providerRequest.laneB
        val messages = buildList {
            addAll(lane.messages)
            addAll(projectTurnRequestToOpenAiLegacyMessages(providerRequest.requestNode.request))
            received.nativeMessage?.let { add(it) }
        }
        val next = lane.copy(anchorNodeId = providerRequest.resultNodeId, messages = messages, blackboard = lane.blackboard.withAll(received.result.blackboard.onlyPrefixed("openai."))).tidyAfterAppend()
        return ProviderTurnResultCommit(next, received.result)
    }
}

private fun openAiLegacyRoot(root: SessionRoot) = OpenAiLegacyRoot(
    systemMessage = root.instructions.takeIf { it.isNotEmpty() }?.let { buildJsonObject {
        put("role", "system")
        put("content", it.plainText())
    } },
    tools = root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonArray { it.forEach { tool -> add(openAiLegacyTool(tool)) } } }
)

private fun openAiLegacyMessagesForSending(lane: OpenAiLegacyLaneB, request: TurnRequest) = buildJsonArray {
    lane.root.systemMessage?.let { add(it) }
    lane.messages.forEach { add(it) }
    projectTurnRequestToOpenAiLegacyMessages(request).forEach { add(it) }
}

// 重建路径只消费 Session 已经 thin 过的 LaneA。Legacy/DeepSeek 比较宽松，外家思考直接走 reasoning_content
private fun projectThinLaneAToOpenAiLegacyMessages(nodes: List<SessionNode>) = buildList {
    nodes.forEach { node ->
        when (node) {
            is TurnRequestNode -> addAll(projectTurnRequestToOpenAiLegacyMessages(node.request))
            is TurnResultNode -> add(projectTurnResultToOpenAiLegacyMessage(node.result))
        }
    }
}

private fun projectTurnRequestToOpenAiLegacyMessages(request: TurnRequest) = buildList {
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

private fun projectTurnResultToOpenAiLegacyMessage(result: TurnResult) = buildJsonObject {
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

    // Legacy/DeepSeek 对外家思考也最宽松，直接按 reasoning_content 回填就完事，保证工具波不断线
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

private fun OpenAiLegacyLaneB.tidyAfterAppend(): OpenAiLegacyLaneB = copy(messages = messages.tidyLatestOpenAiLegacyWave())

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

private fun parseOpenAiChatReceived(raw: JsonObject, providerRequest: ProviderTurnRequest<*>): OpenAiLegacyReceived {
    val choice = raw["choices"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
    val message = choice?.get("message")?.jsonObjectOrNull()
    val native = message?.normalizeOpenAiLegacyAssistantMessage()
    val trace = TurnTrace(ProviderShape.OpenAiLegacy, raw.string("model") ?: providerRequest.config.model, choice?.string("finish_reason"), raw)
    return OpenAiLegacyReceived(native, openAiLegacyMessageToTurnResult(native, providerRequest.config.output, parseOpenAiUsage(raw["usage"]?.jsonObjectOrNull()), trace))
}

private fun openAiLegacyMessageToTurnResult(message: JsonObject?, output: OutputContract, usage: TokenUsage? = null, trace: TurnTrace = TurnTrace(), blackboard: LlmBlackboard = LlmBlackboard.Empty) = TurnResult(openAiLegacyMessageItems(message, output), usage, trace, blackboard)

private fun openAiLegacyMessageItems(message: JsonObject?, output: OutputContract) = buildList {
    val reasoning = listOfNotNull(message?.string("reasoning_content"), message?.string("reasoning")).joinToString("").takeIf { it.isNotBlank() }
    reasoning?.let { add(TurnItem.Reasoning(it, ReasoningKind.Raw)) }
    message?.string("content")?.takeIf { it.isNotEmpty() }?.let { add(TurnItem.Content(it.toOutputBody(output))) }
    message?.string("refusal")?.takeIf { it.isNotEmpty() }?.let { add(TurnItem.Refusal(it)) }
    message?.get("tool_calls")?.jsonArrayOrNull().orEmpty().forEachIndexed { index, itemElement ->
        val item = itemElement.jsonObjectOrNull() ?: return@forEachIndexed
        val function = item["function"]?.jsonObjectOrNull() ?: return@forEachIndexed
        add(TurnItem.ToolCall(item.string("id") ?: "call_$index", function.string("name") ?: "function", function.string("arguments").orEmpty().toJsonObjectLenient()))
    }
}

// Legacy 流式只拼原生 assistant message，TurnResult 由该 message 转译而来
private class OpenAiLegacyAssistantMessageDraft(private val output: OutputContract, private val observer: TurnObserver?) {
    private val reasoning = StringBuilder()
    private val content = StringBuilder()
    private val refusal = StringBuilder()
    private val toolCalls = mutableMapOf<Int, ToolCallDraft>()
    private val completedIds = mutableSetOf<String>()
    private var nextItemOrdinal = 0
    private var reasoningItemId: String? = null
    private var contentItemId: String? = null
    private var refusalItemId: String? = null

    suspend fun delta(delta: JsonObject) {
        appendReasoning(delta.string("reasoning_content").orEmpty() + delta.string("reasoning").orEmpty())
        appendContent(delta.string("content").orEmpty())
        appendRefusal(delta.string("refusal").orEmpty())

        delta["tool_calls"]?.jsonArrayOrNull().orEmpty().forEach { itemElement ->
            val item = itemElement.jsonObjectOrNull() ?: return@forEach
            val index = item.int("index") ?: 0
            val tool = toolCalls.getOrPut(index) { ToolCallDraft(newItemId(), index) }
            tool.delta(item)
        }
    }

    suspend fun complete(usage: TokenUsage?, trace: TurnTrace): OpenAiLegacyReceived {
        val native = message()
        val result = openAiLegacyMessageToTurnResult(native, output, usage, trace)
        completeObserver(result)
        observer?.onEvent(TurnEvent.Completed(result))
        return OpenAiLegacyReceived(native, result)
    }

    fun partial(usage: TokenUsage? = null, trace: TurnTrace = TurnTrace()) = openAiLegacyMessageToTurnResult(message(), output, usage, trace)

    private suspend fun appendReasoning(delta: String) {
        if (delta.isEmpty()) return

        val id = reasoningItemId ?: newItemId().also {
            reasoningItemId = it
            observer?.onEvent(TurnEvent.ItemStarted(it, TurnItemKind.Reasoning))
        }

        reasoning.append(delta)
        observer?.onEvent(TurnEvent.ItemDelta(id, TurnItemDelta.Text(delta)))
    }

    private suspend fun appendContent(delta: String) {
        if (delta.isEmpty()) return
        val id = contentItemId ?: newItemId().also {
            contentItemId = it
            observer?.onEvent(TurnEvent.ItemStarted(it, TurnItemKind.Content))
        }
        content.append(delta)
        observer?.onEvent(TurnEvent.ItemDelta(id, TurnItemDelta.Text(delta)))
    }

    private suspend fun appendRefusal(delta: String) {
        if (delta.isEmpty()) return
        val id = refusalItemId ?: newItemId().also {
            refusalItemId = it
            observer?.onEvent(TurnEvent.ItemStarted(it, TurnItemKind.Refusal))
        }
        refusal.append(delta)
        observer?.onEvent(TurnEvent.ItemDelta(id, TurnItemDelta.Text(delta)))
    }

    private fun message() = buildJsonObject {
        put("role", "assistant")
        if (content.isNotEmpty()) put("content", content.toString()) else put("content", JsonNull)
        if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
        if (refusal.isNotEmpty()) put("refusal", refusal.toString())
        if (toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray { toolCalls.keys.sorted().forEach { index -> add(toolCalls.getValue(index).toJson()) } })
    }.normalizeOpenAiLegacyAssistantMessage()

    private suspend fun completeObserver(result: TurnResult) = result.items.forEach { item ->
        val id = when (item) {
            is TurnItem.Reasoning -> reasoningItemId
            is TurnItem.Content -> contentItemId
            is TurnItem.Refusal -> refusalItemId
            is TurnItem.ToolCall -> toolCalls.values.firstOrNull { it.callId == item.id }?.itemId
        } ?: newItemId().also { observer?.onEvent(TurnEvent.ItemStarted(it, item.kind())) }

        if (completedIds.add(id)) observer?.onEvent(TurnEvent.ItemCompleted(id, item))
    }

    private fun newItemId() = "item_${nextItemOrdinal++}"

    private inner class ToolCallDraft(val itemId: String, index: Int) {
        var callId = "call_$index"
        var type = "function"
        var name = "function"
        private val arguments = StringBuilder()
        private var started = false

        suspend fun delta(item: JsonObject) {
            item.string("id")?.let { callId = it }
            item.string("type")?.let { type = it }
            item["function"]?.jsonObjectOrNull()?.string("name")?.let { name = it }

            if (!started) {
                started = true
                observer?.onEvent(TurnEvent.ItemStarted(itemId, TurnItemKind.ToolCall))
            }

            item["function"]?.jsonObjectOrNull()?.string("arguments")?.let {
                arguments.append(it)
                if (it.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(itemId, TurnItemDelta.ToolArguments(it)))
            }
        }

        fun toJson() = buildJsonObject {
            put("id", callId)
            put("type", type)
            put("function", buildJsonObject {
                put("name", name)
                put("arguments", arguments.toString())
            })
        }
    }
}

private fun JsonObject.normalizeOpenAiLegacyAssistantMessage() = buildJsonObject {
    put("role", string("role") ?: "assistant")
    val content = string("content")
    if (content != null) put("content", content) else put("content", JsonNull)
    string("reasoning_content")?.let { put("reasoning_content", it) }
    string("reasoning")?.let { put("reasoning", it) }
    string("refusal")?.let { put("refusal", it) }
    this@normalizeOpenAiLegacyAssistantMessage["tool_calls"]?.jsonArrayOrNull()?.let { put("tool_calls", it) }
}

private fun parseOpenAiUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    val promptDetails = usage["prompt_tokens_details"]?.jsonObjectOrNull()
    val completionDetails = usage["completion_tokens_details"]?.jsonObjectOrNull()
    return TokenUsage(usage.int("prompt_tokens"), usage.int("completion_tokens"), usage.int("total_tokens"), promptDetails?.int("cached_tokens"), completionDetails?.int("reasoning_tokens"))
}
