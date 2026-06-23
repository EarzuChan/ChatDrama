package me.earzuchan.chatdrama.framework.llm

import io.ktor.util.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.earzuchan.chatdrama.framework.di.frameworkModule
import me.earzuchan.chatdrama.framework.di.frameworkPlatformModule
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LlmApiIntegrationTest {
    @BeforeTest
    fun ensureKoin() {
        if (GlobalContext.getOrNull() == null) startKoin { modules(frameworkModule, frameworkPlatformModule) }
    }

    // 这几个有跨域，所以仅在JVM测试

    @Test
    fun openaiLegacyVanyo() = liveTest { onlyJvm { assertEndpointContract(OpenaiLegacyApi(OpenaiLegacyConfig(VANYO_OPENAI_KEY, "gpt5.5", "$VANYO_BASE_URL/v1")), "openai legacy / vanyo") } }

    @Test
    fun openaiResponsesVanyo() = liveTest { onlyJvm { assertEndpointContract(OpenaiResponsesApi(OpenaiResponsesConfig(VANYO_OPENAI_KEY, "gpt5.5", "$VANYO_BASE_URL/v1")), "openai responses / vanyo") } }
    
    @Test
    fun claudeOpenRouter() = liveTest { onlyJvm { allowRegionalFailure("claude / openrouter") { assertEndpointContract(ClaudeApi(ClaudeConfig(OPENROUTER_KEY, "anthropic/claude-sonnet-4.6", "https://openrouter.ai/api")), "claude / openrouter") } } }

    // 这些恢复正常

    @Test
    fun openaiLegacyOpenRouter() = liveTest { allowRegionalFailure("openai legacy / openrouter") { assertEndpointContract(OpenaiLegacyApi(OpenaiLegacyConfig(OPENROUTER_KEY, "openai/gpt-5.5", "https://openrouter.ai/api/v1")), "openai legacy / openrouter") } }

    @Test
    fun openaiLegacyDeepSeekFlash() = liveTest { assertEndpointContract(OpenaiLegacyApi(OpenaiLegacyConfig(DEEPSEEK_KEY, "deepseek-v4-flash", "https://api.deepseek.com")), "openai legacy / deepseek flash") }

    @Test
    fun openaiLegacyDeepSeekPro() = liveTest { assertEndpointContract(OpenaiLegacyApi(OpenaiLegacyConfig(DEEPSEEK_KEY, "deepseek-v4-pro", "https://api.deepseek.com")), "openai legacy / deepseek pro") }

    @Test
    fun geminiGoogle() = liveTest { assertEndpointContract(GeminiApi(GeminiConfig(GEMINI_KEY, "gemini-3-flash-preview")), "gemini / google") }
}

private val testJson = Json { ignoreUnknownKeys = true }

private fun liveTest(block: suspend () -> Unit) = runTest(timeout = 240.seconds) { block() }

private suspend fun onlyJvm(block: suspend () -> Unit) = if (PlatformUtils.IS_JVM) block() else println("$platformName: jvm-only live test skipped")

private val platformName get() = when {
    PlatformUtils.IS_JVM -> "jvm"

    PlatformUtils.IS_JS -> "js"

    PlatformUtils.IS_WASM_JS -> "wasmJs"

    else -> "unknown"
}

private suspend fun assertEndpointContract(api: LlmApi, label: String) {
    assertJsonOutput(api, label)
    assertStrictJsonOutput(api, label)
    assertStreamText(api, label)
    assertToolLoop(api, label)
    assertCacheHint(api, label)
    assertParallelToolCalls(api, label)
}

private suspend fun assertJsonOutput(api: LlmApi, label: String) {
    val response = api.generate(
        LlmRequest(
            messages = listOf(user("Return a JSON object exactly like {\"value\":\"pong\"}. No markdown, no extra keys.")),
            output = OutputMode.JsonObject, maxOutputTokens = testMaxOutputTokens,
        )
    )
    val text = response.textContent()
    val value = runCatching { testJson.parseToJsonElement(text).jsonObject["value"]?.jsonPrimitive?.contentOrNull }.getOrNull()

    assertEquals("pong", value, "$label did not return the expected JSON object. Response: ${response.brief()}")
}

