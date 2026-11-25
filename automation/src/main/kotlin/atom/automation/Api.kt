package atom.automation

import atom.core.llm.LlmEngine
import atom.core.llm.ReasoningEffort
import atom.core.llm.ToolSpec
import atom.core.tool.ToolResult
import atom.ui.feature.Atom
import atom.ui.feature.RunSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What a scheduled run or an automation's agent step may touch. An empty set means chat only. */
@Serializable
enum class Capability(val tag: String, val label: String) {
    @SerialName("web") Web("web", "Web & browser"),
    @SerialName("device_read") DeviceRead("device_read", "Read device data"),
    @SerialName("device_control") DeviceControl("device_control", "Device controls"),
    @SerialName("terminal") Terminal("terminal", "Terminal, files & Linux"),
    @SerialName("mcp") Mcp("mcp", "MCP servers"),
    @SerialName("app_ui") AppUi("app_ui", "App UI automation"),
    ;

    companion object {
        val DEFAULT: Set<Capability> = setOf(Web, DeviceRead)
        fun fromTag(tag: String): Capability? = entries.firstOrNull { it.tag == tag.trim().lowercase() || it.name.equals(tag.trim(), true) }
        fun describe(set: Set<Capability>): String = if (set.isEmpty()) "Chat only" else entries.filter { it in set }.joinToString(", ") { it.label }
    }
}

/** Everything needed to start one unattended run. */
data class RunRequest(
    val prompt: String,
    /** Null starts a fresh conversation (isolated archive). */
    val conversationId: String?,
    val source: RunSource,
    /** Tool families the run may use; the runtime must hide every other tool. Empty = no tools. */
    val capabilities: Set<Capability>,
    /** Null follows the global model for [source]. */
    val modelId: String?,
    /** Null follows the global reasoning setting. */
    val reasoning: ReasoningEffort?,
    val title: String,
    /** Show the live "Working" notification while running; false keeps the run silent. */
    val showProgress: Boolean,
)

/**
 * Starts unattended runs. The default goes straight through [Atom.host] and can only hint the capability limits in
 * the prompt; the app should install one that applies [RunRequest.capabilities], model and reasoning for real.
 */
fun interface RunLauncher {
    /** The conversation id of the started run, or null when that conversation is already running. */
    fun start(request: RunRequest): String?
}

object HostRunLauncher : RunLauncher {
    override fun start(request: RunRequest): String? {
        val header = "[${request.title} · allowed: ${Capability.describe(request.capabilities)}]\n"
        return Atom.host.start(header + request.prompt, request.conversationId, request.source)
    }
}

data class ModelChoice(val id: String, val label: String)

/** Models a task can pin. The default offers only "Follow global". */
interface ModelChoiceProvider {
    fun choices(): List<ModelChoice>
    fun label(id: String?): String = if (id == null) "Follow global" else choices().firstOrNull { it.id == id }?.label ?: id
}

object FollowGlobalModels : ModelChoiceProvider {
    override fun choices(): List<ModelChoice> = emptyList()
}

data class EngineSelection(val engine: LlmEngine, val model: String, val reasoning: ReasoningEffort? = null)

/** Supplies the model used once, when a sentence is compiled into an automation. */
fun interface EngineProvider {
    /** Null when no model is configured. */
    suspend fun current(): EngineSelection?
}

/** Calls agent tools by name outside a chat run (device controls, settings, anything in the live registry). */
interface ToolInvoker {
    fun available(): List<ToolSpec>
    suspend fun invoke(name: String, args: JsonObject): ToolResult
}

/** Lets UI-automation tasks run while the phone is locked (virtual display). */
fun interface BackgroundDisplay {
    fun available(): Boolean
}

/** Fed by the notification listener. Cheap to call: it returns at once when no automation listens. */
interface NotificationEvents {
    /** False when no enabled automation has a notification trigger; the listener may skip calling. */
    val wanted: Boolean
    fun onNotificationPosted(packageName: String, appLabel: String?, title: String?, text: String?)
}

/** Fed by the accessibility service on foreground-app changes. */
interface AppOpenEvents {
    /** Packages some enabled automation watches; empty means nobody listens. */
    val watchedPackages: Set<String>
    fun onAppOpened(packageName: String)
}
