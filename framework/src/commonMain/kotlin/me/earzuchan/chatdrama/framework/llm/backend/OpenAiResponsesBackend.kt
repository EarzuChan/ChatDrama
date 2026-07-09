package me.earzuchan.chatdrama.framework.llm.backend

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

// TODO：远期尝试支持OpenAI凭证登录
data class OpenAiResponsesBackendConfig(val apiKey: String, val baseUrl: String = "https://api.openai.com/v1", val organization: String? = null, val project: String? = null, val extraHeaders: Map<String, String> = emptyMap())

private data class OpenAiResponsesRoot(val instructions: String? = null, val tools: JsonArray? = null)

private sealed interface OpenAiResponsesContext {
    data class Stateless(val input: List<JsonObject>, val officialCompaction: Boolean = false) : OpenAiResponsesContext
    data class StatefulByPreviousId(val id: String, val shadowInput: List<JsonObject>) : OpenAiResponsesContext
}

private data class OpenAiResponsesLaneB(override val rootRevisionId: LlmNodeId, override val anchorNodeId: LlmNodeId?, override val configKey: ProviderLaneBConfigKey, val root: OpenAiResponsesRoot, val context: OpenAiResponsesContext, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : ProviderLaneB {
    override val shape = ProviderShape.OpenAiResponses
}

private data class OpenAiResponsesReceived(val output: List<JsonObject>, val result: TurnResult)

class OpenAiResponsesBackend(private val config: OpenAiResponsesBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.OpenAiResponses
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput, LlmFeature.Reasoning), setOf(LlmFeature.PromptCaching, LlmFeature.RemoteState))

    override fun rebuildLaneB(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB = OpenAiResponsesLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), openAiResponsesRoot(rootRevision.root), OpenAiResponsesContext.Stateless(projectLaneAToResponsesInput(nodes)), sessionBlackboard.openAiResponsesLaneBlackboard())

    override suspend fun compact(laneB: ProviderLaneB, rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB {
        val lane = laneB as? OpenAiResponsesLaneB ?: return laneB
        var source = lane

        val raw = try {
            postJson(compactUrl(), headers(), compactionBody(source, config))
        } catch (throwable: LlmProviderException) {
            if (!throwable.isOpenAiResponsesPreviousResponseUnsupported() || source.context !is OpenAiResponsesContext.StatefulByPreviousId) throw throwable

            source = source.fallbackFromPreviousResponseUnsupported()
            postJson(compactUrl(), headers(), compactionBody(source, config))
        }

        val stateless = source.toStateless(rootRevision, nodes, config, sessionBlackboard)
        val output = raw.outputItems()
        return if (output.isEmpty()) stateless else stateless.copy(context = OpenAiResponsesContext.Stateless(output, officialCompaction = true), blackboard = stateless.blackboard.withAll(openAiResponsesCompactionBlackboard(raw)))
    }

    override fun debugLaneB(laneB: ProviderLaneB?) = laneB?.let { debugLaneB(it as? OpenAiResponsesLaneB ?: return@let providerLaneBDebug("OpenAiResponsesLaneB(wrong type)", it)) } ?: "OpenAiResponsesLaneB(null)"

    override suspend fun request(providerRequest: ProviderTurnRequest<ProviderLaneB>, mode: RequestMode): ProviderTurnResultCommit<ProviderLaneB> = requestResponses(providerRequest.typedRequest("OpenAI responses") { it as? OpenAiResponsesLaneB }, mode)

    private suspend fun requestResponses(providerRequest: ProviderTurnRequest<OpenAiResponsesLaneB>, mode: RequestMode): ProviderTurnResultCommit<OpenAiResponsesLaneB> {
        try {
            val received = when (mode) {
                RequestMode.Static -> receiveStatic(providerRequest)

                is RequestMode.Streamed -> receiveStream(providerRequest, mode.observer)
            }

            return commitReceived(providerRequest, received)
        } catch (throwable: LlmProviderException) {
            if (!throwable.isOpenAiResponsesPreviousResponseUnsupported() || providerRequest.laneB.context !is OpenAiResponsesContext.StatefulByPreviousId) throw throwable

            // TIPS：以下是处理服务器不支持“服务器状态留存”，退化到关闭服务器状态，重新请求
            return requestResponses(providerRequest.copy(laneB = providerRequest.laneB.fallbackFromPreviousResponseUnsupported()), mode)
        }
    }

    private suspend fun receiveStatic(providerRequest: ProviderTurnRequest<OpenAiResponsesLaneB>) = parseReceived(postJson(responsesUrl(), headers(), responsesBody(providerRequest, streamly = false)), providerRequest)

    private suspend fun receiveStream(providerRequest: ProviderTurnRequest<OpenAiResponsesLaneB>, observer: TurnObserver?): OpenAiResponsesReceived {
        val draft = OpenAiResponsesStreamDraft(providerRequest.config.output, observer)
        var completedRaw: JsonObject? = null

        try {
            postSse(responsesUrl(), headers(), responsesBody(providerRequest, streamly = true)) { event, data ->
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse

                when (event) {
                    "response.output_item.added" -> raw["item"]?.jsonObjectOrNull()?.let { draft.outputItemAdded(raw, it) }
                    "response.output_text.delta", "response.refusal.delta", "response.reasoning_text.delta", "response.reasoning.delta", "response.reasoning_summary_text.delta", "response.function_call_arguments.delta" -> draft.delta(event, raw)
                    "response.completed" -> completedRaw = raw["response"]?.jsonObjectOrNull()
                }
            }

            return draft.complete(completedRaw, providerRequest)
        } catch (throwable: Throwable) {
            if (throwable is LlmProviderException || throwable is LlmTurnException) throw throwable
            throw LlmTurnException(throwable.message ?: "OpenAI responses stream failed", throwable, draft.partial(providerRequest))
        }
    }

    private fun responsesUrl() = "${config.baseUrl.trimEnd('/')}/responses"

    private fun compactUrl() = "${config.baseUrl.trimEnd('/')}/responses/compact"

    private fun headers() = openAiHeaders(config.apiKey, config.organization, config.project, config.extraHeaders)

    private fun debugLaneB(lane: OpenAiResponsesLaneB) = buildString {
        appendLine(providerLaneBDebug("OpenAiResponsesLaneB", lane))
        appendLine("  root.instructions=${lane.root.instructions}")
        appendLine("  root.tools=${lane.root.tools}")
        when (val context = lane.context) {
            is OpenAiResponsesContext.Stateless -> {
                appendLine("  context=Stateless(canonicalCompaction=${context.officialCompaction})")
                appendLine("  input:")
                context.input.forEachIndexed { index, input -> appendLine("    [$index] $input") }
            }

            is OpenAiResponsesContext.StatefulByPreviousId -> appendLine("  context=PreviousResponse(${context.id}, shadowInput=${context.shadowInput.size})")
        }
    }

    private fun responsesBody(providerRequest: ProviderTurnRequest<OpenAiResponsesLaneB>, streamly: Boolean): JsonObject {
        val lane = providerRequest.laneB

        val body = buildJsonObject {
            put("model", providerRequest.config.model)
            lane.root.instructions?.let { put("instructions", it) }

            when (val context = lane.context) {
                is OpenAiResponsesContext.Stateless -> put("input", appendCurrentRequest(context.input, providerRequest.requestNode.request))
                is OpenAiResponsesContext.StatefulByPreviousId -> {
                    put("previous_response_id", context.id)
                    put("input", projectTurnRequestToResponsesInput(providerRequest.requestNode.request).toJsonArray())
                }
            }

            providerRequest.config.temperature?.let { put("temperature", it) }
            if (streamly) put("stream", true)

            lane.root.tools?.takeIf { it.isNotEmpty() }?.let {
                put("tools", it)
                put("parallel_tool_calls", true)
            }
            openAiResponsesTextFormat(providerRequest.config.output)?.let { put("text", buildJsonObject { put("format", it) }) }
            openAiResponsesReasoning(providerRequest.config.reasoning)?.let { put("reasoning", it) }
        }

        return body.merge(providerRequest.config.providerOptions[shape] ?: emptyJsonObject()).withOpenAiResponsesInternalStateOptions(providerRequest.config)
    }

    private fun commitReceived(providerRequest: ProviderTurnRequest<OpenAiResponsesLaneB>, received: OpenAiResponsesReceived): ProviderTurnResultCommit<OpenAiResponsesLaneB> {
        val lane = providerRequest.laneB
        val requestInput = projectTurnRequestToResponsesInput(providerRequest.requestNode.request)
        val responseId = received.result.blackboard.string("openai.responses.response_id")
        val blackboard = lane.blackboard.withAll(received.result.blackboard.onlyPrefixed("openai."))
        val resultBlackboard = received.result.blackboard.withAll(lane.blackboard.onlyPrefixed(OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED))

        val context = when (val current = lane.context) {
            is OpenAiResponsesContext.Stateless -> {
                val shadowInput = current.input + requestInput + received.output
                if (responseId != null && blackboard.string(OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED) != "true") OpenAiResponsesContext.StatefulByPreviousId(responseId, shadowInput) else OpenAiResponsesContext.Stateless(shadowInput, current.officialCompaction)
            }

            is OpenAiResponsesContext.StatefulByPreviousId -> {
                val shadowInput = current.shadowInput + requestInput + received.output
                if (responseId != null && blackboard.string(OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED) != "true") OpenAiResponsesContext.StatefulByPreviousId(responseId, shadowInput) else current.copy(shadowInput = shadowInput)
            }
        }

        val next = lane.copy(anchorNodeId = providerRequest.resultNodeId, context = context, blackboard = blackboard).tidyAfterAppend()
        val result = if (resultBlackboard == received.result.blackboard) received.result else received.result.copy(blackboard = resultBlackboard)
        return ProviderTurnResultCommit(next, result)
    }

    // 有无状态均可压缩
    private fun compactionBody(lane: OpenAiResponsesLaneB, config: EffectiveLlmCallConfig) = buildJsonObject {
        put("model", config.model)
        lane.root.instructions?.let { put("instructions", it) }

        when (val context = lane.context) {
            is OpenAiResponsesContext.Stateless -> put("input", context.input.toJsonArray())
            is OpenAiResponsesContext.StatefulByPreviousId -> put("previous_response_id", context.id)
        }
    }.merge((config.providerOptions[shape] ?: emptyJsonObject()).openAiResponsesCompactOptions())

    private fun OpenAiResponsesLaneB.toStateless(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard) = when (context) {
        is OpenAiResponsesContext.Stateless -> copy(blackboard = sessionBlackboard.openAiResponsesLaneBlackboard())
        is OpenAiResponsesContext.StatefulByPreviousId -> OpenAiResponsesLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), openAiResponsesRoot(rootRevision.root), OpenAiResponsesContext.Stateless(projectLaneAToResponsesInput(nodes)), sessionBlackboard.openAiResponsesLaneBlackboard())
    }

    private fun OpenAiResponsesLaneB.fallbackFromPreviousResponseUnsupported(): OpenAiResponsesLaneB {
        val context = context as? OpenAiResponsesContext.StatefulByPreviousId ?: return this
        return copy(context = OpenAiResponsesContext.Stateless(context.shadowInput), blackboard = blackboard.with(OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED, "true").without("openai.responses.response_id"))
    }
}

