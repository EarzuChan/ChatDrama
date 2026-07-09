package me.earzuchan.chatdrama.framework.llm.backend

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

private const val CLAUDE_PROTOCOL_MAX_OUTPUT_TOKENS = 128000 // CLAUDE 必填这一块，128k有感觉吗

data class ClaudeBackendConfig(val apiKey: String, val baseUrl: String = "https://api.anthropic.com", val anthropicVersion: String = "2023-06-01", val extraHeaders: Map<String, String> = emptyMap())

private data class ClaudeRoot(val system: JsonArray? = null, val tools: JsonArray? = null)

private data class ClaudeLaneB(override val rootRevisionId: LlmNodeId, override val anchorNodeId: LlmNodeId?, override val configKey: ProviderLaneBConfigKey, val root: ClaudeRoot, val messages: List<JsonObject>, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : ProviderLaneB {
    override val shape = ProviderShape.Claude
}

private data class ClaudeReceived(val nativeMessage: JsonObject?, val result: TurnResult)

class ClaudeBackend(private val config: ClaudeBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.Claude
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.Reasoning), setOf(LlmFeature.JsonOutput, LlmFeature.PromptCaching))

    override fun rebuildLaneB(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB = ClaudeLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), claudeRoot(rootRevision.root, config.output), projectThinLaneAToClaudeMessages(nodes), sessionBlackboard.onlyPrefixed("claude."))

    override fun debugLaneB(laneB: ProviderLaneB?) = laneB?.let { debugLaneB(it as? ClaudeLaneB ?: return@let providerLaneBDebug("ClaudeLaneB(wrong type)", it)) } ?: "ClaudeLaneB(null)"

    override suspend fun request(providerRequest: ProviderTurnRequest<ProviderLaneB>, mode: RequestMode): ProviderTurnResultCommit<ProviderLaneB> = performRequest(providerRequest.typedRequest("Claude") { it as? ClaudeLaneB }, mode)

    private suspend fun performRequest(providerRequest: ProviderTurnRequest<ClaudeLaneB>, mode: RequestMode): ProviderTurnResultCommit<ClaudeLaneB> {
        val received = when (mode) {
            RequestMode.Static -> receiveStatic(providerRequest)
            is RequestMode.Streamed -> receiveStream(providerRequest, mode.observer)
        }
        return commitReceived(providerRequest, received)
    }

    private suspend fun receiveStatic(providerRequest: ProviderTurnRequest<ClaudeLaneB>) = parseClaudeReceived(postJson(messagesUrl(), headers(), claudeBody(providerRequest, stream = false)), providerRequest)

    private suspend fun receiveStream(providerRequest: ProviderTurnRequest<ClaudeLaneB>, observer: TurnObserver?): ClaudeReceived {
        val draft = ClaudeMessageDraft(providerRequest.config.output, observer)
        var usage: TokenUsage? = null
        var finishReason: String? = null
        var messageId: String? = null
        var model = providerRequest.config.model

        try {
            postSse(messagesUrl(), headers(), claudeBody(providerRequest, stream = true)) { event, data ->
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse
                when (event) {
                    "message_start" -> raw["message"]?.jsonObjectOrNull()?.let { message ->
                        messageId = message.string("id") ?: messageId
                        model = message.string("model") ?: model
                    }

                    "content_block_start" -> {
                        val index = raw.int("index") ?: 0
                        val block = raw["content_block"]?.jsonObjectOrNull() ?: return@postSse
                        draft.start(index, block)
                    }

                    "content_block_delta" -> {
                        val index = raw.int("index") ?: 0
                        val delta = raw["delta"]?.jsonObjectOrNull() ?: return@postSse
                        draft.delta(index, delta)
                    }

                    "content_block_stop" -> draft.stop(raw.int("index") ?: 0)

                    "message_delta" -> {
                        raw["usage"]?.jsonObjectOrNull()?.let { usage = parseClaudeUsage(it) }
                        raw["delta"]?.jsonObjectOrNull()?.string("stop_reason")?.let { finishReason = it }
                    }
                }
            }

            val blackboard = messageId?.let { LlmBlackboard.Empty.with("claude.messages.message_id", it) } ?: LlmBlackboard.Empty
            val trace = TurnTrace(shape, model, finishReason, blackboard = blackboard)
            return draft.complete(messageId, model, finishReason, usage, trace, blackboard)
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: "Claude stream failed", throwable, draft.partial(usage, TurnTrace(shape, model, finishReason)))
        }
    }

    private fun messagesUrl() = "${config.baseUrl.trimEnd('/')}/v1/messages"

    private fun headers() = buildMap {
        put("x-api-key", config.apiKey)
        put("anthropic-version", config.anthropicVersion)
        put("anthropic-dangerous-direct-browser-access", "true")
        putAll(config.extraHeaders)
    }

    private fun debugLaneB(lane: ClaudeLaneB) = buildString {
        appendLine(providerLaneBDebug("ClaudeLaneB", lane))
        appendLine("  root.system=${lane.root.system}")
        appendLine("  root.tools=${lane.root.tools}")
        appendLine("  messages:")
        lane.messages.forEachIndexed { index, message -> appendLine("    [$index] $message") }
    }

    private fun claudeBody(providerRequest: ProviderTurnRequest<ClaudeLaneB>, stream: Boolean): JsonObject {
        val lane = providerRequest.laneB
        val thinking = claudeThinking(providerRequest.config.reasoning)
        val body = buildJsonObject {
            put("model", providerRequest.config.model)
            put("max_tokens", CLAUDE_PROTOCOL_MAX_OUTPUT_TOKENS)
            put("messages", claudeMessagesForSending(lane, providerRequest.requestNode.request))
            lane.root.system?.let { put("system", it) }
            providerRequest.config.temperature?.let { put("temperature", it) }
            if (stream) put("stream", true)
            lane.root.tools?.takeIf { it.isNotEmpty() }?.let { put("tools", it) }
            thinking?.let { put("thinking", it) }
        }
        return body.merge(providerRequest.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun commitReceived(providerRequest: ProviderTurnRequest<ClaudeLaneB>, received: ClaudeReceived): ProviderTurnResultCommit<ClaudeLaneB> {
        val lane = providerRequest.laneB
        val messages = buildList {
            addAll(lane.messages)
            addAll(projectTurnRequestToClaudeMessages(providerRequest.requestNode.request))
            received.nativeMessage?.let { add(it) }
        }
        val next = lane.copy(anchorNodeId = providerRequest.resultNodeId, messages = messages, blackboard = lane.blackboard.withAll(received.result.blackboard.onlyPrefixed("claude."))).tidyAfterAppend()
        return ProviderTurnResultCommit(next, received.result)
    }
}

private fun claudeRoot(root: SessionRoot, output: OutputContract): ClaudeRoot {
    val instructions = if (output is OutputContract.Text) root.instructions else root.instructions + ContentPart.Text(output.jsonInstruction())
    return ClaudeRoot(instructions.takeIf { it.isNotEmpty() }?.let(::claudeSystem), root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonArray { it.forEach { tool -> add(claudeTool(tool)) } } })
}

