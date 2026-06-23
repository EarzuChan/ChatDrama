package me.earzuchan.chatdrama.framework.llm

import io.ktor.util.PlatformUtils
import kotlinx.coroutines.test.runTest
import me.earzuchan.chatdrama.framework.di.frameworkModule
import me.earzuchan.chatdrama.framework.di.frameworkPlatformModule
import kotlin.test.BeforeTest
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import kotlin.test.Test


const val VANYO_BASE_URL = "https://cn.vanyospace.com"
const val VANYO_MODEL_GPT5_5 = "gpt-5.5"
const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
const val DEEPSEEK_MODEL_V4_PRO = "deepseek-v4-pro"
const val OPENROUTER_BASE_URL = "https://openrouter.ai/api"
const val OPENROUTER_MODEL_CLAUDE_SONNET_4_6 = "anthropic/claude-sonnet-4.6"
const val GEMINI_MODEL_3_FLASH = "gemini-3-flash-preview"

private fun log(tag: String, any: Any) = println("[$tag] $any")

class LlmApiIntegrationTest {
    @BeforeTest
    fun ensureKoin() {
        if (GlobalContext.getOrNull() == null) startKoin { modules(frameworkModule, frameworkPlatformModule) }
    }

    @Test
    fun tempDemo() = runTest {
        // val (backend, model) = OpenAiLegacyBackend(OpenAiLegacyBackendConfig(DEEPSEEK_KEY, DEEPSEEK_BASE_URL)) to DEEPSEEK_MODEL_V4_PRO
        // val (backend, model) = OpenAiResponsesBackend(OpenAiResponsesBackendConfig(VANYO_OPENAI_KEY, "$VANYO_BASE_URL/v1")) to VANYO_MODEL_GPT5_5
        // val (backend, model) = GeminiBackend(GeminiBackendConfig(GEMINI_KEY)) to GEMINI_MODEL_3_FLASH
        val (backend, model) = ClaudeBackend(ClaudeBackendConfig(OPENROUTER_KEY,OPENROUTER_BASE_URL)) to OPENROUTER_MODEL_CLAUDE_SONNET_4_6

        val session = LlmSession(
            backend = backend,
            root = SessionRoot(
                instructions = textContentParts("你是一个测试用途的LLM，你得使用我的工具以测试它"),
                tools = listOf(
                    ToolDefinition(
                        name = "search",
                        description = "搜索信息",
                        args = listOf(ToolArg("query", ToolArgType.StringType, required = true))
                    )
                )
            ),
            defaults = LlmCallConfig(model = model, reasoning = ReasoningLevel.High)
        )

        val result1 = session.request(TurnRequest(listOf(textContentInputItem("下北泽有多少人？")))).also { log("Result1", it) }

        val searchCall = result1.items.filterIsInstance<TurnItem.ToolCall>().first().also { log("SearchCall1", it) }

        val result2 = session.request(
            TurnRequest(
                listOf(
                    TurnInputItem.ToolResult(
                        toolCallId = searchCall.id,
                        name = searchCall.name,
                        parts = textContentParts("114514人。这就是本工具预设的测试结果")
                    )
                )
            )
        ).also { log("Result2", it) }

        result2.items.filterIsInstance<TurnItem.Content>().joinToString("\n") { it.text() }.also { log("Answer", it) }

        log("LaneA", "\n${session.debugLaneA()}")
        log("LaneB", "\n${session.debugLaneB()}")
    }
}

private suspend fun onlyJvm(block: suspend () -> Unit) = if (PlatformUtils.IS_JVM) block() else println("$platformName: jvm-only live test skipped")

private val platformName
    get() = when {
        PlatformUtils.IS_JVM -> "jvm"
        PlatformUtils.IS_JS -> "js"
        PlatformUtils.IS_WASM_JS -> "wasmJs"
        else -> "unknown"
    }
