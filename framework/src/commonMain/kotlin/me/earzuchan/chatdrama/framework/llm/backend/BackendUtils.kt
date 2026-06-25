package me.earzuchan.chatdrama.framework.llm.backend

import me.earzuchan.chatdrama.framework.llm.*
import me.earzuchan.chatdrama.framework.llm.misc.*

internal fun TurnResult.isFrom(shape: ProviderShape) = trace.shape == shape

internal fun TurnResult.hasGeminiNativeToolChain() = isFrom(ProviderShape.Gemini) && items.filterIsInstance<TurnItem.ToolCall>().all { it.blackboard.string("gemini.thought_signature") != null }

internal fun TurnResult.hasClaudeNativeThinking() = isFrom(ProviderShape.Claude) && items.filterIsInstance<TurnItem.Reasoning>().all { (it.kind == ReasoningKind.Redacted && it.blackboard.string("claude.messages.redacted_thinking") != null) || (it.kind != ReasoningKind.Redacted && it.blackboard.string("claude.messages.thinking_signature") != null) }

// TIPS：以下逻辑有点共用，给各家使用，可能有点乱

internal fun TurnResult.previousAssistantText(includeReasoning: Boolean = true, includeTools: Boolean = true, includeContent: Boolean = true): String? {
    val blocks = buildList {
        items.forEach { item ->
            when (item) {
                is TurnItem.Reasoning -> if (includeReasoning) item.previousThoughtText()?.let { add(it) }
                is TurnItem.ToolCall -> if (includeTools) add("[previous assistant tool call]\nid=${item.id}\n${item.name}(${item.arguments})")
                is TurnItem.Content -> if (includeContent) item.asText().takeIf { it.isNotBlank() }?.let { add("[previous assistant content]\n$it") }
                is TurnItem.Refusal -> if (includeContent) item.text?.takeIf { it.isNotBlank() }?.let { add("[previous assistant refusal]\n$it") }
            }
        }
    }
    return blocks.joinToString("\n\n").takeIf { it.isNotBlank() }
}

internal fun TurnResult.previousReasoningText() = previousAssistantText(includeTools = false, includeContent = false)

internal fun TurnInputItem.ToolResult.previousToolResultText() = buildString {
    append("[tool result]\n")
    append("id=")
    append(toolCallId)
    append("\n")
    append(name ?: toolCallId)
    append(" => ")
    append(parts.plainText())
    if (isError) append("\n[tool result is error]")
}

internal fun TurnItem.Reasoning.previousThoughtText(): String? {
    val text = text?.takeIf { it.isNotBlank() } ?: return null
    val title = when (kind) {
        ReasoningKind.Raw -> "[previous assistant raw thought]"
        ReasoningKind.Summary -> "[previous assistant thought]"
        ReasoningKind.Redacted -> "[previous assistant redacted thought]"
        ReasoningKind.Opaque -> "[previous assistant opaque thought]"
    }
    return "$title\n$text"
}
