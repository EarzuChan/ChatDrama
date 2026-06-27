package me.earzuchan.chatdrama.framework.llm.backend

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

private const val CLAUDE_PROTOCOL_MAX_OUTPUT_TOKENS = 8192 // 128000 // CLAUDE 必填这一块，128k有感觉吗。8192是我没钱了省钱临时搞一下

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
        val draft = TurnResultDraft(providerRequest.config.output, observer)
        val native = ClaudeNativeContentBuilder()
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
                        native.start(index, block)
                        parseClaudeContentBlockStartToDraft(index, block, draft)
                    }

                    "content_block_delta" -> {
                        val index = raw.int("index") ?: 0
                        val delta = raw["delta"]?.jsonObjectOrNull() ?: return@postSse
                        native.delta(index, delta)
                        parseClaudeContentBlockDeltaToDraft(index, delta, native.toolIdentity(index), draft)
                    }

                    "content_block_stop" -> {
                        val index = raw.int("index") ?: 0
                        native.toolIdentity(index)?.first?.let { draft.completeTool(it) }
                    }

                    "message_delta" -> {
                        raw["usage"]?.jsonObjectOrNull()?.let { usage = parseClaudeUsage(it) }
                        raw["delta"]?.jsonObjectOrNull()?.string("stop_reason")?.let { finishReason = it }
                    }
                }
            }

            val blackboard = messageId?.let { LlmBlackboard.Empty.with("claude.messages.message_id", it) } ?: LlmBlackboard.Empty
            val trace = TurnTrace(shape, model, finishReason, blackboard = blackboard)
            val result = draft.complete(usage, trace, blackboard)
            return ClaudeReceived(native.messageOrNull(), result)
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

private suspend fun parseClaudeReceived(raw: JsonObject, providerRequest: ProviderTurnRequest<*>): ClaudeReceived {
    val draft = TurnResultDraft(providerRequest.config.output)
    val content = raw["content"]?.jsonArrayOrNull() ?: JsonArray(emptyList())

    content.forEachIndexed { index, itemElement ->
        val item = itemElement.jsonObjectOrNull() ?: return@forEachIndexed
        parseClaudeNativeBlockToDraft(index, item, draft)
    }

    val blackboard = raw.string("id")?.let { LlmBlackboard.Empty.with("claude.messages.message_id", it) } ?: LlmBlackboard.Empty
    val trace = TurnTrace(ProviderShape.Claude, raw.string("model") ?: providerRequest.config.model, raw.string("stop_reason"), raw, blackboard)
    return ClaudeReceived(claudeNativeAssistantMessage(content), draft.complete(parseClaudeUsage(raw["usage"]?.jsonObjectOrNull()), trace, blackboard))
}

private suspend fun parseClaudeNativeBlockToDraft(index: Int, item: JsonObject, draft: TurnResultDraft) {
    when (item.string("type")) {
        "text" -> draft.appendContent(item.string("text").orEmpty())
        "thinking" -> draft.appendReasoning(item.string("thinking").orEmpty(), ReasoningKind.Summary, item.string("signature")?.let { LlmBlackboard.Empty.with("claude.messages.thinking_signature", it) } ?: LlmBlackboard.Empty)
        "redacted_thinking" -> draft.appendReasoning("", ReasoningKind.Redacted, item.string("data")?.let { LlmBlackboard.Empty.with("claude.messages.redacted_thinking", it) } ?: LlmBlackboard.Empty)
        "tool_use" -> {
            val id = item.string("id") ?: "tool_$index"
            val name = item.string("name") ?: "function"
            draft.startTool(id, name)
            draft.appendToolArguments(id, name, (item["input"]?.jsonObjectOrNull() ?: emptyJsonObject()).toString())
            draft.completeTool(id)
        }
    }
}

private suspend fun parseClaudeContentBlockStartToDraft(index: Int, block: JsonObject, draft: TurnResultDraft) {
    when (block.string("type")) {
        "thinking" -> block.string("thinking")?.let { draft.appendReasoning(it) }
        "redacted_thinking" -> draft.appendReasoning("", ReasoningKind.Redacted, block.string("data")?.let { LlmBlackboard.Empty.with("claude.messages.redacted_thinking", it) } ?: LlmBlackboard.Empty)
        "tool_use" -> draft.startTool(block.string("id") ?: "tool_$index", block.string("name") ?: "function")
        "text" -> block.string("text")?.let { draft.appendContent(it) }
    }
}

private suspend fun parseClaudeContentBlockDeltaToDraft(index: Int, delta: JsonObject, tool: Pair<String, String>?, draft: TurnResultDraft) {
    when (delta.string("type")) {
        "text_delta" -> draft.appendContent(delta.string("text").orEmpty())
        "thinking_delta" -> draft.appendReasoning(delta.string("thinking").orEmpty())
        "signature_delta" -> draft.appendReasoning("", blackboard = delta.string("signature")?.let { LlmBlackboard.Empty.with("claude.messages.thinking_signature", it) } ?: LlmBlackboard.Empty)
        "input_json_delta" -> {
            val (id, name) = tool ?: ("tool_$index" to "function")
            draft.appendToolArguments(id, name, delta.string("partial_json").orEmpty())
        }
    }
}

private class ClaudeNativeContentBuilder {
    private val blocks = mutableMapOf<Int, Block>()

    fun start(index: Int, block: JsonObject) {
        blocks[index] = Block(block)
    }

    fun delta(index: Int, delta: JsonObject) {
        val block = blocks.getOrPut(index) { Block(buildJsonObject { put("type", "text") }) }
        block.delta(delta)
    }

    fun toolIdentity(index: Int) = blocks[index]?.toolIdentity()

    fun messageOrNull(): JsonObject? {
        if (blocks.isEmpty()) return null
        return claudeNativeAssistantMessage(JsonArray(blocks.keys.sorted().mapNotNull { blocks[it]?.toJson() }))
    }

    private class Block(start: JsonObject) {
        private val values = start.toMutableMap()
        private val inputJson = StringBuilder()

        fun delta(delta: JsonObject) {
            when (delta.string("type")) {
                "text_delta" -> values["text"] = JsonPrimitive((values["text"]?.jsonPrimitiveOrNull()?.contentOrNull ?: "") + delta.string("text").orEmpty())
                "thinking_delta" -> values["thinking"] = JsonPrimitive((values["thinking"]?.jsonPrimitiveOrNull()?.contentOrNull ?: "") + delta.string("thinking").orEmpty())
                "signature_delta" -> delta.string("signature")?.let { values["signature"] = JsonPrimitive(it) }
                "input_json_delta" -> inputJson.append(delta.string("partial_json").orEmpty())
            }
        }

        fun toolIdentity(): Pair<String, String>? {
            if (values["type"]?.jsonPrimitiveOrNull()?.contentOrNull != "tool_use") return null
            return (values["id"]?.jsonPrimitiveOrNull()?.contentOrNull ?: "tool") to (values["name"]?.jsonPrimitiveOrNull()?.contentOrNull ?: "function")
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

private fun ClaudeLaneB.tidyAfterAppend(): ClaudeLaneB {
    return copy(messages = messages.tidyLatestClaudeWave())
}

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