private fun openAiResponsesRoot(root: SessionRoot) = OpenAiResponsesRoot(
    instructions = root.instructions.takeIf { it.isNotEmpty() }?.plainText(),
    tools = root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonArray { it.forEach { tool -> add(openAiResponsesTool(tool)) } } }
)

private fun appendCurrentRequest(input: List<JsonObject>, request: TurnRequest) = buildJsonArray {
    input.forEach { add(it) }
    projectTurnRequestToResponsesInput(request).forEach { add(it) }
}

private fun projectLaneAToResponsesInput(nodes: List<SessionNode>) = buildList {
    nodes.forEach { node ->
        when (node) {
            is TurnRequestNode -> addAll(projectTurnRequestToResponsesInput(node.request))
            is TurnResultNode -> addAll(projectTurnResultToResponsesInput(node.result))
        }
    }
}

private fun projectTurnRequestToResponsesInput(request: TurnRequest) = buildList {
    request.items.forEach { item ->
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
}

private fun projectTurnResultToResponsesInput(result: TurnResult): List<JsonObject> = buildList {
    if (!result.isFrom(ProviderShape.OpenAiResponses)) result.previousReasoningText()?.let {
        add(buildJsonObject {
            put("role", "assistant")
            put("content", it)
        })
    }

    result.items.forEach { item ->
        when (item) {
            is TurnItem.Content -> add(buildJsonObject {
                put("role", "assistant")
                put("content", item.asText())
            })

            is TurnItem.ToolCall -> add(buildJsonObject {
                put("type", "function_call")
                put("call_id", item.id)
                put("name", item.name)
                put("arguments", item.arguments.toString())
            })

            is TurnItem.Reasoning -> if (result.isFrom(ProviderShape.OpenAiResponses)) add(item.toOpenAiResponsesReasoningInput()) else Unit
            is TurnItem.Refusal -> item.text?.let {
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", it)
                })
            }
        }
    }
}