private fun claudeMessagesForSending(lane: ClaudeLaneB, request: TurnRequest) = buildJsonArray {
    lane.messages.forEach { add(it) }
    projectTurnRequestToClaudeMessages(request).forEach { add(it) }
}

// 重建路径只消费 Session 已经 thin 过的 LaneA；Claude 本家签名思考恢复，外家/无签名思考乔装成文本。
private fun projectThinLaneAToClaudeMessages(nodes: List<SessionNode>) = buildList {
    nodes.forEach { node ->
        when (node) {
            is TurnRequestNode -> addAll(projectTurnRequestToClaudeMessages(node.request))
            is TurnResultNode -> add(projectTurnResultToClaudeMessage(node.result))
        }
    }
}

private fun projectTurnRequestToClaudeMessages(request: TurnRequest) = buildList {
    request.items.forEach { item ->
        when (item) {
            is TurnInputItem.Content -> add(buildJsonObject {
                put("role", "user")
                put("content", claudeContent(item.parts))
            })

            is TurnInputItem.ToolResult -> add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", item.toolCallId)
                        put("content", item.parts.plainText())
                        if (item.isError) put("is_error", true)
                    })
                })
            })
        }
    }
}

private fun projectTurnResultToClaudeMessage(result: TurnResult) = buildJsonObject {
    put("role", "assistant")
    put("content", buildJsonArray {
        val nativeThinking = result.hasClaudeNativeThinking()
        result.items.forEach { item ->
            when (item) {
                is TurnItem.Content -> add(claudeTextBlock(item.asText()))

                is TurnItem.Reasoning -> if (nativeThinking) add(item.toClaudeThinkingBlock()) else item.previousThoughtText()?.let { add(claudeTextBlock(it)) }

                is TurnItem.ToolCall -> add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", item.id)
                    put("name", item.name)
                    put("input", item.arguments)
                })

                is TurnItem.Refusal -> item.text?.let { add(claudeTextBlock(it)) }
            }
        }
    })
}

