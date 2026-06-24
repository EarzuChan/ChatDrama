package me.earzuchan.chatdrama.framework.llm

import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.llm.misc.*

enum class ProviderShape { OpenAiLegacy, OpenAiResponses, Claude, Gemini }

enum class LlmFeature { Content, Streaming, ImageInput, ToolCalling, JsonOutput, PromptCaching, Reasoning, RemoteState }

data class LlmCapabilities(val native: Set<LlmFeature>, val bestEffort: Set<LlmFeature> = emptySet()) {
    val supported: Set<LlmFeature> get() = native + bestEffort
    fun supports(feature: LlmFeature) = feature in supported
    fun emits(feature: LlmFeature) = feature in native
}

data class LlmBlackboard(val values: Map<String, JsonElement> = emptyMap()) {
    val isEmpty get() = values.isEmpty()
    operator fun get(key: String) = values[key]
    fun string(key: String) = values[key]?.jsonPrimitiveOrNull()?.contentOrNull
    fun obj(key: String) = values[key]?.jsonObjectOrNull()
    fun with(key: String, value: JsonElement) = LlmBlackboard(values + (key to value))
    fun with(key: String, value: String) = with(key, JsonPrimitive(value))
    fun withAll(other: LlmBlackboard) = LlmBlackboard(values + other.values)
    fun without(key: String) = LlmBlackboard(values - key)

    companion object { val Empty = LlmBlackboard() }
}

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class ImageUrl(val url: String, val mimeType: String? = null) : ContentPart
    data class ImageBase64(val base64: String, val mimeType: String) : ContentPart
}

data class ToolDefinition(val name: String, val description: String? = null, val args: List<ToolArg> = emptyList(), val strict: Boolean = true)

data class ToolArg(val name: String, val type: ToolArgType, val description: String? = null, val required: Boolean = true)

sealed interface ToolArgType {
    data object StringType : ToolArgType
    data object IntegerType : ToolArgType
    data object NumberType : ToolArgType
    data object BooleanType : ToolArgType
    data class EnumType(val values: List<String>) : ToolArgType
    data class ArrayType(val item: ToolArgType) : ToolArgType
    data class ObjectType(val args: List<ToolArg>) : ToolArgType
}

data class SessionRoot(val instructions: List<ContentPart> = emptyList(), val tools: List<ToolDefinition> = emptyList(), val blackboard: LlmBlackboard = LlmBlackboard.Empty)

sealed interface TurnInputItem {
    data class Content(val parts: List<ContentPart>, val label: String? = null, val metadata: Map<String, String> = emptyMap()) : TurnInputItem
    data class ToolResult(val toolCallId: String, val name: String? = null, val parts: List<ContentPart>, val isError: Boolean = false, val metadata: Map<String, String> = emptyMap()) : TurnInputItem
}

data class TurnRequest(val items: List<TurnInputItem>, val metadata: Map<String, String> = emptyMap(), val blackboard: LlmBlackboard = LlmBlackboard.Empty)

sealed interface OutputBody {
    data class Text(val text: String) : OutputBody
    data class Json(val value: JsonElement, val rawText: String? = null) : OutputBody
}

enum class ReasoningKind { Summary, Raw, Redacted, Opaque }

data class SafetyInfo(val reason: String? = null, val categories: List<String> = emptyList(), val raw: JsonElement? = null)

sealed interface TurnItem {
    val blackboard: LlmBlackboard

    data class Content(val body: OutputBody, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : TurnItem
    data class Reasoning(val text: String? = null, val kind: ReasoningKind = ReasoningKind.Summary, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : TurnItem
    data class ToolCall(val id: String, val name: String, val arguments: JsonObject, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : TurnItem
    data class Refusal(val text: String? = null, val safety: SafetyInfo? = null, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : TurnItem
}

data class TokenUsage(val inputTokens: Int? = null, val outputTokens: Int? = null, val totalTokens: Int? = null, val cachedInputTokens: Int? = null, val reasoningTokens: Int? = null)

data class TurnTrace(val shape: ProviderShape? = null, val model: String? = null, val finishReason: String? = null, val raw: JsonElement? = null, val blackboard: LlmBlackboard = LlmBlackboard.Empty, val notices: List<String> = emptyList())

data class TurnResult(val items: List<TurnItem>, val usage: TokenUsage? = null, val trace: TurnTrace = TurnTrace(), val blackboard: LlmBlackboard = LlmBlackboard.Empty)

enum class ReasoningLevel { Off, Minimal, Low, Medium, High, Max }

enum class CachePreference { Off, Prefer }

sealed interface OutputContract {
    data object Text : OutputContract
    data object Json : OutputContract
    data class JsonSchema(val name: String, val schema: JsonObject, val strict: Boolean = true) : OutputContract
}

// 随时可改的会话配置，模型、思考程度、缓存
data class LlmCallConfig(val model: String? = null, val reasoning: ReasoningLevel? = null, val cache: CachePreference? = null, val output: OutputContract? = null, val temperature: Double? = null, val providerOptions: Map<ProviderShape, JsonObject> = emptyMap()) {
    fun over(other: LlmCallConfig) = LlmCallConfig(other.model ?: model, other.reasoning ?: reasoning, other.cache ?: cache, other.output ?: output, other.temperature ?: temperature, providerOptions + other.providerOptions)
}

data class EffectiveLlmCallConfig(val model: String, val reasoning: ReasoningLevel = ReasoningLevel.Off, val cache: CachePreference = CachePreference.Prefer, val output: OutputContract = OutputContract.Text, val temperature: Double? = null, val providerOptions: Map<ProviderShape, JsonObject> = emptyMap())

sealed interface RequestMode {
    data object Static : RequestMode
    data class Streamed(val observer: TurnObserver? = null) : RequestMode
}

enum class TurnItemKind { Content, Reasoning, ToolCall, Refusal }

sealed interface TurnItemDelta {
    data class Text(val text: String) : TurnItemDelta
    data class ToolArguments(val delta: String) : TurnItemDelta
}

interface TurnObserver { suspend fun onEvent(event: TurnEvent) }

sealed interface TurnEvent {
    data class ItemStarted(val id: String, val kind: TurnItemKind) : TurnEvent
    data class ItemDelta(val id: String, val delta: TurnItemDelta) : TurnEvent
    data class ItemCompleted(val id: String, val item: TurnItem) : TurnEvent
    data class Completed(val result: TurnResult) : TurnEvent
}

class LlmTurnException(message: String, cause: Throwable? = null, val partial: TurnResult? = null, val trace: TurnTrace = TurnTrace()) : RuntimeException(message, cause)

class LlmProviderException(val statusCode: Int, override val message: String, val body: String, val raw: JsonElement?) : RuntimeException(message)