private fun TurnItem.Reasoning.toOpenAiResponsesReasoningInput() = buildJsonObject {
    put("type", "reasoning")
    blackboard.string("openai.responses.reasoning_encrypted_content")?.let { put("encrypted_content", it) }
    text?.takeIf { it.isNotBlank() }?.let {
        put("summary", buildJsonArray {
            add(buildJsonObject {
                put("type", "summary_text")
                put("text", it)
            })
        })
    }
}

private fun parseReceived(raw: JsonObject, providerRequest: ProviderTurnRequest<*>): OpenAiResponsesReceived {
    val output = raw.outputItems()
    val blackboard = raw.string("id")?.let { LlmBlackboard.Empty.with("openai.responses.response_id", it) } ?: LlmBlackboard.Empty
    val trace = TurnTrace(ProviderShape.OpenAiResponses, raw.string("model") ?: providerRequest.config.model, raw.string("status"), raw, blackboard)
    return OpenAiResponsesReceived(output, openAiResponsesToTurnResult(raw, providerRequest.config.output, parseOpenAiResponsesUsage(raw["usage"]?.jsonObjectOrNull()), trace, blackboard))
}

private fun openAiResponsesToTurnResult(raw: JsonObject, output: OutputContract, usage: TokenUsage? = null, trace: TurnTrace = TurnTrace(), blackboard: LlmBlackboard = LlmBlackboard.Empty) = TurnResult(buildList {
    raw.outputItems().forEachIndexed { index, item ->
        when (item.string("type")) {
            "message" -> addAll(openAiResponsesMessageItems(item, output))
            "function_call" -> add(openAiResponsesFunctionCallItem(item, index))
            "reasoning" -> openAiResponsesReasoningItem(item)?.let { add(it) }
        }
    }
    raw.string("output_text")?.takeIf { it.isNotBlank() && none { item -> item is TurnItem.Content } }?.let { add(TurnItem.Content(it.toOutputBody(output))) }
}, usage, trace, blackboard)

