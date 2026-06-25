package me.earzuchan.chatdrama.framework.llm.backend

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

data class GeminiBackendConfig(val apiKey: String, val baseUrl: String = "https://generativelanguage.googleapis.com", val apiVersion: String = "v1beta", val extraHeaders: Map<String, String> = emptyMap())

private data class GeminiRoot(val systemInstruction: JsonObject? = null, val tools: JsonArray? = null, val toolConfig: JsonObject? = null)

private data class GeminiLaneB(override val rootRevisionId: LlmNodeId, override val anchorNodeId: LlmNodeId?, override val configKey: ProviderLaneBConfigKey, val root: GeminiRoot, val contents: List<JsonObject>, val textualizedToolIds: Set<String> = emptySet(), override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : ProviderLaneB {
    override val shape = ProviderShape.Gemini
}

class GeminiBackend(private val config: GeminiBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.Gemini
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput, LlmFeature.Reasoning), setOf(LlmFeature.PromptCaching, LlmFeature.RemoteState))

    override fun rebuildLaneB(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB {
        val projection = geminiProjection(nodes)
        return GeminiLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), geminiRoot(rootRevision.root), projection.contents, projection.textualizedToolIds, sessionBlackboard.onlyPrefixed("gemini."))
    }

    override fun debugLaneB(laneB: ProviderLaneB?) = laneB?.let { debugLaneB(it as? GeminiLaneB ?: return@let providerLaneBDebug("GeminiLaneB(wrong type)", it)) } ?: "GeminiLaneB(null)"

    override suspend fun request(turn: ProviderTurn<ProviderLaneB>, mode: RequestMode): ProviderTurnCommit<ProviderLaneB> = internalRealRequest(turn.typedTurn("Gemini") { it as? GeminiLaneB }, mode)

    private suspend fun internalRealRequest(turn: ProviderTurn<GeminiLaneB>, mode: RequestMode) = when (mode) {
        RequestMode.Static -> commit(turn, parseGeminiResponse(postGeminiJson(geminiUrl(turn.config.model, stream = false), headers(), geminiBody(turn)), turn))

        is RequestMode.Streamed -> commit(turn, stream(turn, mode.observer))
    }

    private suspend fun stream(turn: ProviderTurn<GeminiLaneB>, observer: TurnObserver?): TurnResult {
        val draft = TurnDraft(turn.config.output, observer)
        var usage: TokenUsage? = null
        var finishReason: String? = null
        var responseId: String? = null
        var toolOrdinal = 0

        try {
            postGeminiSse(geminiUrl(turn.config.model, stream = true), headers(), geminiBody(turn)) { _, data ->
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postGeminiSse
                responseId = raw.string("responseId") ?: responseId
                parseGeminiUsage(raw["usageMetadata"]?.jsonObjectOrNull())?.let { usage = it }
                val candidate = raw["candidates"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull() ?: return@postGeminiSse
                candidate.string("finishReason")?.let { finishReason = it }
                candidate["content"]?.jsonObjectOrNull()?.get("parts")?.jsonArrayOrNull().orEmpty().forEach { partElement ->
                    val part = partElement.jsonObjectOrNull() ?: return@forEach
                    part.appendGeminiTextPartTo(draft)
                    part["functionCall"]?.jsonObjectOrNull()?.let { functionCall ->
                        val name = functionCall.string("name") ?: "function"
                        val id = functionCall.string("id") ?: "gemini:$name:${toolOrdinal++}"
                        draft.startTool(id, name, part.geminiThoughtBlackboard())
                        draft.appendToolArguments(id, name, (functionCall["args"]?.jsonObjectOrNull() ?: emptyJsonObject()).toString())
                        draft.completeTool(id)
                    }
                }
            }
            val blackboard = responseId?.let { LlmBlackboard.Empty.with("gemini.response_id", it) } ?: LlmBlackboard.Empty
            return draft.complete(usage, TurnTrace(shape, turn.config.model, finishReason, blackboard = blackboard), blackboard)
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: "Gemini stream failed", throwable, draft.partial(usage, TurnTrace(shape, turn.config.model, finishReason)))
        }
    }

    private fun geminiUrl(model: String, stream: Boolean): String {
        val action = if (stream) "streamGenerateContent" else "generateContent"
        val base = "${config.baseUrl.trimEnd('/')}/${config.apiVersion}/models/$model:$action"
        return if (stream) "$base?alt=sse" else base
    }

    private fun headers() = buildMap {
        put("x-goog-api-key", config.apiKey)
        putAll(config.extraHeaders)
    }

    private fun debugLaneB(lane: GeminiLaneB) = buildString {
        appendLine(providerLaneBDebug("GeminiLaneB", lane))
        appendLine("  root.systemInstruction=${lane.root.systemInstruction}")
        appendLine("  root.tools=${lane.root.tools}")
        appendLine("  root.toolConfig=${lane.root.toolConfig}")
        appendLine("  textualizedToolIds=${lane.textualizedToolIds}")
        appendLine("  contents:")
        lane.contents.forEachIndexed { index, content -> appendLine("    [$index] $content") }
    }

    private suspend fun postGeminiJson(url: String, headers: Map<String, String>, body: JsonObject) = super.postJson(url, headers, body)

    private suspend fun postGeminiSse(url: String, headers: Map<String, String>, body: JsonObject, onEvent: suspend (event: String?, data: String) -> Unit) = super.postSse(url, headers, body, onEvent)

    private fun geminiBody(turn: ProviderTurn<GeminiLaneB>): JsonObject {
        val lane = turn.laneB
        val body = buildJsonObject {
            put("contents", geminiContents(lane, turn.requestNode.request))
            lane.root.systemInstruction?.let { put("systemInstruction", it) }
            geminiGenerationConfig(turn.config).takeIf { it.isNotEmpty() }?.let { put("generationConfig", it) }
            lane.root.tools?.takeIf { it.isNotEmpty() }?.let { put("tools", it) }
            lane.root.toolConfig?.let { put("toolConfig", it) }
        }
        return body.merge(turn.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun commit(turn: ProviderTurn<GeminiLaneB>, result: TurnResult): ProviderTurnCommit<GeminiLaneB> {
        val lane = turn.laneB
        val textualizedToolIds = lane.textualizedToolIds.toMutableSet()
        val next = lane.copy(anchorNodeId = turn.resultNodeId, contents = lane.contents + geminiRequestContents(turn.requestNode.request, textualizedToolIds) + geminiModelContent(result, textualizedToolIds), textualizedToolIds = textualizedToolIds, blackboard = lane.blackboard.withAll(result.blackboard.onlyPrefixed("gemini."))).tidyAfterAppend()
        return ProviderTurnCommit(next, result)
    }
}

private fun geminiRoot(root: SessionRoot) = GeminiRoot(
    systemInstruction = root.instructions.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("parts", geminiParts(it)) } },
    tools = root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonArray { add(buildJsonObject { put("functionDeclarations", buildJsonArray { it.forEach { tool -> add(geminiFunctionDeclaration(tool)) } }) }) } },
    toolConfig = root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("functionCallingConfig", buildJsonObject { put("mode", "AUTO") }) } }
)

