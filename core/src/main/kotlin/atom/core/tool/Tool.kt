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
}

interface Tool {
    val spec: ToolSpec
    val risk: Risk

    /**
     * True when the tool drives the shared phone UI (reading the screen, gestures, typing, opening apps). Only one
     * run at a time may do that; the runtime makes other runs wait for phone control before calling such a tool.
     */
    val usesPhoneUi: Boolean get() = false

    suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
}

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