private fun openAiResponsesMessageItems(item: JsonObject, output: OutputContract) = buildList {
    item["content"]?.jsonArrayOrNull().orEmpty().forEach { contentElement ->
        val content = contentElement.jsonObjectOrNull() ?: return@forEach
        when (content.string("type")) {
            "output_text" -> add(TurnItem.Content(content.string("text").orEmpty().toOutputBody(output)))
            "refusal" -> add(TurnItem.Refusal(content.string("refusal") ?: content.string("text")))
            else -> content.string("text")?.let { add(TurnItem.Content(it.toOutputBody(output))) }
        }
    }
}

private fun openAiResponsesFunctionCallItem(item: JsonObject, index: Int) = TurnItem.ToolCall(item.string("call_id") ?: item.string("id") ?: "call_$index", item.string("name") ?: "function", item.string("arguments").orEmpty().toJsonObjectLenient())

private fun openAiResponsesReasoningItem(item: JsonObject): TurnItem.Reasoning? {
    val blackboard = item.string("encrypted_content")?.let { LlmBlackboard.Empty.with("openai.responses.reasoning_encrypted_content", it) } ?: LlmBlackboard.Empty
    val summaries = item["summary"]?.jsonArrayOrNull().orEmpty().mapNotNull { it.jsonObjectOrNull()?.string("text") }
    if (summaries.isEmpty()) return if (!blackboard.isEmpty) TurnItem.Reasoning(null, ReasoningKind.Opaque, blackboard) else null
    return TurnItem.Reasoning(summaries.joinToString("\n"), ReasoningKind.Summary, blackboard)
}

