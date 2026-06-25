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

class OpenAiResponsesBackend(private val config: OpenAiResponsesBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.OpenAiResponses
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput, LlmFeature.Reasoning), setOf(LlmFeature.PromptCaching, LlmFeature.RemoteState))

    override fun rebuildLaneB(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB = OpenAiResponsesLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), openAiResponsesRoot(rootRevision.root), OpenAiResponsesContext.Stateless(openAiResponsesInput(nodes)), sessionBlackboard.openAiResponsesLaneBlackboard())

    override suspend fun breakState(laneB: ProviderLaneB, rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB {
        val lane = laneB as? OpenAiResponsesLaneB ?: return laneB
        return lane.breakState(rootRevision, nodes, config, sessionBlackboard)
    }

    override suspend fun compact(laneB: ProviderLaneB, rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard): ProviderLaneB {
        val lane = laneB as? OpenAiResponsesLaneB ?: return laneB
        val body = compactionBody(lane, config)
        var source = lane

        val raw = try {
            postJson(compactUrl(), headers(), body)
        } catch (throwable: LlmProviderException) {
            if (!throwable.isOpenAiResponsesPreviousResponseUnsupported() || lane.context !is OpenAiResponsesContext.StatefulByPreviousId) throw throwable

            source = lane.fallbackFromPreviousResponseUnsupported()
            postJson(compactUrl(), headers(), compactionBody(source, config))
        }

        val output = raw["output"]?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull() } ?: emptyList()
        val broken = source.breakState(rootRevision, nodes, config, sessionBlackboard)
        return if (output.isEmpty()) broken else broken.copy(context = OpenAiResponsesContext.Stateless(output, officialCompaction = true), blackboard = broken.blackboard.withAll(openAiResponsesBlackboard(raw)))
    }

    override fun debugLaneB(laneB: ProviderLaneB?) = laneB?.let { debugLaneB(it as? OpenAiResponsesLaneB ?: return@let providerLaneBDebug("OpenAiResponsesLaneB(wrong type)", it)) } ?: "OpenAiResponsesLaneB(null)"

    override suspend fun request(turn: ProviderTurn<ProviderLaneB>, mode: RequestMode): ProviderTurnCommit<ProviderLaneB> = internalRealRequest(turn.typedTurn("OpenAI responses") { it as? OpenAiResponsesLaneB }, mode)

    private suspend fun internalRealRequest(turn: ProviderTurn<OpenAiResponsesLaneB>, mode: RequestMode): ProviderTurnCommit<OpenAiResponsesLaneB> {
        try {
            return when (mode) {
                RequestMode.Static -> commit(turn, parseOpenAiResponsesResponse(postJson(responsesUrl(), headers(), responsesBody(turn, stream = false)), turn))

                is RequestMode.Streamed -> commit(turn, stream(turn, mode.observer))
            }
        } catch (throwable: LlmProviderException) {
            if (!throwable.isOpenAiResponsesPreviousResponseUnsupported() || turn.laneB.context !is OpenAiResponsesContext.StatefulByPreviousId) throw throwable

            return internalRealRequest(turn.copy(laneB = turn.laneB.fallbackFromPreviousResponseUnsupported()), mode)
        }
    }

    private suspend fun stream(turn: ProviderTurn<OpenAiResponsesLaneB>, observer: TurnObserver?): TurnResult {
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
            if (throwable is LlmProviderException) throw throwable
            throw LlmTurnException(throwable.message ?: "OpenAI responses stream failed", throwable, draft.partial(trace = TurnTrace(shape, turn.config.model)))
        }
    }

    private fun responsesUrl() = "${config.baseUrl.trimEnd('/')}/responses"

    private fun compactUrl() = "${config.baseUrl.trimEnd('/')}/responses/compact"

    private fun headers() = openAiHeaders(config.apiKey, config.organization, config.project, config.extraHeaders) // Org和项目被OpenAI新API吃

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

    private fun responsesBody(turn: ProviderTurn<OpenAiResponsesLaneB>, stream: Boolean): JsonObject {
        val lane = turn.laneB
        val body = buildJsonObject {
            put("model", turn.config.model)
            lane.root.instructions?.let { put("instructions", it) }
            when (val context = lane.context) {
                is OpenAiResponsesContext.Stateless -> {
                    put("input", openAiResponsesInput(context.input, turn.requestNode.request))
                }

                is OpenAiResponsesContext.StatefulByPreviousId -> {
                    put("previous_response_id", context.id)
                    put("input", openAiResponsesRequestInput(turn.requestNode.request).toJsonArray())
                }
            }
            turn.config.temperature?.let { put("temperature", it) }
            if (stream) put("stream", true)

            lane.root.tools?.takeIf { it.isNotEmpty() }?.let {
                put("tools", it)
                put("parallel_tool_calls", true)
            }

            openAiResponsesTextFormat(turn.config.output)?.let { put("text", buildJsonObject { put("format", it) }) }
            openAiResponsesReasoning(turn.config.reasoning)?.let { put("reasoning", it) }
        }
        return body.merge(turn.config.providerOptions[shape] ?: emptyJsonObject()).withOpenAiResponsesInternalStateOptions(turn.config)
    }

    private fun commit(turn: ProviderTurn<OpenAiResponsesLaneB>, result: TurnResult): ProviderTurnCommit<OpenAiResponsesLaneB> {
        val lane = turn.laneB
        val output = openAiResponsesResultInput(result)
        val responseId = result.blackboard.string("openai.responses.response_id")
        val requestInput = openAiResponsesRequestInput(turn.requestNode.request)
        val blackboard = lane.blackboard.withAll(result.blackboard.onlyPrefixed("openai."))
        val resultBlackboard = result.blackboard.withAll(lane.blackboard.onlyPrefixed(OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED))
        val context = when (val current = lane.context) {
            is OpenAiResponsesContext.Stateless -> {
                val shadowInput = current.input + requestInput + output
                if (responseId != null && blackboard.string(OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED) != "true") OpenAiResponsesContext.StatefulByPreviousId(responseId, shadowInput) else OpenAiResponsesContext.Stateless(shadowInput, current.officialCompaction)
            }

            is OpenAiResponsesContext.StatefulByPreviousId -> {
                val shadowInput = current.shadowInput + requestInput + output
                if (responseId != null && blackboard.string(OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED) != "true") OpenAiResponsesContext.StatefulByPreviousId(responseId, shadowInput) else current.copy(shadowInput = shadowInput)
            }
        }
        val next = lane.copy(anchorNodeId = turn.resultNodeId, context = context, blackboard = blackboard).tidyAfterAppend()
        return ProviderTurnCommit(next, if (resultBlackboard == result.blackboard) result else result.copy(blackboard = resultBlackboard))
    }

    private fun compactionBody(lane: OpenAiResponsesLaneB, config: EffectiveLlmCallConfig) = buildJsonObject {
        put("model", config.model)
        lane.root.instructions?.let { put("instructions", it) }
        when (val context = lane.context) {
            is OpenAiResponsesContext.Stateless -> put("input", context.input.toJsonArray())
            is OpenAiResponsesContext.StatefulByPreviousId -> put("previous_response_id", context.id)
        }
    }.merge((config.providerOptions[shape] ?: emptyJsonObject()).openAiResponsesCompactOptions())

    private fun OpenAiResponsesLaneB.breakState(rootRevision: RootRevision, nodes: List<SessionNode>, config: EffectiveLlmCallConfig, sessionBlackboard: LlmBlackboard) = when (context) {
        is OpenAiResponsesContext.Stateless -> copy(blackboard = blackboard.without("openai.responses.response_id"))
        is OpenAiResponsesContext.StatefulByPreviousId -> OpenAiResponsesLaneB(rootRevision.id, nodes.lastOrNull()?.id, config.laneBKey(shape), openAiResponsesRoot(rootRevision.root), OpenAiResponsesContext.Stateless(openAiResponsesInput(nodes)), sessionBlackboard.openAiResponsesLaneBlackboard())
    }

    // 滚回“无状态”去
    private fun OpenAiResponsesLaneB.fallbackFromPreviousResponseUnsupported(): OpenAiResponsesLaneB {
        val context = context as? OpenAiResponsesContext.StatefulByPreviousId ?: return this
        return copy(context = OpenAiResponsesContext.Stateless(context.shadowInput), blackboard = blackboard.with(OPENAI_RESPONSES_PREVIOUS_RESPONSE_UNSUPPORTED, "true").without("openai.responses.response_id"))
    }
}

