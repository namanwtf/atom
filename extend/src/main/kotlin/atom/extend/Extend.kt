package atom.extend

import atom.core.agent.RunControl
import atom.core.llm.LlmEngine
import atom.core.llm.ReasoningEffort
import atom.core.tool.Tool

/** Context window of the model the next run will use. */
fun interface ContextWindowProvider {
    /** Tokens, or null when unknown (memory then assumes [DEFAULT_CONTEXT_WINDOW]). */
    fun currentContextWindow(): Int?

    companion object {
        const val DEFAULT_CONTEXT_WINDOW = 32_768
    }
}

sealed interface SecretRead {
    data object Missing : SecretRead
    data class Value(val value: String) : SecretRead

    /** The secret exists but cannot be decrypted (key lost after a restore, for example); ask the user again. */
    data object Unreadable : SecretRead
}

/** Encrypted credential storage. Values never leave it except to authenticate the request they belong to. */
interface SecretStore {
    suspend fun read(key: String): SecretRead
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}

/** A model the user can pick for subagents. */
data class SubagentModelOption(val id: String, val label: String)

class SubagentEngine(
    val engine: LlmEngine,
    val model: String,
    val contextWindow: Int? = null,
    /** True for on-device engines: parallel subagents then run one after another. */
    val onDevice: Boolean = engine.capabilities.onDevice,
    val release: suspend () -> Unit = {},
    /**
     * Output tokens a request with the given reasoning may use (more when the model thinks, so the reasoning does not
     * crowd out the answer); null keeps the runner's default.
     */
    val outputTokens: (ReasoningEffort?) -> Int? = { null },
    /** Reasoning for the retry after a cut-off answer ([atom.core.agent.AgentConfig.briefReasoning]). */
    val briefReasoning: ReasoningEffort? = null,
)

/** Supplies the model a subagent runs on. */
interface SubagentEngineProvider {
    /**
     * [modelId] null means "follow the main model" of the parent run identified by [parentTaskId]. [role] is the
     * subagent's name (explore, coder, plan, a custom name or "general").
     */
    suspend fun acquire(role: String, modelId: String?, parentTaskId: String): SubagentEngine

    /** Models offered in Subagent settings. */
    fun models(): List<SubagentModelOption> = emptyList()
}

/** What a tool is for, so subagents can be given a subset of the parent's tools. */
enum class ToolCategory { Read, Web, File, Terminal, Mcp, Memory, Device, PhoneUi, Internal }

/** Gives subagents access to the tools of the run that spawned them. */
interface ToolScope {
    /** Every tool of the run [taskId] (the parent's [atom.core.tool.ToolContext.taskId]). */
    fun toolsFor(taskId: String): List<Tool>

    /** Overrides the built-in name/risk heuristics; null keeps them. */
    fun categoryOf(tool: Tool): ToolCategory? = null

    /** The parent's run control, so subagent phone tools wait for the phone lease and pause with the parent. */
    fun controlFor(taskId: String): RunControl = RunControl.None

    /**
     * How long one subagent may run. Agent tool calls are bounded by the parent's tool timeout; when that is
     * longer than this, raise it. On expiry the subagent returns what it has so far.
     */
    val subagentTimeBudgetMs: Long get() = atom.core.agent.ModelLimits.SUBAGENT_MS
}

/** Wiring the app provides once, from Application.onCreate. Everything has a working default except subagents. */
object Extend {
    @Volatile var contextWindow: ContextWindowProvider = ContextWindowProvider { null }
        private set

    @Volatile var secrets: SecretStore? = null
        private set

    @Volatile var engines: SubagentEngineProvider? = null
        private set

    @Volatile var toolScope: ToolScope? = null
        private set

    /**
     * The saved MCP servers' tokens and the secret parts of their URLs, for the problem report's redaction only.
     * Empty when the extend runtime is not set up yet.
     */
    suspend fun mcpSecrets(): List<String> = runCatching { ExtendRuntime.mcp.knownSecrets() }.getOrDefault(emptyList())

    fun install(
        contextWindow: ContextWindowProvider? = null,
        secrets: SecretStore? = null,
        engines: SubagentEngineProvider? = null,
        toolScope: ToolScope? = null,
    ) {
        contextWindow?.let { this.contextWindow = it }
        secrets?.let { this.secrets = it }
        engines?.let { this.engines = it }
        toolScope?.let { this.toolScope = it }
    }
}