private fun JsonObject.outputItems() = this["output"]?.jsonArrayOrNull().orEmpty().mapNotNull { it.jsonObjectOrNull() }

// Responses 流式 draft 维护原生 output items；completed response 到达时以服务端总和为准
private class OpenAiResponsesStreamDraft(private val output: OutputContract, private val observer: TurnObserver?) {
    private val items = mutableListOf<JsonObject>()
    private val itemIds = mutableMapOf<String, String>()
    private val itemIndexKeys = mutableMapOf<Int, String>()
    private val completedObserverIds = mutableSetOf<String>()
    private var nextObserverOrdinal = 0

    suspend fun outputItemAdded(raw: JsonObject, item: JsonObject) {
        val index = raw.int("output_index") ?: items.size
        while (items.size <= index) items += buildJsonObject { put("type", "message") }
        items[index] = item
        val key = itemKey(raw, item, index)
        itemIndexKeys[index] = key
        startObserver(key, kindOf(item))
    }

    suspend fun delta(event: String, raw: JsonObject) {
        val index = raw.int("output_index") ?: 0
        while (items.size <= index) items += buildJsonObject { put("type", "message") }
        val item = items[index]
        val key = raw.openAiResponsesToolKey() ?: itemIndexKeys[index] ?: itemKey(raw, item, index)
        itemIndexKeys[index] = key

        when (event) {
            "response.output_text.delta" -> {
                startObserver(key, TurnItemKind.Content)
                val delta = raw.string("delta").orEmpty()
                items[index] = item.appendResponsesMessageContent("output_text", "text", delta)
                if (delta.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(itemIds.getValue(key), TurnItemDelta.Text(delta)))
            }

            "response.refusal.delta" -> {
                startObserver(key, TurnItemKind.Refusal)
                val delta = raw.string("delta").orEmpty()
                items[index] = item.appendResponsesMessageContent("refusal", "refusal", delta)
                if (delta.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(itemIds.getValue(key), TurnItemDelta.Text(delta)))
            }

            "response.reasoning_text.delta", "response.reasoning.delta", "response.reasoning_summary_text.delta" -> {
                startObserver(key, TurnItemKind.Reasoning)
                val delta = raw.string("delta").orEmpty()
                items[index] = item.appendResponsesReasoningSummary(delta)
                if (delta.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(itemIds.getValue(key), TurnItemDelta.Text(delta)))
            }

            "response.function_call_arguments.delta" -> {
                startObserver(key, TurnItemKind.ToolCall)
                val delta = raw.string("delta").orEmpty()
                items[index] = item.appendResponsesFunctionArguments(raw, delta)
                if (delta.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(itemIds.getValue(key), TurnItemDelta.ToolArguments(delta)))
            }
        }
    }

