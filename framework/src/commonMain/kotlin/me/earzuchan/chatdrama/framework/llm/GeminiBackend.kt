package me.earzuchan.chatdrama.framework.llm

import kotlinx.serialization.json.*

data class GeminiBackendConfig(val apiKey: String, val defaultModel: String, val baseUrl: String = "https://generativelanguage.googleapis.com", val apiVersion: String = "v1beta", val extraHeaders: Map<String, String> = emptyMap())

class GeminiBackend(private val config: GeminiBackendConfig) : HttpProviderBackend() {
    override val shape = ProviderShape.Gemini
    override val defaultConfig = LlmCallConfig(model = config.defaultModel, cache = CachePreference.Prefer, remoteState = RemoteStatePreference.Off)
    override val capabilities = LlmCapabilities(setOf(LlmFeature.Content, LlmFeature.Streaming, LlmFeature.ImageInput, LlmFeature.ToolCalling, LlmFeature.JsonOutput, LlmFeature.Reasoning), setOf(LlmFeature.PromptCaching, LlmFeature.RemoteState))

    override suspend fun request(turn: ProviderTurn, mode: RequestMode) = when (mode) {
        RequestMode.Static -> parseGeminiResponse(postGeminiJson(geminiUrl(turn.config.model, stream = false), headers(), geminiBody(turn)), turn)
        is RequestMode.Streamed -> stream(turn, mode.observer)
    }

    private suspend fun stream(turn: ProviderTurn, observer: TurnObserver?): TurnResult {
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
            val blackboard = responseId?.let { Blackboard.Empty.with("gemini.response_id", it) } ?: Blackboard.Empty
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

    private suspend fun postGeminiJson(url: String, headers: Map<String, String>, body: JsonObject) = super.postJson(url, headers, body)

    private suspend fun postGeminiSse(url: String, headers: Map<String, String>, body: JsonObject, onEvent: suspend (event: String?, data: String) -> Unit) = super.postSse(url, headers, body, onEvent)

    private fun geminiBody(turn: ProviderTurn): JsonObject {
        val body = buildJsonObject {
            put("contents", geminiContents(turn))
            if (turn.rootRevision.root.instructions.isNotEmpty()) put("systemInstruction", buildJsonObject { put("parts", geminiParts(turn.rootRevision.root.instructions)) })
            geminiGenerationConfig(turn.config).takeIf { it.isNotEmpty() }?.let { put("generationConfig", it) }
            if (turn.rootRevision.root.tools.isNotEmpty()) {
                put("tools", buildJsonArray { add(buildJsonObject { put("functionDeclarations", buildJsonArray { turn.rootRevision.root.tools.forEach { add(geminiFunctionDeclaration(it)) } }) }) })
                put("toolConfig", buildJsonObject { put("functionCallingConfig", buildJsonObject { put("mode", "AUTO") }) })
            }
        }
        return body.merge(turn.config.providerOptions[shape] ?: emptyJsonObject())
    }

    private fun geminiContents(turn: ProviderTurn) = buildJsonArray {
        turn.nodes.forEach { node ->
            when (node) {
                is TurnRequestNode -> node.request.items.forEach { item ->
                    when (item) {
                        is TurnInputItem.Content -> add(buildJsonObject {
                            put("role", "user")
                            put("parts", geminiParts(item.parts))
                        })

                        is TurnInputItem.ToolResult -> add(buildJsonObject {
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
                        })
                    }
                }

                is TurnResultNode -> add(buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        node.result.items.forEach { item ->
                            when (item) {
                                is TurnItem.Content -> add(buildJsonObject { put("text", item.asText()) })
                                is TurnItem.Reasoning -> add(item.toGeminiThoughtPart())
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
                })
            }
        }
    }
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

private suspend fun parseGeminiResponse(raw: JsonObject, turn: ProviderTurn): TurnResult {
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
    val blackboard = raw.string("responseId")?.let { Blackboard.Empty.with("gemini.response_id", it) } ?: Blackboard.Empty
    return draft.complete(parseGeminiUsage(raw["usageMetadata"]?.jsonObjectOrNull()), TurnTrace(ProviderShape.Gemini, turn.config.model, candidate?.string("finishReason"), raw, blackboard), blackboard)
}

private fun parseGeminiUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    return TokenUsage(usage.int("promptTokenCount"), usage.int("candidatesTokenCount"), usage.int("totalTokenCount"), usage.int("cachedContentTokenCount"), usage.int("thoughtsTokenCount"))
}

private suspend fun JsonObject.appendGeminiTextPartTo(draft: TurnDraft) {
    string("text")?.let { if (boolean("thought") == true) draft.appendReasoning(it, blackboard = geminiThoughtBlackboard()) else draft.appendContent(it) }
    string("thoughtSignature")?.let { draft.appendReasoning("", ReasoningKind.Opaque, Blackboard.Empty.with("gemini.thought_signature", it)) }
}

private fun JsonObject.geminiThoughtBlackboard() = string("thoughtSignature")?.let { Blackboard.Empty.with("gemini.thought_signature", it) } ?: Blackboard.Empty

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