private fun openAiResponsesRoot(root: SessionRoot) = OpenAiResponsesRoot(
    instructions = root.instructions.takeIf { it.isNotEmpty() }?.plainText(),
    tools = root.tools.takeIf { it.isNotEmpty() }?.let { buildJsonArray { it.forEach { tool -> add(openAiResponsesTool(tool)) } } }
)

private fun openAiResponsesInput(input: List<JsonObject>, request: TurnRequest) = buildJsonArray {
    input.forEach { add(it) }
    openAiResponsesRequestInput(request).forEach { add(it) }
}

// TIPS：进行转译+转译中有顺手处理
private fun openAiResponsesInput(nodes: List<SessionNode>) = buildList {
    nodes.forEach { node ->
        when (node) {
            is TurnRequestNode -> addAll(openAiResponsesRequestInput(node.request))
            is TurnResultNode -> addAll(openAiResponsesResultInput(node.result))
        }
    }
}

private fun openAiResponsesRequestInput(request: TurnRequest) = buildList {
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

// CHECK：这里会不会有合流问题？如果是原生收到Result，为什么要转TurnResult再回转？本应只面向转译！
private fun openAiResponsesResultInput(result: TurnResult): List<JsonObject> {
    val rawOutput = result.trace.raw?.jsonObjectOrNull()?.get("output")?.jsonArrayOrNull()
    if (rawOutput != null) return rawOutput.mapNotNull { it.jsonObjectOrNull() }

    return buildList {
        // Responses 对外家 reasoning item 虽能收，但实验显示模型可能接不住；外家思考乔装为普通 assistant text。
        if (!result.isFrom(ProviderShape.OpenAiResponses)) result.previousReasoningText()?.let { add(buildJsonObject {
            put("role", "assistant")
            put("content", it)
        }) }

        result.items.forEach { item ->
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

                is TurnItem.Reasoning -> if (result.isFrom(ProviderShape.OpenAiResponses)) add(buildJsonObject {
                    put("type", "reasoning")
                    item.text?.let { put("summary", buildJsonArray { add(buildJsonObject {
                        put("type", "summary_text")
                        put("text", it)
                    }) }) }
                })

                is TurnItem.Refusal -> item.text?.let { add(buildJsonObject {
                    put("role", "assistant")
                    put("content", it)
                }) }
            }
        }
    }
}

