package me.earzuchan.chatdrama.framework.llm.backend

import me.earzuchan.chatdrama.framework.llm.ProviderShape
import me.earzuchan.chatdrama.framework.llm.ReasoningKind
import me.earzuchan.chatdrama.framework.llm.TurnInputItem
import me.earzuchan.chatdrama.framework.llm.TurnItem
import me.earzuchan.chatdrama.framework.llm.TurnResult
import me.earzuchan.chatdrama.framework.llm.misc.*

internal fun TurnResult.isFrom(shape: ProviderShape) = trace.shape == shape

// 以下那些是为了转厂的原厂内容乔装而设的

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
        ReasoningKind.Opaque -> "[previous assistant opaque thought]" // TIPS、CHECK：透传的，若不是明文，则招笑
    }

    return "$title\n$text"
}