private suspend fun assertStrictJsonOutput(api: LlmApi, label: String) {
    val response = try {
        api.generate(
            LlmRequest(
                messages = listOf(user("Return only the JSON object requested by the schema: value is pong and count is the string 3.")),
                output = OutputMode.JsonSchema("strict_pong", strictPongSchema, strict = true),
                maxOutputTokens = testMaxOutputTokens,
            )
        )
    } catch (throwable: Throwable) {
        if (!api.looksLikeStrictJsonSchemaUnavailable(throwable)) throw throwable

        println("$label strict json schema skipped: provider rejected json_schema response_format")
        return
    }
    val json = parseJsonObject(response, "$label strict json")

    assertEquals("pong", json["value"]?.jsonPrimitive?.contentOrNull, "$label strict JSON value mismatch. Response: ${response.brief()}")
    assertEquals("3", json["count"]?.jsonPrimitive?.contentOrNull, "$label strict JSON count mismatch. Response: ${response.brief()}")
    assertEquals(setOf("value", "count"), json.keys, "$label strict JSON returned extra/missing keys. Response: ${response.brief()}")
}

private suspend fun assertStreamText(api: LlmApi, label: String) {
    val deltas = StringBuilder()
    var completed: LlmResponse? = null

    api.stream(
        LlmRequest(
            messages = listOf(user("Reply with exactly: pong")),
            maxOutputTokens = testMaxOutputTokens,
        )
    ).collect { event ->
        when (event) {
            is LlmEvent.TextDelta -> deltas.append(event.text)

            is LlmEvent.Completed -> completed = event.response

            else -> Unit
        }
    }

    assertTrue(deltas.isNotBlank(), "$label did not emit text deltas. Completed: ${completed?.brief()}")
    assertEquals("pong", deltas.toString().trim(), "$label streamed unexpected text. Completed: ${completed?.brief()}")
    assertTrue(completed != null, "$label did not emit a completed event")
}

private suspend fun assertToolLoop(api: LlmApi, label: String) {
    val first = api.generate(
        LlmRequest(
            messages = listOf(user("Call record_pong with value pong. Do not answer in text.")),
            tools = listOf(ToolSpec("record_pong", "Records the test value.", pongToolSchema, strict = false)),
            toolConfig = api.testToolConfig("record_pong"), maxOutputTokens = testMaxOutputTokens,
        )
    )
    val toolCall = first.toolCalls.firstOrNull { it.name == "record_pong" }

    assertTrue(toolCall != null, "$label tool loop did not produce the first tool call. Response: ${first.brief()}")
    assertEquals("pong", toolCall.arguments["value"]?.jsonPrimitive?.contentOrNull, "$label tool loop returned tool args: ${toolCall.arguments}")

    val second = api.generate(
        LlmRequest(
            messages = listOf(
                user("Call record_pong with value pong. After the tool result, reply exactly: done:pong"),
                LlmMessage.Model(toolCalls = listOf(toolCall)),
                LlmMessage.ToolResult(toolCall.id, toolCall.name, listOf(ContentPart.Text("recorded pong"))),
            ),
            tools = listOf(ToolSpec("record_pong", "Records the test value.", pongToolSchema, strict = false)),
            toolConfig = ToolConfig(mode = ToolMode.None), maxOutputTokens = testMaxOutputTokens,
        )
    )

    assertEquals("done:pong", second.textContent().trim(), "$label did not complete the tool loop. Response: ${second.brief()}")
}

private suspend fun assertCacheHint(api: LlmApi, label: String) {
    val response = api.generate(
        LlmRequest(
            system = listOf(ContentPart.Text("Stable cached prefix for live test. The required final answer is cache:pong.", CacheMarker.Ephemeral)),
            messages = listOf(user("Reply exactly: cache:pong")),
            cache = CachePolicy.Breakpoints(listOf(CacheBreakpoint(-1, 0))),
            maxOutputTokens = testMaxOutputTokens,
        )
    )

    assertEquals("cache:pong", response.textContent().trim(), "$label cache-hint request failed. Response: ${response.brief()}")
}

