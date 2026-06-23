package me.earzuchan.chatdrama.framework.llm

import kotlinx.serialization.json.*

private const val CLAUDE_PROTOCOL_MAX_OUTPUT_TOKENS = 128000 // CLAUDE 必填这一块，128k有感觉吗

data class ClaudeBackendConfig(val apiKey: String, val defaultModel: String, val baseUrl: String = "https://api.anthropic.com", val anthropicVersion: String = "2023-06-01", val extraHeaders: Map<String, String> = emptyMap())

class ClaudeBackend(private val config: ClaudeBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.Claude
    override val defaultConfig = LlmCallConfig(model = config.defaultModel, cache = CachePreference.Prefer, remoteState = RemoteStatePreference.Off)
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.Reasoning), setOf(LlmFeature.JsonOutput, LlmFeature.PromptCaching))

    override suspend fun request(turn: ProviderTurn, mode: RequestMode) = when (mode) {
        RequestMode.Static -> parseClaudeResponse(postJson(messagesUrl(), headers(), claudeBody(turn, stream = false)), turn)
        is RequestMode.Streamed -> stream(turn, mode.observer)
    }

    private suspend fun stream(turn: ProviderTurn, observer: TurnObserver?): TurnResult {
        val draft = TurnDraft(turn.config.output, observer)
        val tools = mutableMapOf<Int, Pair<String, String>>()
        var usage: TokenUsage? = null
        var finishReason: String? = null
        var messageId: String? = null

        try {
            postSse(messagesUrl(), headers(), claudeBody(turn, stream = true)) { event, data ->
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse
                when (event) {
                    "message_start" -> messageId = raw["message"]?.jsonObjectOrNull()?.string("id")
                    "content_block_start" -> {
                        val index = raw.int("index") ?: 0
                        val block = raw["content_block"]?.jsonObjectOrNull() ?: return@postSse
                        when (block.string("type")) {
                            "tool_use" -> {
                                val id = block.string("id") ?: "tool_$index"
                                val name = block.string("name") ?: "function"
                                tools[index] = id to name
                                draft.startTool(id, name)
                            }

                            "thinking" -> block.string("thinking")?.let { draft.appendReasoning(it) }
                            "redacted_thinking" -> draft.appendReasoning(null.orEmpty(), ReasoningKind.Redacted, block.string("data")?.let { Blackboard.Empty.with("claude.messages.redacted_thinking", it) } ?: Blackboard.Empty)
                        }
                    }

                    "content_block_delta" -> {
                        val index = raw.int("index") ?: 0
                        val delta = raw["delta"]?.jsonObjectOrNull() ?: return@postSse
                        when (delta.string("type")) {
                            "text_delta" -> draft.appendContent(delta.string("text").orEmpty())
                            "thinking_delta" -> draft.appendReasoning(delta.string("thinking").orEmpty())
                            "signature_delta" -> draft.appendReasoning("", ReasoningKind.Opaque, delta.string("signature")?.let { Blackboard.Empty.with("claude.messages.thinking_signature", it) } ?: Blackboard.Empty)
                            "input_json_delta" -> {
                                val (id, name) = tools[index] ?: ("tool_$index" to "function")
                                draft.appendToolArguments(id, name, delta.string("partial_json").orEmpty())
                            }
                        }
                    }

                    "message_delta" -> {
                        raw["usage"]?.jsonObjectOrNull()?.let { usage = parseClaudeUsage(it) }
                        raw["delta"]?.jsonObjectOrNull()?.string("stop_reason")?.let { finishReason = it }
                    }
                }
            }
            tools.values.forEach { (id, _) -> draft.completeTool(id) }
            val blackboard = messageId?.let { Blackboard.Empty.with("claude.messages.message_id", it) } ?: Blackboard.Empty
            return draft.complete(usage, TurnTrace(shape, turn.config.model, finishReason, blackboard = blackboard), blackboard)
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: "Claude stream failed", throwable, draft.partial(usage, TurnTrace(shape, turn.config.model, finishReason)))
        }
    }

    private fun messagesUrl() = "${config.baseUrl.trimEnd('/')}/v1/messages"

    private fun headers() = buildMap {
        put("x-api-key", config.apiKey)
        put("anthropic-version", config.anthropicVersion)
        put("anthropic-dangerous-direct-browser-access", "true")
        putAll(config.extraHeaders)
    }

    private fun claudeBody(turn: ProviderTurn, stream: Boolean): JsonObject {
        val thinking = claudeThinking(turn.config.reasoning)
        val body = buildJsonObject {
            put("model", turn.config.model)
            put("max_tokens", CLAUDE_PROTOCOL_MAX_OUTPUT_TOKENS)
            put("messages", claudeMessages(turn))
            if (turn.rootRevision.root.instructions.isNotEmpty()) put("system", claudeSystem(turn.rootRevision.root.instructions))
            turn.config.temperature?.let { put("temperature", it) }
            if (stream) put("stream", true)
            if (turn.rootRevision.root.tools.isNotEmpty()) put("tools", buildJsonArray { turn.rootRevision.root.tools.forEach { add(claudeTool(it)) } })
            thinking?.let { put("thinking", it) }
            if (turn.config.output !is OutputContract.Text) put("system", claudeSystem(turn.rootRevision.root.instructions + ContentPart.Text(turn.config.output.jsonInstruction())))
        }
        return body.merge(turn.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun claudeMessages(turn: ProviderTurn) = buildJsonArray {
        turn.nodes.forEach { node ->
            when (node) {
                is TurnRequestNode -> node.request.items.forEach { item ->
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

                is TurnResultNode -> add(buildJsonObject {
                    put("role", "assistant")
                    put("content", buildJsonArray {
                        node.result.items.forEach { item ->
                            when (item) {
                                is TurnItem.Content -> add(buildJsonObject {
                                    put("type", "text")
                                    put("text", item.asText())
                                })

                                is TurnItem.Reasoning -> add(item.toClaudeThinkingBlock())
                                is TurnItem.ToolCall -> add(buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", item.id)
                                    put("name", item.name)
                                    put("input", item.arguments)
                                })

                                is TurnItem.Refusal -> item.text?.let { add(buildJsonObject {
                                    put("type", "text")
                                    put("text", it)
                                }) }
                            }
                        }
                    })
                })
            }
        }
    }
}

private fun claudeSystem(parts: List<ContentPart>) = buildJsonArray { parts.forEach { add(claudeContentBlock(it)) } }

private fun claudeContent(parts: List<ContentPart>) = buildJsonArray { parts.forEach { add(claudeContentBlock(it)) } }

private fun claudeContentBlock(part: ContentPart): JsonObject = when (part) {
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

private suspend fun parseClaudeResponse(raw: JsonObject, turn: ProviderTurn): TurnResult {
    val draft = TurnDraft(turn.config.output)
    raw["content"]?.jsonArrayOrNull().orEmpty().forEachIndexed { index, itemElement ->
        val item = itemElement.jsonObjectOrNull() ?: return@forEachIndexed
        when (item.string("type")) {
            "text" -> draft.appendContent(item.string("text").orEmpty())
            "thinking" -> draft.appendReasoning(item.string("thinking").orEmpty(), ReasoningKind.Summary, item.string("signature")?.let { Blackboard.Empty.with("claude.messages.thinking_signature", it) } ?: Blackboard.Empty)
            "redacted_thinking" -> draft.appendReasoning("", ReasoningKind.Redacted, item.string("data")?.let { Blackboard.Empty.with("claude.messages.redacted_thinking", it) } ?: Blackboard.Empty)
            "tool_use" -> {
                val id = item.string("id") ?: "tool_$index"
                val name = item.string("name") ?: "function"
                draft.startTool(id, name)
                draft.appendToolArguments(id, name, (item["input"]?.jsonObjectOrNull() ?: emptyJsonObject()).toString())
                draft.completeTool(id)
            }
        }
    }
    val blackboard = raw.string("id")?.let { Blackboard.Empty.with("claude.messages.message_id", it) } ?: Blackboard.Empty
    return draft.complete(parseClaudeUsage(raw["usage"]?.jsonObjectOrNull()), TurnTrace(ProviderShape.Claude, raw.string("model") ?: turn.config.model, raw.string("stop_reason"), raw, blackboard), blackboard)
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

private fun ReasoningLevel.defaultClaudeThinkingBudget() = when (this) {
    ReasoningLevel.Off -> 0
    ReasoningLevel.Minimal -> 1024
    ReasoningLevel.Low -> 2048
    ReasoningLevel.Medium -> 4096
    ReasoningLevel.High -> 8192
    ReasoningLevel.Max -> 16000
}