private fun TurnResult.hasClaudeNativeThinking() = isFrom(ProviderShape.Claude) && items.filterIsInstance<TurnItem.Reasoning>().all { (it.kind == ReasoningKind.Redacted && it.blackboard.string("claude.messages.redacted_thinking") != null) || (it.kind != ReasoningKind.Redacted && it.blackboard.string("claude.messages.thinking_signature") != null) }

private fun parseClaudeReceived(raw: JsonObject, providerRequest: ProviderTurnRequest<*>): ClaudeReceived {
    val content = raw["content"]?.jsonArrayOrNull() ?: JsonArray(emptyList())
    val native = claudeNativeAssistantMessage(content)
    val blackboard = raw.string("id")?.let { LlmBlackboard.Empty.with("claude.messages.message_id", it) } ?: LlmBlackboard.Empty
    val trace = TurnTrace(ProviderShape.Claude, raw.string("model") ?: providerRequest.config.model, raw.string("stop_reason"), raw, blackboard)
    return ClaudeReceived(native, claudeMessageToTurnResult(native, providerRequest.config.output, parseClaudeUsage(raw["usage"]?.jsonObjectOrNull()), trace, blackboard))
}

private fun claudeMessageToTurnResult(message: JsonObject?, output: OutputContract, usage: TokenUsage? = null, trace: TurnTrace = TurnTrace(), blackboard: LlmBlackboard = LlmBlackboard.Empty) = TurnResult(claudeMessageItems(message, output), usage, trace, blackboard)

private fun claudeMessageItems(message: JsonObject?, output: OutputContract) = buildList {
    message?.get("content")?.jsonArrayOrNull().orEmpty().forEachIndexed { index, itemElement ->
        val item = itemElement.jsonObjectOrNull() ?: return@forEachIndexed
        when (item.string("type")) {
            "text" -> add(TurnItem.Content(item.string("text").orEmpty().toOutputBody(output)))
            "thinking" -> add(TurnItem.Reasoning(item.string("thinking").orEmpty(), ReasoningKind.Summary, item.string("signature")?.let { LlmBlackboard.Empty.with("claude.messages.thinking_signature", it) } ?: LlmBlackboard.Empty))
            "redacted_thinking" -> add(TurnItem.Reasoning(null, ReasoningKind.Redacted, item.string("data")?.let { LlmBlackboard.Empty.with("claude.messages.redacted_thinking", it) } ?: LlmBlackboard.Empty))
            "tool_use" -> add(TurnItem.ToolCall(item.string("id") ?: "tool_$index", item.string("name") ?: "function", item["input"]?.jsonObjectOrNull() ?: emptyJsonObject()))
        }
    }
}

// Claude 流式 draft 只维护 native message blocks，并从 blocks 派生统一 observer/result。
private class ClaudeMessageDraft(private val output: OutputContract, private val observer: TurnObserver?) {
    private val blocks = mutableMapOf<Int, Block>()
    private val completedIds = mutableSetOf<String>()
    private var nextFallbackOrdinal = 0

    suspend fun start(index: Int, block: JsonObject) {
        blocks[index] = Block(index, block)
        blocks[index]?.startObserver()
    }

    suspend fun delta(index: Int, delta: JsonObject) {
        val block = blocks.getOrPut(index) { Block(index, buildJsonObject { put("type", "text") }) }
        block.delta(delta)
    }

    suspend fun stop(index: Int) {
        blocks[index]?.completeObserver()
    }

    suspend fun complete(messageId: String?, model: String, finishReason: String?, usage: TokenUsage?, trace: TurnTrace, blackboard: LlmBlackboard): ClaudeReceived {
        val native = message()
        val result = claudeMessageToTurnResult(native, output, usage, trace, blackboard)
        result.items.forEachIndexed { index, item -> completeFallbackObserver(index, item) }
        observer?.onEvent(TurnEvent.Completed(result))
        return ClaudeReceived(native, result)
    }

