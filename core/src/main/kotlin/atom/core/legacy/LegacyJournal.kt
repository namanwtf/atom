package atom.core.legacy

import atom.core.llm.ToolCall
import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeToSequence
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

    /**
     * Reasoning of the model turn behind a visible assistant row. Rows are `assistant-<runId>-<round>-<index>` (or
     * `-result`, `-retry-<round>`); the transcript names the turn `assistant-<runId>-<round>`.
     */
    fun reasoningFor(rowId: String): String? {
        val candidates = buildList {
            add(rowId)
            add(rowId.removeSuffix("-result"))
            RETRY.find(rowId)?.let { add(it.groupValues[1] + "-" + it.groupValues[2]) }
            TRAILING_INDEX.find(rowId)?.let { add(it.groupValues[1]) }
        }
        return candidates.firstNotNullOfOrNull { reasoning[it] }
    }

    companion object {
        val EMPTY = LegacyJournal(emptyMap(), emptyMap(), emptyMap())

        private val TOOL_ROW = Regex("-tool-\\d+-(.+)$")
        private val RETRY = Regex("^(.*)-retry-(\\d+)$")
        private val TRAILING_INDEX = Regex("^(.*)-\\d+$")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Reads a journal held in memory; see [parse] for streams. */
        fun parse(text: String?): LegacyJournal =
            if (text.isNullOrBlank()) EMPTY else parse(text.byteInputStream(Charsets.UTF_8), Int.MAX_VALUE)

        /**
         * Reads a journal one element at a time, so a transcript of many megabytes never has to be in memory at once;
         * only calls, results (each cut to [maxResultChars]) and reasoning are kept. A damaged journal keeps what was
         * read before the damage; an unreadable one gives [EMPTY] (the visible timeline is still imported).
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun parse(input: InputStream, maxResultChars: Int): LegacyJournal {
            val calls = LinkedHashMap<String, ToolCall>()
            val results = LinkedHashMap<String, String>()
            val reasoning = LinkedHashMap<String, String>()
            try {
                json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).forEach { element ->
                    val o = element as? JsonObject ?: return@forEach
                    if (o.bool("contextSummary") == true) return@forEach
                    when (o.str("role")) {
                        "assistant" -> {
                            val messageId = o.str("messageId").orEmpty()
                            val thought = o.str("reasoningContent").orEmpty()
                            if (messageId.isNotEmpty() && thought.isNotBlank()) reasoning[messageId] = thought.take(maxResultChars)
                            toolCalls(o.str("toolCallsJson")).forEach { calls[it.id] = it }
                        }
                        "tool" -> {
                            val id = o.str("toolCallId").orEmpty()
                            if (id.isNotEmpty()) results[id] = o.str("content").orEmpty().let { if (it.length > maxResultChars) it.take(maxResultChars + 1) else it }
                        }
                        else -> Unit
                    }
                }
            } catch (e: SerializationException) {
                // Keep what was read.
            } catch (e: IllegalArgumentException) {
                // Not a JSON array, or damaged mid-way.
            } catch (e: IllegalStateException) {
                // The stream ended inside an element.
            }
            return if (calls.isEmpty() && results.isEmpty() && reasoning.isEmpty()) EMPTY else LegacyJournal(calls, results, reasoning)
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