private suspend fun assertParallelToolCalls(api: LlmApi, label: String) {
    val response = api.generate(
        LlmRequest(
            messages = listOf(user("Call record_alpha with value alpha and record_beta with value beta. Use both tools now. Do not answer in text.")),
            tools = listOf(
                ToolSpec("record_alpha", "Records alpha.", alphaToolSchema, strict = false),
                ToolSpec("record_beta", "Records beta.", betaToolSchema, strict = false),
            ),
            toolConfig = api.testParallelToolConfig(), maxOutputTokens = testMaxOutputTokens,
        )
    )
    val calls = response.toolCalls.associateBy { it.name }

    assertTrue(calls["record_alpha"] != null && calls["record_beta"] != null, "$label did not return both parallel tool calls. Response: ${response.brief()}")
    assertEquals("alpha", calls["record_alpha"]?.arguments?.get("value")?.jsonPrimitive?.contentOrNull, "$label alpha tool args mismatch. Response: ${response.brief()}")
    assertEquals("beta", calls["record_beta"]?.arguments?.get("value")?.jsonPrimitive?.contentOrNull, "$label beta tool args mismatch. Response: ${response.brief()}")
}

private suspend fun allowRegionalFailure(label: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (throwable: Throwable) {
        if (!throwable.looksLikeRegionalFailure()) throw throwable

        println("$label skipped: ${throwable.message?.take(240)}")
    }
}

private fun Throwable.looksLikeRegionalFailure(): Boolean {
    val message = listOfNotNull(message, cause?.message).joinToString("\n").lowercase()

    return this is LlmApiException && statusCode in setOf(401, 403, 429, 451) || "cors" in message || "region" in message || "country" in message || "unsupported_country" in message || "failed to fetch" in message || "networkerror" in message || "provider returned error" in message
}

private fun LlmApi.looksLikeStrictJsonSchemaUnavailable(throwable: Throwable): Boolean {
    val message = listOfNotNull(throwable.message, throwable.cause?.message).joinToString("\n").lowercase()

    return provider == LlmProvider.OpenAiLegacy && defaultModel.startsWith("deepseek-v4") && throwable is LlmApiException && "response_format type is unavailable" in message
}

private fun user(text: String) = LlmMessage.User(listOf(ContentPart.Text(text)))

private const val testMaxOutputTokens = 1024

private fun LlmApi.testToolConfig(name: String = "record_pong") = ToolConfig(mode = if (defaultModel.startsWith("deepseek-v4")) ToolMode.Auto else ToolMode.Force(name), allowParallel = false)

private fun LlmApi.testParallelToolConfig() = ToolConfig(mode = if (defaultModel.startsWith("deepseek-v4")) ToolMode.Auto else ToolMode.Required, allowParallel = true)

private fun LlmResponse.textContent() = content.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }.trim()

private fun LlmResponse.brief() = "model=$model finish=$finishReason text=${textContent().take(240)} tools=${toolCalls.map { it.name to it.arguments }}"

private fun parseJsonObject(response: LlmResponse, label: String) = runCatching { testJson.parseToJsonElement(response.textContent()).jsonObject }.getOrElse { throw AssertionError("$label did not return a JSON object. Response: ${response.brief()}", it) }

private val strictPongSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("value", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("pong")) })
        })
        put("count", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("3")) })
        })
    })
    put("required", buildJsonArray {
        add(JsonPrimitive("value"))
        add(JsonPrimitive("count"))
    })
    put("additionalProperties", false)
}

private val pongToolSchema = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("value", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive("pong")) })
        })
    })
    put("required", buildJsonArray { add(JsonPrimitive("value")) })
    put("additionalProperties", false)
}

private val alphaToolSchema = valueToolSchema("alpha")

private val betaToolSchema = valueToolSchema("beta")

private fun valueToolSchema(value: String) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("value", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add(JsonPrimitive(value)) })
        })
    })
    put("required", buildJsonArray { add(JsonPrimitive("value")) })
    put("additionalProperties", false)
}
