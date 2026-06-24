package me.earzuchan.chatdrama.framework.llm.backend

import io.ktor.http.*
import me.earzuchan.chatdrama.framework.llm.*

internal fun openAiHeaders(apiKey: String, organization: String?, project: String?, extraHeaders: Map<String, String>) = buildMap {
    put(HttpHeaders.Authorization, "Bearer $apiKey")
    organization?.let { put("OpenAI-Organization", it) }
    project?.let { put("OpenAI-Project", it) }
    putAll(extraHeaders)
}

internal fun openAiReasoningEffort(reasoning: ReasoningLevel) = when (reasoning) {
    ReasoningLevel.Off -> null
    ReasoningLevel.Minimal -> "minimal"
    ReasoningLevel.Low -> "low"
    ReasoningLevel.Medium -> "medium"
    ReasoningLevel.High -> "high"
    ReasoningLevel.Max -> "high"
}