private fun geminiContents(lane: GeminiLaneB, request: TurnRequest) = buildJsonArray {
    lane.contents.forEach { add(it) }
    geminiRequestContents(request, lane.textualizedToolIds.toMutableSet()).forEach { add(it) }
}

private data class GeminiProjection(val contents: List<JsonObject>, val textualizedToolIds: Set<String>)

// TIPS：进行转译+转译中有顺手处理
private fun geminiProjection(nodes: List<SessionNode>): GeminiProjection {
    val textualizedToolIds = mutableSetOf<String>() // 有一些外家工具调用，需要乔装处理

    val contents = buildList {
        nodes.forEach { node ->
            when (node) {
                is TurnRequestNode -> addAll(geminiRequestContents(node.request, textualizedToolIds))
                is TurnResultNode -> add(geminiModelContent(node.result, textualizedToolIds))
            }
        }
    }

    return GeminiProjection(contents, textualizedToolIds)
}

private fun geminiRequestContents(request: TurnRequest, textualizedToolIds: MutableSet<String> = mutableSetOf()) = buildList {
    request.items.forEach { item ->
        when (item) {
            is TurnInputItem.Content -> add(buildJsonObject {
                put("role", "user")
                put("parts", geminiParts(item.parts))
            })

            is TurnInputItem.ToolResult -> if (item.toolCallId in textualizedToolIds) add(geminiUserTextContent(item.previousToolResultText())) else add(geminiFunctionResponseContent(item))
        }
    }
}

private fun geminiModelContent(result: TurnResult, textualizedToolIds: MutableSet<String> = mutableSetOf()): JsonObject {
    if (!result.hasGeminiNativeToolChain() && result.hasToolCalls()) {
        result.items.filterIsInstance<TurnItem.ToolCall>().forEach { textualizedToolIds += it.id }
        // Gemini 对缺 thoughtSignature 的工具链要求很硬；外家/无签名未闭合波整体乔装为普通文本。
        return geminiModelTextContent(result.previousAssistantText() ?: "")
    }

    return buildJsonObject {
        put("role", "model")
        put("parts", buildJsonArray {
            result.items.forEach { item ->
                when (item) {
                    is TurnItem.Content -> add(buildJsonObject { put("text", item.asText()) })
                    is TurnItem.Reasoning -> if (result.isFrom(ProviderShape.Gemini)) add(item.toGeminiThoughtPart()) else item.previousThoughtText()?.let { add(buildJsonObject { put("text", it) }) }
                    is TurnItem.ToolCall -> add(buildJsonObject {
                        item.blackboard.string("gemini.thought_signature")?.let { put("thoughtSignature", it) }
                        put("functionCall", buildJsonObject {
                            put("name", item.name)
                            put("args", item.arguments)
                            if (!item.id.startsWith("gemini:")) put("id", item.id)
                        })
                    })

                    is TurnItem.Refusal -> item.text?.let { add(buildJsonObject { put("text", it) }) }
                }
            }
        })
    }
}