    suspend fun complete(completedRaw: JsonObject?, providerRequest: ProviderTurnRequest<*>): OpenAiResponsesReceived {
        val raw = completedRaw ?: syntheticResponse(providerRequest)
        val received = parseReceived(raw, providerRequest)
        completeObserver(received.result)
        observer?.onEvent(TurnEvent.Completed(received.result))
        return received
    }

    fun partial(providerRequest: ProviderTurnRequest<*>) = openAiResponsesToTurnResult(syntheticResponse(providerRequest), providerRequest.config.output, trace = TurnTrace(ProviderShape.OpenAiResponses, providerRequest.config.model))

    private fun syntheticResponse(providerRequest: ProviderTurnRequest<*>) = buildJsonObject {
        put("model", providerRequest.config.model)
        put("status", "incomplete")
        put("output", buildJsonArray { items.forEach { add(it.normalizedResponsesOutputItem()) } })
    }

    private suspend fun startObserver(key: String, kind: TurnItemKind) {
        if (key in itemIds) return
        val id = "item_${nextObserverOrdinal++}"
        itemIds[key] = id
        observer?.onEvent(TurnEvent.ItemStarted(id, kind))
    }

    private suspend fun completeObserver(result: TurnResult) = result.items.forEachIndexed { index, item ->
        val key = itemIndexKeys[index] ?: index.toString()
        val id = itemIds[key] ?: "item_${nextObserverOrdinal++}".also {
            itemIds[key] = it
            observer?.onEvent(TurnEvent.ItemStarted(it, item.kind()))
        }

        if (completedObserverIds.add(id)) observer?.onEvent(TurnEvent.ItemCompleted(id, item))
    }

    private fun itemKey(raw: JsonObject, item: JsonObject, index: Int) = listOfNotNull(raw.string("call_id"), raw.string("item_id"), item.string("call_id"), item.string("id"), raw.int("output_index")?.toString()).firstOrNull() ?: index.toString()
    private fun kindOf(item: JsonObject) = when (item.string("type")) {
        "function_call" -> TurnItemKind.ToolCall
        "reasoning" -> TurnItemKind.Reasoning
        else -> TurnItemKind.Content
    }
}

private fun JsonObject.appendResponsesMessageContent(type: String, field: String, delta: String) = buildJsonObject {
    val current = this@appendResponsesMessageContent["content"]?.jsonArrayOrNull().orEmpty()
    put("type", string("type") ?: "message")
    put("role", string("role") ?: "assistant")
    put("content", buildJsonArray {
        val targetIndex = current.indexOfLast { it.jsonObjectOrNull()?.string("type") == type }
        current.forEachIndexed { index, element ->
            val obj = element.jsonObjectOrNull()
            if (index == targetIndex && obj != null) add(obj.withStringAppended(field, delta)) else add(element)
        }
        if (targetIndex < 0) add(buildJsonObject {
            put("type", type)
            put(field, delta)
        })
    })
}

private fun JsonObject.appendResponsesReasoningSummary(delta: String) = buildJsonObject {
    this@appendResponsesReasoningSummary.forEach { (key, value) -> put(key, value) }
    if (!containsKey("type")) put("type", "reasoning")
    put("summary", buildJsonArray {
        val current = this@appendResponsesReasoningSummary["summary"]?.jsonArrayOrNull().orEmpty()
        if (current.isEmpty()) add(buildJsonObject {
            put("type", "summary_text")
            put("text", delta)
        }) else current.forEachIndexed { index, element ->
            val obj = element.jsonObjectOrNull()
            if (index == current.lastIndex && obj != null) add(obj.withStringAppended("text", delta)) else add(element)
        }
    })
}

private fun JsonObject.appendResponsesFunctionArguments(raw: JsonObject, delta: String) = buildJsonObject {
    put("type", "function_call")
    put("call_id", string("call_id") ?: raw.string("call_id") ?: raw.string("item_id") ?: raw.int("output_index")?.let { "call_$it" } ?: "call")
    put("name", string("name") ?: raw.string("name") ?: "function")
    put("arguments", string("arguments").orEmpty() + delta)
}

