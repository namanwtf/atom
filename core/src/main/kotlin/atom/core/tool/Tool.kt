package atom.core.tool

import atom.core.llm.ContentPart
import atom.core.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

/** How much damage a tool can do; drives approval policy. */
enum class Risk {
    /** Observes only: reading the screen, listing apps. */
    Read,

    /** Changes UI state the user can see and undo: taps, typing, opening apps. */
    Act,

    /** Hard to undo or privacy-relevant: shell, sending, deleting, system settings. Asks the user first. */
    Sensitive,
}

data class ToolResult(
    val text: String,
    val images: List<ContentPart.Image> = emptyList(),
    val isError: Boolean = false,
    /**
     * Personal or secret data (one-time codes, notification text, Wi-Fi passwords, contacts, logs...). The model
     * sees it for the rest of the current turn only; saved history keeps a placeholder instead.
     */
    val sensitive: Boolean = false,
) {
    fun toParts(): List<ContentPart> = listOf(ContentPart.Text(text)) + images

    companion object {
        fun ok(text: String) = ToolResult(text)
        fun error(text: String) = ToolResult(text, isError = true)
        fun sensitive(text: String) = ToolResult(text, sensitive = true)
    }
}

interface ToolContext {
    val taskId: String

    /** Ask the user to approve [action]. Returns false if declined or nobody answers. */
    suspend fun confirm(action: String): Boolean

    /** Ask the user to approve [request]; contexts that show more than a line of text override this. */
    suspend fun approve(request: ApprovalRequest): Boolean = confirm(request.action)
}

/**
 * What an approval prompt shows and how it may be answered.
 *
 * @property action the full action, e.g. the whole command line (shown scrollable, never cut).
 * @property reason one line on why it asks, or null.
 * @property taskGrantable a shell-type call: the user may allow all such calls for the rest of this task.
 * @property alwaysAsk asks even when that grant is on (the call looks plainly destructive).
 */
data class ApprovalRequest(
    val action: String,
    val reason: String? = null,
    val taskGrantable: Boolean = false,
    val alwaysAsk: Boolean = false,
)

interface Tool {
    val spec: ToolSpec
    val risk: Risk

    /**
     * True when the tool drives the shared phone UI (reading the screen, gestures, typing, opening apps). Only one
     * run at a time may do that; the runtime makes other runs wait for phone control before calling such a tool.
     */
    val usesPhoneUi: Boolean get() = false

    /**
     * How long one call may run before it is cut off, overriding the run's default tool timeout. Long tools
     * (subagents, downloads, builds) return their own limit; null uses the default.
     */
    val timeoutMs: Long? get() = null

    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult

    /**
     * The risk of one call with [args], which decides whether it asks the user first. By default the tool's [risk];
     * tools whose danger depends on what they are asked to do (a shell running `ls` or `rm -rf /sdcard`) judge each
     * call, see [ShellRisk]. [risk] stays the worst case, for scoping and listings. Must not throw.
     */
    fun riskFor(args: JsonObject): Risk = risk

    /** The approval prompt for a call with [args], or null for the default (the tool name and its arguments). Must not throw. */
    fun approvalRequest(args: JsonObject): ApprovalRequest? = null

    /**
     * Plain-English text for the approval a [Risk.Sensitive] call asks for, e.g. what an automation compiles to.
     * Null (the default) shows the tool name and its arguments. Must not throw; wrappers forward it.
     */
    suspend fun approvalText(args: JsonObject): String? = null
}

/**
 * Marks a tool that judges each call with [Tool.riskFor] (its [Tool.risk] is the worst case), so wrappers that add
 * their own confirmation can tell a call the runtime already asks about from one that runs without asking.
 */
interface PerCallRisk

class ToolRegistry(tools: Iterable<Tool>) {
    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }

    init {
        require(byName.size == tools.count()) { "Duplicate tool names" }
    }

    val specs: List<ToolSpec> get() = byName.values.map { it.spec }

    val all: Collection<Tool> get() = byName.values

    operator fun get(name: String): Tool? = byName[name]

    operator fun plus(other: ToolRegistry) = ToolRegistry(byName.values + other.byName.values)
}
