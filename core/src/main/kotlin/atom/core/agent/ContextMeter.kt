package atom.core.agent

import atom.core.llm.ChatMessage
import atom.core.llm.Role
import atom.core.llm.ToolSpec

/** The context meter's math, shared by the run (what it reports) and the chat's ring (what it draws). */
object ContextMeter {
    /**
     * The prompt cap every cloud run used before the cap followed the model's window. Conversations saved then
     * stored it as their window, so their meter read e.g. 23k of 24k (97%) on a 1M-token model.
     */
    const val LEGACY_CLOUD_CAP = 24_000

    /** Share of [window] that [used] fills, 0..1; null when the window is unknown. */
    fun fraction(used: Int, window: Int?): Float? =
        window?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) }

    /** Where automatic compaction starts, as a share of the window; null when it is off or the window unknown. */
    fun compactMark(window: Int?, autoCompact: Boolean, compactAt: Double = DEFAULT_COMPACT_AT): Float? =
        if (!autoCompact || window == null || window <= 0) null else compactAt.toFloat().coerceIn(0f, 1f)

    /**
     * The window a saved conversation's meter divides by. A window stored as [LEGACY_CLOUD_CAP] was the old cap,
     * not the model's: the model's own usable window ([modelUsable]) replaces it when that is larger.
     */
    fun displayWindow(stored: Int?, modelUsable: Int?): Int? = when {
        stored == LEGACY_CLOUD_CAP && modelUsable != null && modelUsable > stored -> modelUsable
        stored != null -> stored
        else -> modelUsable
    }

    const val DEFAULT_COMPACT_AT = 0.85
}

/** Where a request's tokens go, by part, for the debug log and tests. Estimates use [ContextPacker]'s ~4 chars/token. */
data class RequestSize(
    val systemChars: Int,
    val toolChars: Map<String, Int>,
    val messageChars: Int,
    val toolCount: Int,
) {
    val toolTotalChars: Int get() = toolChars.values.sum()
    val totalChars: Int get() = systemChars + toolTotalChars + messageChars
    val estimatedTokens: Int get() = (totalChars + 3) / 4

    fun describe(): String = buildString {
        append("request ≈").append(estimatedTokens).append(" tokens (").append(totalChars).append(" chars): ")
        append("system ").append(systemChars).append(", tools ").append(toolTotalChars).append(" in ").append(toolCount)
        append(", messages ").append(messageChars)
        if (toolChars.isNotEmpty()) {
            append(" | tools by group: ")
            append(toolChars.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        }
    }

    companion object {
        /** Characters one tool declaration puts on the wire (name, description, JSON schema). */
        fun chars(spec: ToolSpec): Int = spec.name.length + spec.description.length + spec.parameters.toString().length

        fun of(messages: List<ChatMessage>, tools: List<ToolSpec>, groupOf: (ToolSpec) -> String = { "all" }): RequestSize {
            val system = messages.filter { it.role == Role.System }.sumOf { it.text.length }
            val rest = messages.filter { it.role != Role.System }.sumOf { m ->
                m.text.length + m.toolCalls.sumOf { it.name.length + it.argumentsJson.length }
            }
            val byGroup = LinkedHashMap<String, Int>()
            tools.forEach { byGroup[groupOf(it)] = (byGroup[groupOf(it)] ?: 0) + chars(it) }
            return RequestSize(system, byGroup, rest, tools.size)
        }
    }
}
