package me.earzuchan.chatdrama.framework.llm.backend

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

data class GeminiBackendConfig(val apiKey: String, val baseUrl: String = "https://generativelanguage.googleapis.com", val apiVersion: String = "v1beta", val extraHeaders: Map<String, String> = emptyMap())

private data class GeminiRoot(val systemInstruction: JsonObject? = null, val tools: JsonArray? = null, val toolConfig: JsonObject? = null)

private data class GeminiLaneB(override val rootRevisionId: LlmNodeId, override val anchorNodeId: LlmNodeId?, override val configKey: ProviderLaneBConfigKey, val root: GeminiRoot, val contents: List<JsonObject>, val textualizedToolIds: Set<String> = emptySet(), override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : ProviderLaneB {
    override val shape = ProviderShape.Gemini
}

private data class GeminiReceived(val nativeContent: JsonObject?, val result: TurnResult)

class GeminiBackend(private val config: GeminiBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.Gemini
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput, LlmFeature.Reasoning), setOf(LlmFeature.PromptCaching, LlmFeature.RemoteState))

    override fun rebuildLaneB(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB {
        val projection = projectThinLaneAToGemini(nodes)
        return GeminiLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), geminiRoot(rootRevision.root), projection.contents, projection.textualizedToolIds, sessionBlackboard.onlyPrefixed("gemini."))
    }

    override fun debugLaneB(laneB: ProviderLaneB?) = laneB?.let { debugLaneB(it as? GeminiLaneB ?: return@let providerLaneBDebug("GeminiLaneB(wrong type)", it)) } ?: "GeminiLaneB(null)"

    override suspend fun request(providerRequest: ProviderTurnRequest<ProviderLaneB>, mode: RequestMode): ProviderTurnResultCommit<ProviderLaneB> = performRequest(providerRequest.typedRequest("Gemini") { it as? GeminiLaneB }, mode)

    private suspend fun performRequest(providerRequest: ProviderTurnRequest<GeminiLaneB>, mode: RequestMode): ProviderTurnResultCommit<GeminiLaneB> {
        val received = when (mode) {
            RequestMode.Static -> receiveStatic(providerRequest)

            is RequestMode.Streamed -> receiveStream(providerRequest, mode.observer)
        }

        return commitReceived(providerRequest, received)
    }

    private suspend fun receiveStatic(providerRequest: ProviderTurnRequest<GeminiLaneB>) = parseGeminiReceived(postJson(geminiUrl(providerRequest.config.model, stream = false), headers(), geminiBody(providerRequest)), providerRequest)

    private suspend fun receiveStream(providerRequest: ProviderTurnRequest<GeminiLaneB>, observer: TurnObserver?): GeminiReceived {
        val draft = GeminiGenerateContentResponseDraft(providerRequest.config.output, observer)

        try {
            postSse(geminiUrl(providerRequest.config.model, stream = true), headers(), geminiBody(providerRequest)) { _, data ->
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse
                draft.chunk(raw)
            }

            return draft.complete(providerRequest.config.model)
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: "Gemini stream failed", throwable, draft.partial(providerRequest.config.model))
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

    private fun geminiBody(providerRequest: ProviderTurnRequest<GeminiLaneB>): JsonObject {
        val lane = providerRequest.laneB
        val body = buildJsonObject {
            put("contents", geminiContentsForSending(lane, providerRequest.requestNode.request))
            lane.root.systemInstruction?.let { put("systemInstruction", it) }
            geminiGenerationConfig(providerRequest.config).takeIf { it.isNotEmpty() }?.let { put("generationConfig", it) }
            lane.root.tools?.takeIf { it.isNotEmpty() }?.let { put("tools", it) }
            lane.root.toolConfig?.let { put("toolConfig", it) }
        }
        return body.merge(providerRequest.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun commitReceived(providerRequest: ProviderTurnRequest<GeminiLaneB>, received: GeminiReceived): ProviderTurnResultCommit<GeminiLaneB> {
        val lane = providerRequest.laneB
        val textualizedToolIds = lane.textualizedToolIds.toMutableSet()
        val contents = buildList {
            addAll(lane.contents)
            addAll(projectTurnRequestToGeminiContents(providerRequest.requestNode.request, textualizedToolIds))
            received.nativeContent?.let { add(it) }
        }
        val blackboard = lane.blackboard.withAll(received.result.blackboard.onlyPrefixed("gemini."))
        val next = lane.copy(anchorNodeId = providerRequest.resultNodeId, contents = contents, textualizedToolIds = textualizedToolIds, blackboard = blackboard).tidyAfterAppend()
        return ProviderTurnResultCommit(next, received.result)
    }
}

private fun geminiRoot(root: SessionRoot) = GeminiRoot(
    systemInstruction = root.instructions.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("parts", geminiParts(it)) } },
    tools = root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonArray { add(buildJsonObject { put("functionDeclarations", buildJsonArray { it.forEach { tool -> add(geminiFunctionDeclaration(tool)) } }) }) } },
    toolConfig = root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("functionCallingConfig", buildJsonObject { put("mode", "AUTO") }) } }
)