private fun GeminiLaneB.tidyAfterAppend(): GeminiLaneB {
    val tidied = contents.tidyLatestGeminiWave()
    if (tidied == contents) return this
    return copy(contents = tidied, textualizedToolIds = textualizedToolIds.filterTo(mutableSetOf()) { id -> tidied.any { it.containsGeminiTextualizedToolId(id) } })
}

private data class GeminiWaveRange(val start: Int, val endExclusive: Int)

private fun List<JsonObject>.tidyLatestGeminiWave(): List<JsonObject> {
    val range = latestGeminiWaveRange() ?: return this
    val tidied = subList(range.start, range.endExclusive).tidiedGeminiWave()
    if (tidied == subList(range.start, range.endExclusive)) return this
    return take(range.start) + tidied + drop(range.endExclusive)
}

private fun List<JsonObject>.latestGeminiWaveRange(): GeminiWaveRange? {
    if (lastOrNull()?.isGeminiFinalModelContent() != true) return null
    var start = indexOfLast { it.string("role") == "user" && !it.hasGeminiFunctionResponse() && !it.hasGeminiTextualizedToolResult() }.takeIf { it >= 0 } ?: return null
    while (start > 0 && this[start - 1].string("role") == "user" && !this[start - 1].hasGeminiFunctionResponse() && !this[start - 1].hasGeminiTextualizedToolResult()) start--
    return GeminiWaveRange(start, size)
}

private fun List<JsonObject>.tidiedGeminiWave(): List<JsonObject> {
    val promptCount = takeWhile { it.string("role") == "user" && !it.hasGeminiFunctionResponse() && !it.hasGeminiTextualizedToolResult() }.size
    if (promptCount == 0) return this
    val final = lastOrNull { it.isGeminiFinalModelContent() } ?: return this
    return take(promptCount) + final.geminiFinalOnly()
}

private fun JsonObject.isGeminiFinalModelContent() = string("role") == "model" && !hasGeminiFunctionCall() && hasGeminiVisibleText()

private fun JsonObject.hasGeminiFunctionCall() = this["parts"]?.jsonArrayOrNull().orEmpty().any { it.jsonObjectOrNull()?.containsKey("functionCall") == true }

private fun JsonObject.hasGeminiFunctionResponse() = this["parts"]?.jsonArrayOrNull().orEmpty().any { it.jsonObjectOrNull()?.containsKey("functionResponse") == true }

private fun JsonObject.hasGeminiTextualizedToolResult() = this["parts"]?.jsonArrayOrNull().orEmpty().any { it.jsonObjectOrNull()?.string("text")?.startsWith("[tool result]") == true }

private fun JsonObject.containsGeminiTextualizedToolId(id: String) = this["parts"]?.jsonArrayOrNull().orEmpty().any { it.jsonObjectOrNull()?.string("text")?.contains("id=$id") == true }

private fun JsonObject.hasGeminiVisibleText() = this["parts"]?.jsonArrayOrNull().orEmpty().any { part ->
    val obj = part.jsonObjectOrNull() ?: return@any false
    obj.boolean("thought") != true && obj.string("text")?.isNotBlank() == true
}

private fun JsonObject.geminiFinalOnly() = buildJsonObject {
    put("role", "model")
    put("parts", buildJsonArray {
        this@geminiFinalOnly["parts"]?.jsonArrayOrNull().orEmpty().forEach { part ->
            val obj = part.jsonObjectOrNull() ?: return@forEach
            if (obj.boolean("thought") != true && obj.string("text")?.isNotBlank() == true) add(buildJsonObject { put("text", obj.string("text").orEmpty()) })
        }
    })
}

private fun geminiFunctionResponseContent(item: TurnInputItem.ToolResult) = buildJsonObject {
    put("role", "user")
    put("parts", buildJsonArray {
        add(buildJsonObject {
            put("functionResponse", buildJsonObject {
                item.name?.let { put("name", it) }
                put("response", buildJsonObject {
                    put("toolCallId", item.toolCallId)
                    put("result", item.parts.plainText())
                    put("isError", item.isError)
                })
            })
        })
    })
}

private fun geminiUserTextContent(text: String) = buildJsonObject {
    put("role", "user")
    put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
}

