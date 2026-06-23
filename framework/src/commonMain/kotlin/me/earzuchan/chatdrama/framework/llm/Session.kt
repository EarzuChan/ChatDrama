package me.earzuchan.chatdrama.framework.llm

data class LlmNodeId(val value: String) { override fun toString() = value }

data class RootRevision(val id: LlmNodeId, val root: SessionRoot, val parentId: LlmNodeId? = null)

sealed interface SessionNode {
    val id: LlmNodeId
    val parentId: LlmNodeId?
    val rootId: LlmNodeId
    val blackboard: Blackboard
}

data class TurnRequestNode(override val id: LlmNodeId, override val parentId: LlmNodeId?, override val rootId: LlmNodeId, val request: TurnRequest, val config: LlmCallConfig = LlmCallConfig(), override val blackboard: Blackboard = Blackboard.Empty) : SessionNode

data class TurnResultNode(override val id: LlmNodeId, override val parentId: LlmNodeId?, override val rootId: LlmNodeId, val result: TurnResult, override val blackboard: Blackboard = Blackboard.Empty) : SessionNode

data class ProviderTurn(val rootRevision: RootRevision, val nodes: List<SessionNode>, val requestNode: TurnRequestNode, val config: EffectiveLlmCallConfig, val sessionBlackboard: Blackboard)

class LlmSession private constructor(private val sessionTag: String, val backend: ProviderBackend, private val roots: MutableMap<LlmNodeId, RootRevision>, private val nodes: MutableMap<LlmNodeId, SessionNode>, private var activeRootId: LlmNodeId, private var activeNodeId: LlmNodeId?, var defaults: LlmCallConfig, var blackboard: Blackboard, private var nextOrdinal: Int) {
    constructor(backend: ProviderBackend, root: SessionRoot = SessionRoot(), defaults: LlmCallConfig = LlmCallConfig(), blackboard: Blackboard = Blackboard.Empty) : this(newSessionTag(), backend, mutableMapOf(), mutableMapOf(), LlmNodeId("pending"), null, defaults, blackboard, 0) {
        val rootId = newId("root")
        roots[rootId] = RootRevision(rootId, root)
        activeRootId = rootId
    }

    val activeRoot get() = roots.getValue(activeRootId)
    val activeNode get() = activeNodeId?.let(nodes::get)

    suspend fun request(request: TurnRequest, config: LlmCallConfig = LlmCallConfig(), mode: RequestMode = RequestMode.Streamed()): TurnResult {
        val requestNode = TurnRequestNode(newId("request"), activeNodeId, activeRootId, request, config, request.blackboard)
        val effective = resolveConfig(config)
        val turn = ProviderTurn(activeRoot, activePath() + requestNode, requestNode, effective, blackboard)

        try {
            val result = backend.request(turn, mode)
            val resultNode = TurnResultNode(newId("result"), requestNode.id, activeRootId, result, result.blackboard)
            nodes[requestNode.id] = requestNode
            nodes[resultNode.id] = resultNode
            activeNodeId = resultNode.id
            blackboard = blackboard.withAll(result.blackboard)
            return result
        } catch (throwable: LlmTurnException) {
            throw throwable
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "LLM turn failed" }, throwable, trace = TurnTrace(shape = backend.shape, model = effective.model))
        }
    }

    suspend fun resendLatest(config: LlmCallConfig = LlmCallConfig(), mode: RequestMode = RequestMode.Streamed()): TurnResult {
        val latest = activePath().lastOrNull { it is TurnRequestNode } as? TurnRequestNode ?: error("No request node to resend.")
        val before = activeNodeId
        activeNodeId = latest.parentId

        return try {
            request(latest.request, latest.config.over(config), mode)
        } catch (throwable: Throwable) {
            activeNodeId = before
            throw throwable
        }
    }

    fun activePath(): List<SessionNode> {
        val path = mutableListOf<SessionNode>()
        var current = activeNodeId
        while (current != null) {
            val node = nodes[current] ?: break
            path += node
            current = node.parentId
        }
        return path.asReversed()
    }

    fun node(id: LlmNodeId) = nodes[id]

    fun checkout(nodeId: LlmNodeId?) {
        if (nodeId != null && nodeId !in nodes) error("Unknown node: $nodeId")
        activeNodeId = nodeId
    }

    fun fork(from: LlmNodeId? = activeNodeId) = LlmSession(newSessionTag(), backend, roots.toMutableMap(), nodes.toMutableMap(), activeRootId, from, defaults, blackboard, nextOrdinal)

    fun editRoot(transform: (SessionRoot) -> SessionRoot) {
        val rootId = newId("root")
        roots[rootId] = RootRevision(rootId, transform(activeRoot.root), activeRootId)
        activeRootId = rootId
    }

    fun forkWithEditedRoot(transform: (SessionRoot) -> SessionRoot) = fork().also { it.editRoot(transform) }

    fun replaceRequest(nodeId: LlmNodeId, request: TurnRequest) {
        val old = nodes[nodeId] as? TurnRequestNode ?: error("Node is not a request node: $nodeId")
        val replacement = TurnRequestNode(newId("request"), old.parentId, activeRootId, request, old.config, request.blackboard)
        nodes[replacement.id] = replacement
        activeNodeId = replacement.id
    }

    fun replaceResult(nodeId: LlmNodeId, result: TurnResult) {
        val old = nodes[nodeId] as? TurnResultNode ?: error("Node is not a result node: $nodeId")
        val replacement = TurnResultNode(newId("result"), old.parentId, activeRootId, result, result.blackboard)
        nodes[replacement.id] = replacement
        activeNodeId = replacement.id
    }

    private fun resolveConfig(config: LlmCallConfig): EffectiveLlmCallConfig {
        val merged = backend.defaultConfig.over(defaults).over(config)
        return EffectiveLlmCallConfig(merged.model ?: error("No model configured for ${backend.shape}."), merged.reasoning ?: ReasoningLevel.Off, merged.cache ?: CachePreference.Prefer, merged.remoteState ?: RemoteStatePreference.Off, merged.output ?: OutputContract.Text, merged.temperature, merged.providerOptions)
    }

    private fun newId(kind: String) = LlmNodeId("$sessionTag-$kind-${nextOrdinal++}")

    companion object {
        private var nextSessionOrdinal = 0
        private fun newSessionTag() = "session-${nextSessionOrdinal++}"
    }
}