private fun geminiContentsForSending(lane: GeminiLaneB, request: TurnRequest) = buildJsonArray {
    lane.contents.forEach { add(it) }
    projectTurnRequestToGeminiContents(request, lane.textualizedToolIds.toMutableSet()).forEach { add(it) }
}

private data class GeminiProjection(val contents: List<JsonObject>, val textualizedToolIds: Set<String>)

// 重建路径只消费已经 thin 过的 LaneA；本家尽量恢复，外家/无签名工具链乔装成文本
private fun projectThinLaneAToGemini(nodes: List<SessionNode>): GeminiProjection {
    val textualizedToolIds = mutableSetOf<String>()
    val contents = buildList {
        nodes.forEach { node ->
            when (node) {
                is TurnRequestNode -> addAll(projectTurnRequestToGeminiContents(node.request, textualizedToolIds))
                is TurnResultNode -> add(projectTurnResultToGeminiContent(node.result, textualizedToolIds))
            }
        }
    }
    return GeminiProjection(contents, textualizedToolIds)
}

private fun projectTurnRequestToGeminiContents(request: TurnRequest, textualizedToolIds: MutableSet<String> = mutableSetOf()) = buildList {
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

private fun projectTurnResultToGeminiContent(result: TurnResult, textualizedToolIds: MutableSet<String> = mutableSetOf()): JsonObject {
    if (!result.hasGeminiNativeToolChain() && result.hasToolCalls()) {
        result.items.filterIsInstance<TurnItem.ToolCall>().forEach { textualizedToolIds += it.id }
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

private fun TurnResult.hasGeminiNativeToolChain() = isFrom(ProviderShape.Gemini) && items.filterIsInstance<TurnItem.ToolCall>().all { it.blackboard.string("gemini.thought_signature") != null }

private fun parseGeminiReceived(raw: JsonObject, providerRequest: ProviderTurnRequest<*>): GeminiReceived {
    val candidate = raw.geminiPrimaryCandidate()
    val nativeContent = candidate?.geminiCandidateContent()
    val blackboard = raw.string("responseId")?.let { LlmBlackboard.Empty.with("gemini.response_id", it) } ?: LlmBlackboard.Empty
    val trace = TurnTrace(ProviderShape.Gemini, providerRequest.config.model, candidate?.string("finishReason"), raw, blackboard)
    return GeminiReceived(nativeContent, geminiResponseToTurnResult(raw, providerRequest.config.model, providerRequest.config.output, trace, blackboard))
}

private fun geminiResponseToTurnResult(raw: JsonObject, model: String, output: OutputContract, trace: TurnTrace = TurnTrace(ProviderShape.Gemini, model), blackboard: LlmBlackboard = LlmBlackboard.Empty) = TurnResult(geminiResponseItems(raw, output), parseGeminiUsage(raw["usageMetadata"]?.jsonObjectOrNull()), trace, blackboard)

private fun geminiResponseItems(raw: JsonObject, output: OutputContract) = buildList {
    raw.geminiPrimaryCandidate()?.geminiCandidateContent()?.geminiContentParts().orEmpty().forEachIndexed { index, part ->
        part.geminiTextItem(output)?.let { add(it) }
        part["functionCall"]?.jsonObjectOrNull()?.let { functionCall ->
            val name = functionCall.string("name") ?: "function"
            val id = functionCall.string("id") ?: "gemini:$name:$index"
            add(TurnItem.ToolCall(id, name, functionCall["args"]?.jsonObjectOrNull() ?: emptyJsonObject(), part.geminiThoughtBlackboard()))
        }
    }
}

private fun JsonObject.geminiPrimaryCandidate() = this["candidates"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()

private fun JsonObject.geminiCandidateContent() = this["content"]?.jsonObjectOrNull()?.normalizeGeminiModelContent()

private fun JsonObject.geminiCandidateParts() = geminiCandidateContent()?.geminiContentParts().orEmpty()

private fun JsonObject.geminiContentParts() = this["parts"]?.jsonArrayOrNull().orEmpty().mapNotNull { it.jsonObjectOrNull() }

private fun JsonObject.normalizeGeminiModelContent(): JsonObject? {
    val parts = this["parts"]?.jsonArrayOrNull() ?: return null
    return buildJsonObject {
        this@normalizeGeminiModelContent.forEach { (key, value) -> put(key, value) }
        if (string("role") == null) put("role", "model")
        put("parts", parts)
    }
}

// Gemini 流式 draft 以完整 GenerateContentResponse 为原生事实源
private class GeminiGenerateContentResponseDraft(private val output: OutputContract, private val observer: TurnObserver?) {
    private val parts = mutableListOf<JsonObject>()
    private var usage: JsonObject? = null
    private var finishReason: String? = null
    private var responseId: String? = null
    private var nextObserverOrdinal = 0
    private val itemIds = mutableMapOf<Int, String>()
    private val completedIds = mutableSetOf<String>()

    suspend fun chunk(raw: JsonObject) {
        responseId = raw.string("responseId") ?: responseId
        raw["usageMetadata"]?.jsonObjectOrNull()?.let { usage = it }
        raw.geminiPrimaryCandidate()?.string("finishReason")?.let { finishReason = it }
        raw.geminiPrimaryCandidate()?.geminiCandidateParts().orEmpty().forEach { part -> addPart(part) }
    }

    suspend fun complete(model: String): GeminiReceived {
        val raw = response()
        val blackboard = responseId?.let { LlmBlackboard.Empty.with("gemini.response_id", it) } ?: LlmBlackboard.Empty
        val trace = TurnTrace(ProviderShape.Gemini, model, finishReason, raw, blackboard)
        val result = geminiResponseToTurnResult(raw, model, output, trace, blackboard)
        completeObserver(result)
        observer?.onEvent(TurnEvent.Completed(result))
        return GeminiReceived(raw.geminiPrimaryCandidate()?.geminiCandidateContent(), result)
    }

    fun partial(model: String) = geminiResponseToTurnResult(response(), model, output, TurnTrace(ProviderShape.Gemini, model, finishReason), responseId?.let { LlmBlackboard.Empty.with("gemini.response_id", it) } ?: LlmBlackboard.Empty)

    private suspend fun addPart(part: JsonObject) {
        val index = parts.lastIndex.takeIf { it >= 0 && parts[it].canMergeGeminiTextDelta(part) }

        if (index != null) {
            parts[index] = parts[index].mergeGeminiTextDelta(part)
            emitDelta(index, part)
        } else {
            parts += part
            val newIndex = parts.lastIndex
            startObserver(newIndex, part)
            emitDelta(newIndex, part)
        }
    }

    private fun response() = buildJsonObject {
        responseId?.let { put("responseId", it) }
        put("candidates", buildJsonArray {
            add(buildJsonObject {
                finishReason?.let { put("finishReason", it) }
                put("content", content())
            })
        })
        usage?.let { put("usageMetadata", it) }
    }

    private fun content() = buildJsonObject {
        put("role", "model")
        put("parts", JsonArray(parts))
    }

    private suspend fun startObserver(index: Int, part: JsonObject) {
        val id = itemIds.getOrPut(index) { "item_${nextObserverOrdinal++}" }
        val kind = if (part["functionCall"] != null) TurnItemKind.ToolCall else if (part.boolean("thought") == true) TurnItemKind.Reasoning else TurnItemKind.Content
        observer?.onEvent(TurnEvent.ItemStarted(id, kind))
    }

    private suspend fun emitDelta(index: Int, part: JsonObject) {
        val id = itemIds.getValue(index)
        part.string("text")?.takeIf { it.isNotEmpty() }?.let { observer?.onEvent(TurnEvent.ItemDelta(id, TurnItemDelta.Text(it))) }
        part["functionCall"]?.jsonObjectOrNull()?.get("args")?.jsonObjectOrNull()?.toString()?.takeIf { it != "{}" }?.let { observer?.onEvent(TurnEvent.ItemDelta(id, TurnItemDelta.ToolArguments(it))) }
    }

    private suspend fun completeObserver(result: TurnResult) = result.items.forEachIndexed { index, item ->
        val id = itemIds[index] ?: "item_${nextObserverOrdinal++}".also { observer?.onEvent(TurnEvent.ItemStarted(it, item.kind())) }
        if (completedIds.add(id)) observer?.onEvent(TurnEvent.ItemCompleted(id, item))
    }
}

private fun JsonObject.geminiTextItem(output: OutputContract): TurnItem? {
    val blackboard = geminiThoughtBlackboard()
    val text = string("text")

    return when {
        !text.isNullOrEmpty() && boolean("thought") == true -> TurnItem.Reasoning(text, blackboard = blackboard)
        !text.isNullOrEmpty() -> TurnItem.Content(text.toOutputBody(output))
        !blackboard.isEmpty -> TurnItem.Reasoning(null, ReasoningKind.Opaque, blackboard)
        else -> null
    }
}

private fun JsonObject.canMergeGeminiTextDelta(other: JsonObject): Boolean {
    if (!keys.all { it in GEMINI_TEXT_PART_KEYS } || !other.keys.all { it in GEMINI_TEXT_PART_KEYS }) return false
    if (string("text") == null || other.string("text") == null) return false
    return boolean("thought") == other.boolean("thought") && string("thoughtSignature") == other.string("thoughtSignature")
}

private fun JsonObject.mergeGeminiTextDelta(other: JsonObject) = buildJsonObject {
    put("text", string("text").orEmpty() + other.string("text").orEmpty())
    boolean("thought")?.let { put("thought", it) }
    string("thoughtSignature")?.let { put("thoughtSignature", it) }
}

private val GEMINI_TEXT_PART_KEYS = setOf("text", "thought", "thoughtSignature")

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

private fun parseGeminiUsage(usage: JsonObject?) = if (usage == null) null
else TokenUsage(usage.int("promptTokenCount"), usage.int("candidatesTokenCount"), usage.int("totalTokenCount"), usage.int("cachedContentTokenCount"), usage.int("thoughtsTokenCount"))

private fun JsonObject.geminiThoughtBlackboard() = string("thoughtSignature")?.let { LlmBlackboard.Empty.with("gemini.thought_signature", it) } ?: LlmBlackboard.Empty

private fun TurnItem.Reasoning.toGeminiThoughtPart() = buildJsonObject {
    put("thought", true)
    text?.let { put("text", it) }
    blackboard.string("gemini.thought_signature")?.let { put("thoughtSignature", it) }
}

private fun TurnResult.hasToolCalls() = items.any { it is TurnItem.ToolCall }

private fun ReasoningLevel.defaultGeminiThinkingBudget() = when (this) {
    ReasoningLevel.Off -> 0
    ReasoningLevel.Minimal -> 512
    ReasoningLevel.Low -> 1024
    ReasoningLevel.Medium -> 4096
    ReasoningLevel.High -> 8192
    ReasoningLevel.Max -> 16000
}
