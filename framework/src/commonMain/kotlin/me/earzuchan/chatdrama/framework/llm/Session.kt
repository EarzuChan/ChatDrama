package me.earzuchan.chatdrama.framework.llm

import kotlinx.serialization.json.JsonObject

data class LlmNodeId(val value: String) { override fun toString() = value }

data class RootRevision(val id: LlmNodeId, val root: SessionRoot, val parentId: LlmNodeId? = null)

sealed interface SessionNode {
    val id: LlmNodeId
    val parentId: LlmNodeId?
    val rootId: LlmNodeId
    val blackboard: LlmBlackboard
}

data class TurnRequestNode(override val id: LlmNodeId, override val parentId: LlmNodeId?, override val rootId: LlmNodeId, val request: TurnRequest, val config: LlmCallConfig = LlmCallConfig(), override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : SessionNode

data class TurnResultNode(override val id: LlmNodeId, override val parentId: LlmNodeId?, override val rootId: LlmNodeId, val result: TurnResult, override val blackboard: LlmBlackboard = LlmBlackboard.Empty) : SessionNode

interface LaneA {
    val activeRoot: RootRevision
    val activeNode: SessionNode?
    fun activePath(): List<SessionNode>
    fun activeBlackboard(): LlmBlackboard
    fun node(id: LlmNodeId): SessionNode?
}

interface ProviderLaneB {
    val shape: ProviderShape
    val rootRevisionId: LlmNodeId
    val anchorNodeId: LlmNodeId?
    val configKey: ProviderLaneBConfigKey
    val blackboard: LlmBlackboard
}

data class ProviderLaneBConfigKey(val model: String, val reasoning: ReasoningLevel, val cache: CachePreference, val remoteState: RemoteStatePreference, val output: OutputContract, val providerOptions: JsonObject = emptyJsonObject())

data class ProviderBinding(val backend: ProviderBackend, var laneB: ProviderLaneB? = null, var defaults: LlmCallConfig = LlmCallConfig())

data class ProviderTurn<out B : ProviderLaneB>(val laneB: B, val requestNode: TurnRequestNode, val resultNodeId: LlmNodeId, val config: EffectiveLlmCallConfig, val sessionBlackboard: LlmBlackboard)

data class ProviderTurnCommit<out B : ProviderLaneB>(val laneB: B, val result: TurnResult)

class LlmSession private constructor(private val sessionTag: String, private val roots: MutableMap<LlmNodeId, RootRevision>, private val nodes: MutableMap<LlmNodeId, SessionNode>, private var activeRootId: LlmNodeId, private var activeNodeId: LlmNodeId?, var provider: ProviderBinding, var defaults: LlmCallConfig, private val sessionBlackboard: LlmBlackboard, private var nextOrdinal: Int) : LaneA {
    constructor(backend: ProviderBackend, root: SessionRoot = SessionRoot(), defaults: LlmCallConfig = LlmCallConfig(), blackboard: LlmBlackboard = LlmBlackboard.Empty, providerDefaults: LlmCallConfig = LlmCallConfig()) : this(newSessionTag(), mutableMapOf(), mutableMapOf(), LlmNodeId("pending"), null, ProviderBinding(backend, defaults = providerDefaults), defaults, blackboard, 0) {
        val rootId = newId("root")
        roots[rootId] = RootRevision(rootId, root)
        activeRootId = rootId
    }

    override val activeRoot get() = roots.getValue(activeRootId)
    override val activeNode get() = activeNodeId?.let(nodes::get)
    val blackboard get() = activeBlackboard()

    // 发送一次TurnRequest
    suspend fun request(request: TurnRequest, config: LlmCallConfig = LlmCallConfig(), mode: RequestMode = RequestMode.Streamed()): TurnResult {
        val requestNode = TurnRequestNode(newId("request"), activeNodeId, activeRootId, request, config, request.blackboard)
        val resultNodeId = newId("result")
        val effective = resolveConfig(config)
        val activePath = activePath()

        try {
            var laneB = ensureLaneB(effective, activePath)
            laneB = provider.backend.maybeCompact(laneB, requestNode, effective)
            provider.laneB = laneB

            val commit = provider.backend.request(ProviderTurn<ProviderLaneB>(laneB, requestNode, resultNodeId, effective, blackboardOf(activePath, requestNode)), mode)
            val result = commit.result
            val resultNode = TurnResultNode(resultNodeId, requestNode.id, activeRootId, result, result.blackboard)

            nodes[requestNode.id] = requestNode
            nodes[resultNode.id] = resultNode
            activeNodeId = resultNode.id
            provider.laneB = commit.laneB

            return result
        } catch (throwable: LlmTurnException) {
            throw throwable
        } catch (throwable: Throwable) {
            throw LlmTurnException(throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "LLM turn failed" }, throwable, trace = TurnTrace(shape = provider.backend.shape, model = effective.model))
        }
    }

    suspend fun compactForProvideRequestSending(config: LlmCallConfig = LlmCallConfig()) = compactLaneB()

    private suspend fun compactLaneB(config: LlmCallConfig = LlmCallConfig()) { // 实为：压缩 LaneB
        val effective = resolveConfig(config)
        provider.laneB = provider.backend.compact(ensureLaneB(effective), effective)
    }

    override fun activePath(): List<SessionNode> {
        val path = mutableListOf<SessionNode>()
        var current = activeNodeId

        while (current != null) {
            val node = nodes[current] ?: break
            path += node
            current = node.parentId
        }

        return path.asReversed()
    }

    override fun node(id: LlmNodeId) = nodes[id]

    override fun activeBlackboard() = blackboardOf(activePath())

    fun debugLaneA() = buildString {
        appendLine("LaneA")
        appendLine("--activeRootId=$activeRootId")
        appendLine("--activeNodeId=$activeNodeId")
        appendLine("--sessionBlackboard=$sessionBlackboard")
        appendLine("--activeBlackboard=${activeBlackboard()}")
        appendLine("--roots:")
        roots.values.forEach { appendLine("----${it.id} parent=${it.parentId} root=${it.root}") }
        appendLine("--nodes:")
        nodes.values.forEach { appendLine("----${it.id} parent=${it.parentId} root=${it.rootId} value=$it") }
        appendLine("--activePath:")
        activePath().forEach { appendLine("----${it.id}") }
    }

    fun debugLaneB() = provider.backend.debugLaneB(provider.laneB)

    fun checkout(nodeId: LlmNodeId?) {
        if (nodeId != null && nodeId !in nodes) error("Unknown node: $nodeId")
        activeNodeId = nodeId
    }

    fun fork(from: LlmNodeId? = activeNodeId): LlmSession {
        if (from != null && from !in nodes) error("Unknown node: $from")
        val reusableLaneB = provider.laneB?.takeIf { it.shape == provider.backend.shape && it.rootRevisionId == activeRootId && it.anchorNodeId == from }
        return LlmSession(newSessionTag(), roots.toMutableMap(), nodes.toMutableMap(), activeRootId, from, ProviderBinding(provider.backend, reusableLaneB, provider.defaults), defaults, sessionBlackboard, nextOrdinal)
    }

    fun switchProvider(backend: ProviderBackend, defaults: LlmCallConfig = LlmCallConfig()) {
        provider = ProviderBinding(backend, defaults = defaults)
    }

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

    // 结合本地配置进行覆写
    private fun resolveConfig(config: LlmCallConfig): EffectiveLlmCallConfig {
        val merged = provider.defaults.over(defaults).over(config)
        return EffectiveLlmCallConfig(merged.model ?: error("No model configured for ${provider.backend.shape}."), merged.reasoning ?: ReasoningLevel.Off, merged.cache ?: CachePreference.Prefer, merged.remoteState ?: RemoteStatePreference.Off, merged.output ?: OutputContract.Text, merged.temperature, merged.providerOptions)
    }

    // 检查：是否失效以致重建LaneB
    private fun ensureLaneB(config: EffectiveLlmCallConfig, path: List<SessionNode> = activePath()): ProviderLaneB {
        val current = provider.laneB
        val key = config.laneBKey(provider.backend.shape)

        if (current != null && current.shape == provider.backend.shape && current.rootRevisionId == activeRootId && current.anchorNodeId == activeNodeId && current.configKey == key) return current
        return provider.backend.rebuildLaneB(activeRoot, path, config, blackboardOf(path)).also { provider.laneB = it }
    }

    private fun blackboardOf(path: List<SessionNode>, extraNode: SessionNode? = null): LlmBlackboard {
        var value = sessionBlackboard.withAll(activeRoot.root.blackboard)
        path.forEach { value = value.withAll(it.blackboard) }
        extraNode?.let { value = value.withAll(it.blackboard) }
        return value
    }

    private fun newId(kind: String) = LlmNodeId("$sessionTag-$kind-${nextOrdinal++}")

    companion object {
        private var nextSessionOrdinal = 0
        private fun newSessionTag() = "session-${nextSessionOrdinal++}"
    }
}

internal fun EffectiveLlmCallConfig.laneBKey(shape: ProviderShape) = ProviderLaneBConfigKey(model, reasoning, cache, remoteState, output, providerOptions[shape] ?: emptyJsonObject())

internal fun <B : ProviderLaneB> ProviderTurn<*>.typedTurn(laneName: String, cast: (ProviderLaneB) -> B?) = ProviderTurn(cast(laneB) ?: error("LaneB is not $laneName: ${laneB::class.simpleName}"), requestNode, resultNodeId, config, sessionBlackboard)
