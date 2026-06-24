package me.earzuchan.chatdrama.framework.llm.misc

import me.earzuchan.chatdrama.framework.llm.*

internal class TurnDraft(private val output: OutputContract, private val observer: TurnObserver? = null) {
    private val items = mutableListOf<DraftItem>()
    private val completedIds = mutableSetOf<String>()

    suspend fun appendContent(text: String) {
        if (text.isEmpty()) return

        val item = lastContent() ?: DraftItem.Content(newItemId(), StringBuilder()).also {
            items += it
            observer?.onEvent(TurnEvent.ItemStarted(it.id, TurnItemKind.Content))
        }
        item.text.append(text)

        observer?.onEvent(TurnEvent.ItemDelta(item.id, TurnItemDelta.Text(text)))
    }

    suspend fun appendReasoning(text: String, kind: ReasoningKind = ReasoningKind.Summary, blackboard: LlmBlackboard = LlmBlackboard.Empty) {
        if (text.isEmpty() && blackboard.isEmpty) return

        val item = lastReasoning(kind) ?: DraftItem.Reasoning(newItemId(), StringBuilder(), kind, blackboard).also {
            items += it
            observer?.onEvent(TurnEvent.ItemStarted(it.id, TurnItemKind.Reasoning))
        }
        item.text.append(text)
        if (!blackboard.isEmpty) item.blackboard = item.blackboard.withAll(blackboard)

        if (text.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(item.id, TurnItemDelta.Text(text)))
    }

    suspend fun appendRefusal(text: String?, safety: SafetyInfo? = null) {
        val item = DraftItem.Refusal(newItemId(), StringBuilder(), safety)

        items += item
        observer?.onEvent(TurnEvent.ItemStarted(item.id, TurnItemKind.Refusal))
        if (!text.isNullOrEmpty()) {
            item.text.append(text)
            observer?.onEvent(TurnEvent.ItemDelta(item.id, TurnItemDelta.Text(text)))
        }

        completeItem(item)
    }

    suspend fun startTool(id: String, name: String, blackboard: LlmBlackboard = LlmBlackboard.Empty) {
        if (items.any { it is DraftItem.ToolCall && it.callId == id }) return

        val item = DraftItem.ToolCall(newItemId(), id, name, StringBuilder(), blackboard)
        items += item

        observer?.onEvent(TurnEvent.ItemStarted(item.id, TurnItemKind.ToolCall))
    }

    suspend fun appendToolArguments(id: String, name: String, delta: String) {
        val item = items.filterIsInstance<DraftItem.ToolCall>().lastOrNull { it.callId == id } ?: DraftItem.ToolCall(newItemId(), id, name, StringBuilder()).also {
            items += it
            observer?.onEvent(TurnEvent.ItemStarted(it.id, TurnItemKind.ToolCall))
        }
        item.arguments.append(delta)

        if (delta.isNotEmpty()) observer?.onEvent(TurnEvent.ItemDelta(item.id, TurnItemDelta.ToolArguments(delta)))
    }

    suspend fun completeTool(id: String) {
        val item = items.filterIsInstance<DraftItem.ToolCall>().lastOrNull { it.callId == id } ?: return

        completeItem(item)
    }

    suspend fun complete(usage: TokenUsage? = null, trace: TurnTrace = TurnTrace(), blackboard: LlmBlackboard = LlmBlackboard.Empty): TurnResult {
        val result = TurnResult(items.map { it.freeze(output) }, usage, trace, blackboard)

        items.forEach { completeItem(it) }
        observer?.onEvent(TurnEvent.Completed(result))
        return result
    }

    suspend fun completeWith(result: TurnResult): TurnResult {
        items.forEach { completeItem(it) }
        observer?.onEvent(TurnEvent.Completed(result))
        return result
    }

    fun partial(usage: TokenUsage? = null, trace: TurnTrace = TurnTrace(), blackboard: LlmBlackboard = LlmBlackboard.Empty) = TurnResult(items.map { it.freeze(output) }, usage, trace, blackboard)
    fun isEmpty() = items.isEmpty()

    private fun lastContent() = items.lastOrNull() as? DraftItem.Content
    private fun lastReasoning(kind: ReasoningKind) = (items.lastOrNull() as? DraftItem.Reasoning)?.takeIf { it.kind == kind }
    private fun newItemId() = "item_${items.size}"
    private suspend fun completeItem(item: DraftItem) {
        if (completedIds.add(item.id)) observer?.onEvent(TurnEvent.ItemCompleted(item.id, item.freeze(output)))
    }
}

private sealed interface DraftItem {
    val id: String
    fun freeze(output: OutputContract): TurnItem

    data class Content(override val id: String, val text: StringBuilder) : DraftItem {
        override fun freeze(output: OutputContract) = TurnItem.Content(text.toString().toOutputBody(output))
    }

    data class Reasoning(override val id: String, val text: StringBuilder, val kind: ReasoningKind, var blackboard: LlmBlackboard = LlmBlackboard.Empty) : DraftItem {
        override fun freeze(output: OutputContract) = TurnItem.Reasoning(text.toString().ifBlank { null }, kind, blackboard)
    }

    data class ToolCall(override val id: String, val callId: String, val name: String, val arguments: StringBuilder, val blackboard: LlmBlackboard = LlmBlackboard.Empty) : DraftItem {
        override fun freeze(output: OutputContract) = TurnItem.ToolCall(callId, name, arguments.toString().toJsonObjectLenient(), blackboard)
    }

    data class Refusal(override val id: String, val text: StringBuilder, val safety: SafetyInfo? = null) : DraftItem {
        override fun freeze(output: OutputContract) = TurnItem.Refusal(text.toString().ifBlank { null }, safety)
    }
}