private fun openAiResponsesBlackboard(raw: JsonObject) = raw.string("id")?.let { LlmBlackboard.Empty.with("openai.responses.compaction_id", it) } ?: LlmBlackboard.Empty

// 这俩：如果之前是被官方压缩的，则不解盘
private fun OpenAiResponsesLaneB.tidyAfterAppend() = when (val context = context) {
    is OpenAiResponsesContext.Stateless -> if (context.officialCompaction) this else copy(context = context.copy(input = context.input.tidyLatestOpenAiResponsesWave()))
    is OpenAiResponsesContext.StatefulByPreviousId -> copy(context = context.copy(shadowInput = context.shadowInput.tidyLatestOpenAiResponsesWave()))
}

// 小压缩 Start

private data class OpenAiResponsesWaveRange(val start: Int, val endExclusive: Int)

private fun List<JsonObject>.tidyLatestOpenAiResponsesWave(): List<JsonObject> {
    val range = latestOpenAiResponsesWaveRange() ?: return this
    val tidied = tidiedOpenAiResponsesWave(range)
    if (tidied == subList(range.start, range.endExclusive)) return this // 如果整理前和整理后的内容完全一致，则无需修改，直接返回原列表
    return take(range.start) + tidied + drop(range.endExclusive)
}

private fun List<JsonObject>.tidiedOpenAiResponsesWave(range: OpenAiResponsesWaveRange) = subList(range.start, range.endExclusive).tidiedOpenAiResponsesWave()

