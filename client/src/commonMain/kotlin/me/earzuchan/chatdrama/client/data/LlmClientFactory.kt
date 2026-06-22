package me.earzuchan.chatdrama.client.data

import me.earzuchan.chatdrama.client.data.model.LlmSettings
import me.earzuchan.chatdrama.framework.llm.ClaudeApi
import me.earzuchan.chatdrama.framework.llm.ClaudeConfig
import me.earzuchan.chatdrama.framework.llm.ContentPart
import me.earzuchan.chatdrama.framework.llm.GeminiApi
import me.earzuchan.chatdrama.framework.llm.GeminiConfig
import me.earzuchan.chatdrama.framework.llm.LlmApi
import me.earzuchan.chatdrama.framework.llm.LlmProvider
import me.earzuchan.chatdrama.framework.llm.OpenaiLegacyApi
import me.earzuchan.chatdrama.framework.llm.OpenaiLegacyConfig
import me.earzuchan.chatdrama.framework.llm.OpenaiResponsesApi
import me.earzuchan.chatdrama.framework.llm.OpenaiResponsesConfig

fun createLlmApi(settings: LlmSettings): LlmApi {
    val cleanEndpoint = settings.endpoint.trim()
    val cleanModel = settings.model.trim()
    val cleanApiKey = settings.apiKey.trim()

    require(cleanApiKey.isNotEmpty()) { "请先配置 API Key" }
    require(cleanModel.isNotEmpty()) { "请先配置模型" }

    return when (settings.provider) {
        LlmProvider.OpenAiLegacy -> if (cleanEndpoint.isBlank()) OpenaiLegacyApi(OpenaiLegacyConfig(cleanApiKey, cleanModel)) else OpenaiLegacyApi(OpenaiLegacyConfig(cleanApiKey, cleanModel, cleanEndpoint))

        LlmProvider.OpenAiResponses -> if (cleanEndpoint.isBlank()) OpenaiResponsesApi(OpenaiResponsesConfig(cleanApiKey, cleanModel)) else OpenaiResponsesApi(OpenaiResponsesConfig(cleanApiKey, cleanModel, cleanEndpoint))

        LlmProvider.Claude -> if (cleanEndpoint.isBlank()) ClaudeApi(ClaudeConfig(cleanApiKey, cleanModel)) else ClaudeApi(ClaudeConfig(cleanApiKey, cleanModel, cleanEndpoint))

        LlmProvider.Gemini -> if (cleanEndpoint.isBlank()) GeminiApi(GeminiConfig(cleanApiKey, cleanModel)) else GeminiApi(GeminiConfig(cleanApiKey, cleanModel, cleanEndpoint))
    }
}

fun List<ContentPart>.textContent() = joinToString("\n") { part ->
    when (part) {
        is ContentPart.Text -> part.text

        is ContentPart.ImageUrl -> part.url

        is ContentPart.ImageBase64 -> "[image:${part.mimeType}]"
    }
}.trim()
