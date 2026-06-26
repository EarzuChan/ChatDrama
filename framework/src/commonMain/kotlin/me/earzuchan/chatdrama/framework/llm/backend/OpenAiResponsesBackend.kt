package me.earzuchan.chatdrama.framework.llm.backend

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

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

    override suspend fun request(turn: ProviderTurn<ProviderLaneB>, mode: RequestMode): ProviderTurnCommit<ProviderLaneB> = requestResponses(turn.typedTurn("OpenAI responses") { it as? OpenAiResponsesLaneB }, mode)

    private suspend fun requestResponses(turn: ProviderTurn<OpenAiResponsesLaneB>, mode: RequestMode): ProviderTurnCommit<OpenAiResponsesLaneB> {
        try {
            val received = when (mode) {
                RequestMode.Static -> receiveStatic(turn)

                is RequestMode.Streamed -> receiveStream(turn, mode.observer)
            }

            return commitReceived(turn, received)
        } catch (throwable: LlmProviderException) {
            if (!throwable.isOpenAiResponsesPreviousResponseUnsupported() || turn.laneB.context !is OpenAiResponsesContext.StatefulByPreviousId) throw throwable

            // TIPS：以下是处理服务器不支持“服务器状态留存”，退化到关闭服务器状态，重新请求
            return requestResponses(turn.copy(laneB = turn.laneB.fallbackFromPreviousResponseUnsupported()), mode)
        }
    }

    private suspend fun receiveStatic(turn: ProviderTurn<OpenAiResponsesLaneB>) = parseReceived(postJson(responsesUrl(), headers(), responsesBody(turn, streamly = false)), turn)

    private suspend fun receiveStream(turn: ProviderTurn<OpenAiResponsesLaneB>, observer: TurnObserver?): OpenAiResponsesReceived {
        val draft = TurnDraft(turn.config.output, observer)
        val tools = mutableMapOf<String, Pair<String, String>>()
        var completedRaw: JsonObject? = null

        try {
            postSse(responsesUrl(), headers(), responsesBody(turn, streamly = true)) { event, data ->
                val raw = parseJsonElementOrNull(data)?.jsonObjectOrNull() ?: return@postSse
                when (event) {
                    "response.output_text.delta" -> draft.appendContent(raw.string("delta").orEmpty())

                    "response.refusal.delta" -> draft.appendRefusal(raw.string("delta"))

                    "response.reasoning_text.delta", "response.reasoning.delta", "response.reasoning_summary_text.delta" -> draft.appendReasoning(raw.string("delta").orEmpty())

                    "response.output_item.added" -> raw["item"]?.jsonObjectOrNull()?.let { item ->
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

            val raw = completedRaw ?: throw LlmTurnException("OpenAI responses stream ended without response.completed.", partial = draft.partial(trace = TurnTrace(shape, turn.config.model)))
            val received = parseReceived(raw, turn, if (draft.isEmpty()) observer else null)

            if (!draft.isEmpty()) draft.completeWith(received.result)
            return received
        } catch (throwable: Throwable) {
            if (throwable is LlmProviderException || throwable is LlmTurnException) throw throwable
            throw LlmTurnException(throwable.message ?: "OpenAI responses stream failed", throwable, draft.partial(trace = TurnTrace(shape, turn.config.model)))
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

    private fun responsesBody(turn: ProviderTurn<OpenAiResponsesLaneB>, streamly: Boolean): JsonObject {
        val lane = turn.laneB

        val body = buildJsonObject {
            put("model", turn.config.model)
            lane.root.instructions?.let { put("instructions", it) }

            when (val context = lane.context) {
                is OpenAiResponsesContext.Stateless -> put("input", appendCurrentRequest(context.input, turn.requestNode.request))
                is OpenAiResponsesContext.StatefulByPreviousId -> {
                    put("previous_response_id", context.id)
                    put("input", projectTurnRequestToResponsesInput(turn.requestNode.request).toJsonArray())
                }
            }

            turn.config.temperature?.let { put("temperature", it) }
            if (streamly) put("stream", true)

            lane.root.tools?.takeIf { it.isNotEmpty() }?.let {
                put("tools", it)
                put("parallel_tool_calls", true)
            }
            openAiResponsesTextFormat(turn.config.output)?.let { put("text", buildJsonObject { put("format", it) }) }
            openAiResponsesReasoning(turn.config.reasoning)?.let { put("reasoning", it) }
        }

        return body.merge(turn.config.providerOptions[shape] ?: emptyJsonObject()).withOpenAiResponsesInternalStateOptions(turn.config)
    }

    private fun commitReceived(turn: ProviderTurn<OpenAiResponsesLaneB>, received: OpenAiResponsesReceived): ProviderTurnCommit<OpenAiResponsesLaneB> {
        val lane = turn.laneB
        val requestInput = projectTurnRequestToResponsesInput(turn.requestNode.request)
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

        val next = lane.copy(anchorNodeId = turn.resultNodeId, context = context, blackboard = blackboard).tidyAfterAppend()
        val result = if (resultBlackboard == received.result.blackboard) received.result else received.result.copy(blackboard = resultBlackboard)
        return ProviderTurnCommit(next, result)
    }

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
    if (!result.isFrom(ProviderShape.OpenAiResponses)) result.previousReasoningText()?.let { add(buildJsonObject {
        put("role", "assistant")
        put("content", it)
    }) }

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
            is TurnItem.Refusal -> item.text?.let { add(buildJsonObject {
                put("role", "assistant")
                put("content", it)
            }) }
        }
    }
}

private fun TurnItem.Reasoning.toOpenAiResponsesReasoningInput() = buildJsonObject {
    put("type", "reasoning")
    blackboard.string("openai.responses.reasoning_encrypted_content")?.let { put("encrypted_content", it) }
    text?.takeIf { it.isNotBlank() }?.let { put("summary", buildJsonArray { add(buildJsonObject {
        put("type", "summary_text")
        put("text", it)
    }) }) }
}

private suspend fun parseReceived(raw: JsonObject, turn: ProviderTurn<*>, observer: TurnObserver? = null): OpenAiResponsesReceived {
    val output = raw.outputItems()
    // println("恩情难下：$output")
    val draft = TurnDraft(turn.config.output, observer)

    output.forEachIndexed { index, item ->
        when (item.string("type")) {
            "message" -> parseMessageItem(item, draft)
            "function_call" -> parseFunctionCallItem(item, index, draft)
            "reasoning" -> parseReasoningItem(item, draft)
        }
    }
    raw.string("output_text")?.takeIf { it.isNotBlank() && draft.partial().items.none { item -> item is TurnItem.Content } }?.let { draft.appendContent(it) }

    val blackboard = raw.string("id")?.let { LlmBlackboard.Empty.with("openai.responses.response_id", it) } ?: LlmBlackboard.Empty
    val trace = TurnTrace(ProviderShape.OpenAiResponses, raw.string("model") ?: turn.config.model, raw.string("status"), raw, blackboard)
    return OpenAiResponsesReceived(output, draft.complete(parseOpenAiResponsesUsage(raw["usage"]?.jsonObjectOrNull()), trace, blackboard))
}

private suspend fun parseMessageItem(item: JsonObject, draft: TurnDraft) {
    item["content"]?.jsonArrayOrNull().orEmpty().forEach { contentElement ->
        val content = contentElement.jsonObjectOrNull() ?: return@forEach
        when (content.string("type")) {
            "output_text" -> draft.appendContent(content.string("text").orEmpty())
            "refusal" -> draft.appendRefusal(content.string("refusal") ?: content.string("text"))
            else -> content.string("text")?.let { draft.appendContent(it) }
        }
    }
}

private suspend fun parseFunctionCallItem(item: JsonObject, index: Int, draft: TurnDraft) {
    val id = item.string("call_id") ?: item.string("id") ?: "call_$index"
    val name = item.string("name") ?: "function"
    draft.startTool(id, name)
    draft.appendToolArguments(id, name, item.string("arguments").orEmpty())
    draft.completeTool(id)
}

private suspend fun parseReasoningItem(item: JsonObject, draft: TurnDraft) {
    val blackboard = item.string("encrypted_content")?.let { LlmBlackboard.Empty.with("openai.responses.reasoning_encrypted_content", it) } ?: LlmBlackboard.Empty
    val summaries = item["summary"]?.jsonArrayOrNull().orEmpty().mapNotNull { it.jsonObjectOrNull()?.string("text") }
    if (summaries.isEmpty()) {
        if (!blackboard.isEmpty) draft.appendReasoning("", ReasoningKind.Opaque, blackboard)
        return
    }
    summaries.forEachIndexed { index, text -> draft.appendReasoning(text, ReasoningKind.Summary, if (index == 0) blackboard else LlmBlackboard.Empty) }
}

private fun JsonObject.outputItems() = this["output"]?.jsonArrayOrNull().orEmpty().mapNotNull { it.jsonObjectOrNull() }

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

private fun JsonObject.openAiResponsesCompactOptions() = buildJsonObject {
    listOf("include", "metadata", "service_tier", "user").forEach { key -> this@openAiResponsesCompactOptions[key]?.let { put(key, it) } }
}

private fun LlmBlackboard.openAiResponsesLaneBlackboard() = onlyPrefixed("openai.").without("openai.responses.response_id")

private const val OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED = "openai.responses.previous_response_id_unsupported"

private fun LlmProviderException.isOpenAiResponsesPreviousResponseUnsupported() = body.contains("previous_response_id") && (body.contains("unsupported", ignoreCase = true) || body.contains("only supported", ignoreCase = true))

private fun JsonObject.openAiResponsesToolKey() = string("call_id") ?: string("item_id") ?: int("output_index")?.toString()

private fun JsonObject.openAiResponsesToolKeys(item: JsonObject) = listOfNotNull(string("call_id"), string("item_id"), item.string("call_id"), item.string("id"), int("output_index")?.toString()).distinct()

private fun parseOpenAiResponsesUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    val inputDetails = usage["input_tokens_details"]?.jsonObjectOrNull()
    val outputDetails = usage["output_tokens_details"]?.jsonObjectOrNull()
    return TokenUsage(usage.int("input_tokens"), usage.int("output_tokens"), usage.int("total_tokens"), inputDetails?.int("cached_tokens"), outputDetails?.int("reasoning_tokens"))
}
