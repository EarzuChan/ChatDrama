package me.earzuchan.chatdrama.framework.llm

import me.earzuchan.chatdrama.framework.llm.misc.asText

// 重建的第一阶段：先搞瘦闭合波
internal fun List<SessionNode>.toThinPath(): List<SessionNode> {
    if (isEmpty()) return this

    val result = mutableListOf<SessionNode>()
    val tempWave = mutableListOf<SessionNode>()

    fun flush() { // 整完前一波到Result
        if (tempWave.isEmpty()) return

        result += tempWave.thinIfClosedWave()
        tempWave.clear()
    }

    forEach { node ->
        if (node is TurnRequestNode && node.request.isPromptRequest()) {
            flush()
            tempWave += node
        } else if (tempWave.isNotEmpty()) tempWave += node else result += node
    }

    flush() // 未闭合的Last波，直接成为最新一波

    return if (result == this) this else result
}

// TIPS：这个玩意也被GeminiBackend使用了，God Damn！
internal fun TurnResult.hasToolCalls() = items.any { it is TurnItem.ToolCall }

private fun List<SessionNode>.thinIfClosedWave(): List<SessionNode> {
    val first = firstOrNull() as? TurnRequestNode ?: return this
    val last = lastOrNull() as? TurnResultNode ?: return this
    if (!last.result.isClosedFinalAnswer()) return this

    val prompt = first.copy(request = first.request.toOnlyPrompt())
    val answer = last.copy(result = last.result.toOnlyFinalAnswer())
    return listOf(prompt, answer)
}

private fun TurnRequest.isPromptRequest() = items.any { it is TurnInputItem.Content }

private fun TurnResult.isClosedFinalAnswer() = !hasToolCalls() && finalAnswerItems().isNotEmpty()

private fun TurnResult.finalAnswerItems() = items.filter {
    when (it) {
        is TurnItem.Content -> it.asText().isNotBlank()
        is TurnItem.Refusal -> !it.text.isNullOrBlank()
        is TurnItem.Reasoning, is TurnItem.ToolCall -> false
    }
}

private fun TurnResult.toOnlyFinalAnswer() = copy(items = finalAnswerItems(), trace = trace.copy(raw = null), blackboard = LlmBlackboard.Empty)

private fun TurnRequest.toOnlyPrompt() = copy(items = items.filterIsInstance<TurnInputItem.Content>(), blackboard = LlmBlackboard.Empty)
