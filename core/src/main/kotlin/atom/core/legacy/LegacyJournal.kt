package atom.core.legacy

import atom.core.llm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The previous app's model-level transcript (`conversation_context_checkpoints.journal_json`, or `history_json`
 * when the journal is empty): full tool-call arguments, tool results and per-turn reasoning, which the visible
 * timeline only previews. Compaction summaries are skipped; they are never shown as messages.
 */
class LegacyJournal private constructor(
    /** Tool calls by call id. */
    val calls: Map<String, ToolCall>,
    /** Tool result text by call id. */
    val results: Map<String, String>,
    /** Reasoning text of assistant turns by their `messageId`. */
    val reasoning: Map<String, String>,
) {
    val isEmpty: Boolean get() = calls.isEmpty() && results.isEmpty() && reasoning.isEmpty()

    /**
     * The call id behind a visible tool row. Rows are named `<runId>-tool-<round>-<toolCallId>`; when that pattern
     * does not match a known call, the longest known id the row name ends with is used.
     */
    fun callIdFor(rowId: String): String? {
        TOOL_ROW.find(rowId)?.groupValues?.get(1)?.let { if (it in calls || it in results) return it }
        return (calls.keys.asSequence() + results.keys.asSequence())
            .filter { rowId.endsWith("-$it") }
            .maxByOrNull { it.length }
    }

    /** Reasoning of the model turn behind a visible assistant row (`assistant-<runId>-<round>[-result|-retry-n]`). */
    fun reasoningFor(rowId: String): String? {
        val candidates = buildList {
            add(rowId)
            add(rowId.removeSuffix("-result"))
            RETRY.find(rowId)?.let { add(it.groupValues[1] + "-" + it.groupValues[2]) }
        }
        return candidates.firstNotNullOfOrNull { reasoning[it] }
    }

    companion object {
        val EMPTY = LegacyJournal(emptyMap(), emptyMap(), emptyMap())

        private val TOOL_ROW = Regex("-tool-\\d+-(.+)$")
        private val RETRY = Regex("^(.*)-retry-(\\d+)$")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Reads a journal; anything unreadable gives [EMPTY] (the visible timeline is still imported). */
        fun parse(text: String?): LegacyJournal {
            if (text.isNullOrBlank()) return EMPTY
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return EMPTY
            val calls = LinkedHashMap<String, ToolCall>()
            val results = LinkedHashMap<String, String>()
            val reasoning = LinkedHashMap<String, String>()
            for (element in root) {
                val o = element as? JsonObject ?: continue
                if (o.bool("contextSummary") == true) continue
                when (o.str("role")) {
                    "assistant" -> {
                        val messageId = o.str("messageId").orEmpty()
                        val thought = o.str("reasoningContent").orEmpty()
                        if (messageId.isNotEmpty() && thought.isNotBlank()) reasoning[messageId] = thought
                        toolCalls(o.str("toolCallsJson")).forEach { calls[it.id] = it }
                    }
                    "tool" -> {
                        val id = o.str("toolCallId").orEmpty()
                        if (id.isNotEmpty()) results[id] = o.str("content").orEmpty()
                    }
                    else -> Unit
                }
            }
            return LegacyJournal(calls, results, reasoning)
        }

        /** An OpenAI Chat Completions `tool_calls` array, encoded as a string. */
        internal fun toolCalls(encoded: String?): List<ToolCall> {
            if (encoded.isNullOrBlank()) return emptyList()
            val array = runCatching { json.parseToJsonElement(encoded) }.getOrNull() as? JsonArray ?: return emptyList()
            return array.mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val function = o["function"] as? JsonObject
                val name = function?.str("name") ?: o.str("name") ?: return@mapNotNull null
                val arguments = when (val raw = function?.get("arguments") ?: o["arguments"]) {
                    null -> "{}"
                    is JsonPrimitive -> if (raw.isString) raw.content else raw.toString()
                    else -> raw.toString()
                }
                ToolCall(id, name, arguments.ifBlank { "{}" })
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