private fun JsonObject.withStringAppended(field: String, delta: String) = buildJsonObject {
    this@withStringAppended.forEach { (key, value) -> put(key, value) }
    put(field, string(field).orEmpty() + delta)
}

private fun JsonObject.normalizedResponsesOutputItem() = when (string("type")) {
    "function_call" -> buildJsonObject {
        put("type", "function_call")
        put("call_id", string("call_id") ?: string("id") ?: "call")
        put("name", string("name") ?: "function")
        put("arguments", string("arguments").orEmpty())
    }

    "reasoning" -> this
    else -> buildJsonObject {
        put("type", "message")
        put("role", string("role") ?: "assistant")
        put("content", this@normalizedResponsesOutputItem["content"]?.jsonArrayOrNull() ?: buildJsonArray {})
    }
}

private fun openAiResponsesCompactionBlackboard(raw: JsonObject) = raw.string("id")?.let { LlmBlackboard.Empty.with("openai.responses.compaction_id", it) } ?: LlmBlackboard.Empty

private fun OpenAiResponsesLaneB.tidyAfterAppend() = when (val context = context) {
    is OpenAiResponsesContext.Stateless -> copy(context = context.copy(input = context.input.tidyLatestOpenAiResponsesWave()))
    is OpenAiResponsesContext.StatefulByPreviousId -> copy(context = context.copy(shadowInput = context.shadowInput.tidyLatestOpenAiResponsesWave()))
}

private data class OpenAiResponsesWaveRange(val start: Int, val endExclusive: Int)

private fun List<JsonObject>.tidyLatestOpenAiResponsesWave(): List<JsonObject> {
    val range = latestOpenAiResponsesWaveRange() ?: return this
    val tidied = subList(range.start, range.endExclusive).tidiedOpenAiResponsesWave()
    if (tidied == subList(range.start, range.endExclusive)) return this
    return take(range.start) + tidied + drop(range.endExclusive)
}

private fun List<JsonObject>.latestOpenAiResponsesWaveRange(): OpenAiResponsesWaveRange? {
    var endExclusive = size
    while (endExclusive > 0 && this[endExclusive - 1].isOpenAiResponsesCompaction()) endExclusive--
    if (endExclusive == 0) return null

    var promptIndex = -1
    for (index in endExclusive - 1 downTo 0) {
        val item = this[index]
        if (item.isOpenAiResponsesCompaction()) break
        if (item.isOpenAiResponsesPrompt()) {
            promptIndex = index
            break
        }
    }

    if (promptIndex < 0) return null
    while (promptIndex > 0 && this[promptIndex - 1].isOpenAiResponsesPrompt()) promptIndex--
    return OpenAiResponsesWaveRange(promptIndex, endExclusive)
}

private fun List<JsonObject>.tidiedOpenAiResponsesWave(): List<JsonObject> {
    val last = lastOrNull() ?: return this
    if (!last.isOpenAiResponsesFinalMessage()) return this
    val promptCount = takeWhile { it.isOpenAiResponsesPrompt() }.size
    if (promptCount == 0) return this
    val outputs = drop(promptCount)
    val lastToolishIndex = outputs.indexOfLast { it.isOpenAiResponsesToolish() }
    val finalMessages = outputs.drop(lastToolishIndex + 1).filter { it.isOpenAiResponsesFinalMessage() }.takeIf { it.isNotEmpty() } ?: outputs.filter { it.isOpenAiResponsesFinalMessage() }
    val nonCommentary = finalMessages.filter { it.string("phase") != "commentary" }
    return take(promptCount) + (nonCommentary.ifEmpty { finalMessages })
}

private fun JsonObject.isOpenAiResponsesPrompt() = string("role") == "user" && string("type") != "function_call_output" && string("type") != "computer_call_output" && !hasOpenAiResponsesContentType("function_call_output")

private fun JsonObject.isOpenAiResponsesFinalMessage() = string("type").let { it == null || it == "message" } && string("role") == "assistant" && !containsKey("call_id") && hasOpenAiResponsesVisibleContent()