private fun geminiModelTextContent(text: String) = buildJsonObject {
    put("role", "model")
    put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
}

private fun geminiParts(parts: List<ContentPart>) = buildJsonArray {
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

private fun geminiFunctionDeclaration(tool: ToolDefinition) = buildJsonObject {
    put("name", tool.name)
    tool.description?.let { put("description", it) }
    put("parameters", geminiSchema(tool.inputSchema()))
}

private fun geminiSchema(schema: JsonObject): JsonObject = buildJsonObject {
    schema.forEach { (key, value) ->
        when (key) {
            $$"$schema", $$"$id", "additionalProperties", "patternProperties", "unevaluatedProperties" -> Unit
            "properties" -> value.jsonObjectOrNull()?.let { properties -> put("properties", buildJsonObject { properties.forEach { (name, property) -> put(name, property.jsonObjectOrNull()?.let(::geminiSchema) ?: property) } }) }
            "items" -> put("items", value.jsonObjectOrNull()?.let(::geminiSchema) ?: value)
            else -> put(key, value)
        }
    }
}

private fun geminiGenerationConfig(config: EffectiveLlmCallConfig) = buildJsonObject {
    config.temperature?.let { put("temperature", it) }
    when (val output = config.output) {
        OutputContract.Text -> Unit
        OutputContract.Json -> put("responseMimeType", "application/json")
        is OutputContract.JsonSchema -> {
            put("responseMimeType", "application/json")
            put("responseSchema", geminiSchema(output.schema))
        }
    }
    geminiThinkingConfig(config.reasoning)?.let { put("thinkingConfig", it) }
}

private fun geminiThinkingConfig(reasoning: ReasoningLevel): JsonObject? = when (reasoning) {
    ReasoningLevel.Off -> null
    else -> buildJsonObject {
        put("thinkingBudget", reasoning.defaultGeminiThinkingBudget())
        put("includeThoughts", true)
    }
}

private suspend fun parseGeminiResponse(raw: JsonObject, turn: ProviderTurn<*>): TurnResult {
    val draft = TurnDraft(turn.config.output)
    val candidate = raw["candidates"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
    candidate?.get("content")?.jsonObjectOrNull()?.get("parts")?.jsonArrayOrNull().orEmpty().forEachIndexed { index, partElement ->
        val part = partElement.jsonObjectOrNull() ?: return@forEachIndexed
        part.appendGeminiTextPartTo(draft)
        part["functionCall"]?.jsonObjectOrNull()?.let { functionCall ->
            val name = functionCall.string("name") ?: "function"
            val id = functionCall.string("id") ?: "gemini:$name:$index"
            draft.startTool(id, name, part.geminiThoughtBlackboard())
            draft.appendToolArguments(id, name, (functionCall["args"]?.jsonObjectOrNull() ?: emptyJsonObject()).toString())
            draft.completeTool(id)
        }
    }
    val blackboard = raw.string("responseId")?.let { LlmBlackboard.Empty.with("gemini.response_id", it) } ?: LlmBlackboard.Empty
    return draft.complete(parseGeminiUsage(raw["usageMetadata"]?.jsonObjectOrNull()), TurnTrace(ProviderShape.Gemini, turn.config.model, candidate?.string("finishReason"), raw, blackboard), blackboard)
}

private fun parseGeminiUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    return TokenUsage(usage.int("promptTokenCount"), usage.int("candidatesTokenCount"), usage.int("totalTokenCount"), usage.int("cachedContentTokenCount"), usage.int("thoughtsTokenCount"))
}

private suspend fun JsonObject.appendGeminiTextPartTo(draft: TurnDraft) {
    string("text")?.let { if (boolean("thought") == true) draft.appendReasoning(it, blackboard = geminiThoughtBlackboard()) else draft.appendContent(it) }
    string("thoughtSignature")?.let { draft.appendReasoning("", ReasoningKind.Opaque, LlmBlackboard.Empty.with("gemini.thought_signature", it)) }
}

private fun JsonObject.geminiThoughtBlackboard() = string("thoughtSignature")?.let { LlmBlackboard.Empty.with("gemini.thought_signature", it) } ?: LlmBlackboard.Empty

private fun TurnItem.Reasoning.toGeminiThoughtPart() = buildJsonObject {
    put("thought", true)
    text?.let { put("text", it) }
    blackboard.string("gemini.thought_signature")?.let { put("thoughtSignature", it) }
}

private fun ReasoningLevel.defaultGeminiThinkingBudget() = when (this) {
    ReasoningLevel.Off -> 0
    ReasoningLevel.Minimal -> 512
    ReasoningLevel.Low -> 1024
    ReasoningLevel.Medium -> 4096
    ReasoningLevel.High -> 8192
    ReasoningLevel.Max -> 16000
}