private fun List<JsonObject>.latestOpenAiResponsesWaveRange(): OpenAiResponsesWaveRange? {
    var endExclusive = size // 初始化右边界（不包含）为整个列表的长度

    // 从后往前寻找波的结束位置，跳过列表末尾所有属于压缩类型的元素
    while (endExclusive > 0 && this[endExclusive - 1].isOpenAiResponsesCompaction()) endExclusive--
    if (endExclusive == 0) return null // 全是压缩过的，直接返回

    // 逆序找Prompt
    var promptIndex = -1
    for (index in endExclusive - 1 downTo 0) {
        val item = this[index]

        // 遇到了压缩就停
        if (item.isOpenAiResponsesCompaction()) break

        // 找到Prompt就记录然后停
        if (item.isOpenAiResponsesPrompt()) {
            promptIndex = index
            break
        }
    }
    if (promptIndex < 0) return null // 这是说明，没有完整的一波，直接返回（不处理）

    // 调整Prompt起始点：如果再左也是就加入
    while (promptIndex > 0 && this[promptIndex - 1].isOpenAiResponsesPrompt()) promptIndex--
    return OpenAiResponsesWaveRange(promptIndex, endExclusive)
}

private fun JsonObject.isOpenAiResponsesPrompt() = string("role") == "user" && string("type") != "function_call_output" && string("type") != "computer_call_output" && !hasOpenAiResponsesContentType("function_call_output")

private fun JsonObject.isOpenAiResponsesFinalMessage() = string("type").let { it == null || it == "message" } && string("role") == "assistant" && !containsKey("call_id") && hasOpenAiResponsesVisibleContent()

// 界定好Wave以后，都是调用这个来压缩
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

// 小压缩 End

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
    this@withOpenAiResponsesInternalStateOptions.forEach { (key, value) -> if (key != "include" && key != "store") put(key, value) } // 非特色Key，就设上

    val include = (this@withOpenAiResponsesInternalStateOptions["include"]?.jsonArrayOrNull().orEmpty() + openAiResponsesInternalInclude(config)).distinct()
    if (include.isNotEmpty()) put("include", include.toJsonArray())
    put("store", true) // 强开Store
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

private suspend fun parseOpenAiResponsesResponse(raw: JsonObject, turn: ProviderTurn<*>, observer: TurnObserver? = null): TurnResult {
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
    val blackboard = raw.string("id")?.let { LlmBlackboard.Empty.with("openai.responses.response_id", it) } ?: LlmBlackboard.Empty
    return draft.complete(parseOpenAiResponsesUsage(raw["usage"]?.jsonObjectOrNull()), TurnTrace(ProviderShape.OpenAiResponses, raw.string("model") ?: turn.config.model, raw.string("status"), raw, blackboard), blackboard)
}

private fun parseOpenAiResponsesUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    val inputDetails = usage["input_tokens_details"]?.jsonObjectOrNull()
    val outputDetails = usage["output_tokens_details"]?.jsonObjectOrNull()
    return TokenUsage(usage.int("input_tokens"), usage.int("output_tokens"), usage.int("total_tokens"), inputDetails?.int("cached_tokens"), outputDetails?.int("reasoning_tokens"))
}

private fun TurnItem.Content.asOutputText() = asText()