    fun partial(usage: TokenUsage? = null, trace: TurnTrace = TurnTrace()) = claudeMessageToTurnResult(message(), output, usage, trace)

    private fun message() = claudeNativeAssistantMessage(JsonArray(blocks.keys.sorted().mapNotNull { blocks[it]?.toJson() }))

    private suspend fun completeFallbackObserver(index: Int, item: TurnItem) {
        val block = blocks[index]
        if (block != null && block.observerItemId != null) return
        val id = block?.observerItemId ?: "item_${nextFallbackOrdinal++}"

        if (completedIds.add(id)) {
            observer?.onEvent(TurnEvent.ItemStarted(id, item.kind()))
            observer?.onEvent(TurnEvent.ItemCompleted(id, item))
        }
    }

    private inner class Block(private val index: Int, start: JsonObject) {
        private val values = start.toMutableMap()
        private val inputJson = StringBuilder()
        var observerItemId: String? = null
            private set

        suspend fun startObserver() {
            val kind = when (values["type"]?.jsonPrimitiveOrNull()?.contentOrNull) {
                "thinking", "redacted_thinking" -> TurnItemKind.Reasoning
                "tool_use" -> TurnItemKind.ToolCall
                else -> TurnItemKind.Content
            }

            observerItemId = observerItemId ?: "item_$index"
            observer?.onEvent(TurnEvent.ItemStarted(observerItemId!!, kind))
            values["text"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { observer?.onEvent(TurnEvent.ItemDelta(observerItemId!!, TurnItemDelta.Text(it))) }
            values["thinking"]?.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { observer?.onEvent(TurnEvent.ItemDelta(observerItemId!!, TurnItemDelta.Text(it))) }
        }

        suspend fun delta(delta: JsonObject) {
            startObserver()

            when (delta.string("type")) {
                "text_delta" -> {
                    val text = delta.string("text").orEmpty()
                    values["text"] = JsonPrimitive((values["text"]?.jsonPrimitiveOrNull()?.contentOrNull ?: "") + text)
                    if (text.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(observerItemId!!, TurnItemDelta.Text(text)))
                }
                "thinking_delta" -> {
                    val text = delta.string("thinking").orEmpty()
                    values["thinking"] = JsonPrimitive((values["thinking"]?.jsonPrimitiveOrNull()?.contentOrNull ?: "") + text)
                    if (text.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(observerItemId!!, TurnItemDelta.Text(text)))
                }
                "signature_delta" -> delta.string("signature")?.let { values["signature"] = JsonPrimitive(it) }
                "input_json_delta" -> {
                    val text = delta.string("partial_json").orEmpty()
                    inputJson.append(text)
                    if (text.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(observerItemId!!, TurnItemDelta.ToolArguments(text)))
                }
            }
        }

        suspend fun completeObserver() {
            val item = claudeMessageItems(claudeNativeAssistantMessage(JsonArray(listOf(toJson()))), output).firstOrNull() ?: return
            val id = observerItemId ?: "item_$index"
            if (completedIds.add(id)) observer?.onEvent(TurnEvent.ItemCompleted(id, item))
        }

        fun toJson(): JsonObject {
            if (values["type"]?.jsonPrimitiveOrNull()?.contentOrNull == "tool_use" && inputJson.isNotBlank()) values["input"] = runCatching { Json.parseToJsonElement(inputJson.toString()) }.getOrNull()?.jsonObjectOrNull() ?: emptyJsonObject()
            return JsonObject(values)
        }
    }
}

private fun claudeNativeAssistantMessage(content: JsonArray) = buildJsonObject {
    put("role", "assistant")
    put("content", content)
}

private fun ClaudeLaneB.tidyAfterAppend() = copy(messages = messages.tidyLatestClaudeWave())

private data class ClaudeWaveRange(val start: Int, val endExclusive: Int)

private fun List<JsonObject>.tidyLatestClaudeWave(): List<JsonObject> {
    val range = latestClaudeWaveRange() ?: return this
    val tidied = subList(range.start, range.endExclusive).tidiedClaudeWave()
    if (tidied == subList(range.start, range.endExclusive)) return this
    return take(range.start) + tidied + drop(range.endExclusive)
}

private fun List<JsonObject>.latestClaudeWaveRange(): ClaudeWaveRange? {
    if (lastOrNull()?.isClaudeFinalAssistant() != true) return null

    var start = indexOfLast { it.string("role") == "user" && !it.hasClaudeToolResult() }.takeIf { it >= 0 } ?: return null
    while (start > 0 && this[start - 1].string("role") == "user" && !this[start - 1].hasClaudeToolResult()) start--
    return ClaudeWaveRange(start, size)
}

private fun List<JsonObject>.tidiedClaudeWave(): List<JsonObject> {
    val promptCount = takeWhile { it.string("role") == "user" && !it.hasClaudeToolResult() }.size
    if (promptCount == 0) return this
    val final = lastOrNull { it.isClaudeFinalAssistant() } ?: return this
    return take(promptCount) + final.claudeFinalOnly()
}

private fun JsonObject.isClaudeFinalAssistant() = string("role") == "assistant" && !hasClaudeToolUse() && hasClaudeVisibleText()

private fun JsonObject.hasClaudeToolUse() = this["content"]?.jsonArrayOrNull().orEmpty().any { it.jsonObjectOrNull()?.string("type") == "tool_use" }

private fun JsonObject.hasClaudeToolResult() = this["content"]?.jsonArrayOrNull().orEmpty().any { it.jsonObjectOrNull()?.string("type") == "tool_result" }

private fun JsonObject.hasClaudeVisibleText() = this["content"]?.jsonArrayOrNull().orEmpty().any { it.jsonObjectOrNull()?.let { block -> block.string("type") == "text" && block.string("text")?.isNotBlank() == true } == true }

private fun JsonObject.claudeFinalOnly() = buildJsonObject {
    put("role", "assistant")
    put("content", buildJsonArray {
        this@claudeFinalOnly["content"]?.jsonArrayOrNull().orEmpty().forEach { blockElement ->
            val block = blockElement.jsonObjectOrNull() ?: return@forEach
            if (block.string("type") == "text" && block.string("text")?.isNotBlank() == true) add(claudeTextBlock(block.string("text").orEmpty()))
        }
    })
}

private fun claudeSystem(parts: List<ContentPart>) = buildJsonArray { parts.forEach { add(claudeContentBlock(it)) } }

private fun claudeContent(parts: List<ContentPart>) = buildJsonArray { parts.forEach { add(claudeContentBlock(it)) } }

private fun claudeContentBlock(part: ContentPart): JsonObject = when (part) {
    is ContentPart.Text -> claudeTextBlock(part.text)

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

private fun claudeTextBlock(text: String) = buildJsonObject {
    put("type", "text")
    put("text", text)
}

private fun claudeTool(tool: ToolDefinition) = buildJsonObject {
    put("name", tool.name)
    tool.description?.let { put("description", it) }
    put("input_schema", tool.inputSchema())
}

private fun claudeThinking(reasoning: ReasoningLevel): JsonObject? = when (reasoning) {
    ReasoningLevel.Off -> null
    else -> buildJsonObject {
        put("type", "enabled")
        put("budget_tokens", reasoning.defaultClaudeThinkingBudget())
    }
}

private fun parseClaudeUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    val input = usage.int("input_tokens")
    val output = usage.int("output_tokens")
    return TokenUsage(input, output, listOfNotNull(input, output).takeIf { it.isNotEmpty() }?.sum(), usage.int("cache_read_input_tokens"))
}

private fun TurnItem.Reasoning.toClaudeThinkingBlock() = when (kind) {
    ReasoningKind.Redacted -> buildJsonObject {
        put("type", "redacted_thinking")
        blackboard.string("claude.messages.redacted_thinking")?.let { put("data", it) }
    }

    else -> buildJsonObject {
        put("type", "thinking")
        put("thinking", text.orEmpty())
        blackboard.string("claude.messages.thinking_signature")?.let { put("signature", it) }
    }
}

private fun OutputContract.jsonInstruction() = when (this) {
    OutputContract.Text -> ""
    OutputContract.Json -> "Return only a valid JSON object. Do not include markdown fences or commentary."
    is OutputContract.JsonSchema -> "Return only valid JSON matching this schema. Do not include markdown fences or commentary.\n$schema"
}

// TODO：这是官方的吗？回头核对一下
private fun ReasoningLevel.defaultClaudeThinkingBudget() = when (this) {
    ReasoningLevel.Off -> 0
    ReasoningLevel.Minimal -> 1024
    ReasoningLevel.Low -> 2048
    ReasoningLevel.Medium -> 4096
    ReasoningLevel.High -> 8192
    ReasoningLevel.Max -> 16000
}