private fun JsonObject.hasOpenAiResponsesVisibleContent(): Boolean {
    string("content")?.takeIf { it.isNotBlank() }?.let { return true }
    return this["content"]?.jsonArrayOrNull().orEmpty().any { element ->
        val content = element.jsonObjectOrNull() ?: return@any false
        content.string("type") in setOf("output_text", "refusal") || content.string("text")?.isNotBlank() == true || content.string("refusal")?.isNotBlank() == true
    }
}

private fun JsonObject.hasOpenAiResponsesContentType(type: String) = this["content"]?.jsonArrayOrNull().orEmpty().any { it.jsonObjectOrNull()?.string("type") == type }

private fun JsonObject.isOpenAiResponsesCompaction() = string("type") == "compaction" || string("type") == "compaction_trigger"

private fun JsonObject.isOpenAiResponsesToolish() = string("type")?.contains("call") == true || containsKey("call_id")

private fun Iterable<JsonElement>.toJsonArray() = buildJsonArray { forEach { add(it) } }

private fun openAiResponsesInputContent(parts: List<ContentPart>): JsonElement = if (parts.all { it is ContentPart.Text }) JsonPrimitive(parts.plainText())
else buildJsonArray {
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

private fun openAiResponsesTool(tool: ToolDefinition) = buildJsonObject {
    put("type", "function")
    put("name", tool.name)
    tool.description?.let { put("description", it) }
    put("parameters", tool.inputSchema(tool.strict))
    put("strict", tool.strict)
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

private fun openAiResponsesReasoning(reasoning: ReasoningLevel): JsonObject? = when (reasoning) {
    ReasoningLevel.Off -> null

    else -> buildJsonObject {
        put("effort", if (reasoning == ReasoningLevel.Max) "xhigh" else openAiReasoningEffort(reasoning) ?: "medium")
        put("summary", "auto")
    }
}

private fun JsonObject.withOpenAiResponsesInternalStateOptions(config: EffectiveLlmCallConfig) = buildJsonObject {
    this@withOpenAiResponsesInternalStateOptions.forEach { (key, value) -> if (key != "include" && key != "store") put(key, value) }
    val include = (this@withOpenAiResponsesInternalStateOptions["include"]?.jsonArrayOrNull().orEmpty() + openAiResponsesInternalInclude(config)).distinct()
    if (include.isNotEmpty()) put("include", include.toJsonArray())
    put("store", true)
}

private fun openAiResponsesInternalInclude(config: EffectiveLlmCallConfig) = if (config.reasoning == ReasoningLevel.Off) emptyList() else listOf(JsonPrimitive("reasoning.encrypted_content"))

private fun JsonObject.openAiResponsesCompactOptions() = buildJsonObject { listOf("include", "metadata", "service_tier", "user").forEach { key -> this@openAiResponsesCompactOptions[key]?.let { put(key, it) } } }

private fun LlmBlackboard.openAiResponsesLaneBlackboard() = onlyPrefixed("openai.").without("openai.responses.response_id")

private const val OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED = "openai.responses.previous_response_id_unsupported"

// CHECK：这个有点硬核
private fun LlmProviderException.isOpenAiResponsesPreviousResponseUnsupported() = body.contains("previous_response_id") && (body.contains("unsupported", ignoreCase = true) || body.contains("only supported", ignoreCase = true))

private fun JsonObject.openAiResponsesToolKey() = string("call_id") ?: string("item_id") ?: int("output_index")?.toString()

private fun parseOpenAiResponsesUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null

    val inputDetails = usage["input_tokens_details"]?.jsonObjectOrNull()
    val outputDetails = usage["output_tokens_details"]?.jsonObjectOrNull()
    return TokenUsage(usage.int("input_tokens"), usage.int("output_tokens"), usage.int("total_tokens"), inputDetails?.int("cached_tokens"), outputDetails?.int("reasoning_tokens"))
}
