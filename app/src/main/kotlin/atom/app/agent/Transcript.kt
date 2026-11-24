package atom.app.agent

import atom.app.data.Conversation
import atom.core.llm.Role
import atom.core.tool.Risk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class StepStatus { Running, AwaitingApproval, Ok, Error }

/** What the chat list renders: built from a stored conversation or streamed live by the TaskRunner. */
sealed interface TranscriptItem {
    val key: String

    data class User(override val key: String, val text: String) : TranscriptItem

    data class Assistant(override val key: String, val text: String) : TranscriptItem

    data class Step(
        override val key: String,
        val name: String,
        val argsLine: String,
        val argsFull: String,
        val status: StepStatus,
        val durationMs: Long?,
        val result: String?,
        val risk: Risk?,
    ) : TranscriptItem
}

object Transcript {
    private val json = Json { isLenient = true }
    private val pretty = Json { prettyPrint = true }

    fun of(conversation: Conversation): List<TranscriptItem> {
        val out = ArrayList<TranscriptItem>(conversation.messages.size)
        val msgs = conversation.messages
        var i = 0
        while (i < msgs.size) {
            val stored = msgs[i]
            val msg = stored.message
            when (msg.role) {
                Role.User -> if (!stored.hidden && msg.text.isNotBlank()) out += TranscriptItem.User("u$i", msg.text)
                Role.Assistant -> {
                    val text = msg.text.trim()
                    if (text.isNotEmpty()) out += TranscriptItem.Assistant("a$i", text)
                    // Tool results directly follow their assistant message; pair them by id within that window
                    // (prompted on-device engines reuse ids across turns, so a global match would be wrong).
                    var j = i + 1
                    val results = ArrayList<Int>()
                    while (j < msgs.size && msgs[j].message.role == Role.Tool) results += j++
                    msg.toolCalls.forEachIndexed { k, call ->
                        val match = results.firstOrNull { msgs[it].message.toolCallId == call.id } ?: results.firstOrNull()
                        if (match != null) results.remove(match)
                        val result = match?.let { msgs[it] }
                        out += TranscriptItem.Step(
                            key = "s$i.$k",
                            name = call.name,
                            argsLine = argsLine(call.argumentsJson),
                            argsFull = argsFull(call.argumentsJson),
                            status = if (result == null || result.isError) StepStatus.Error else StepStatus.Ok,
                            durationMs = result?.durationMs,
                            result = result?.message?.text?.let(::preview),
                            risk = result?.risk,
                        )
                    }
                    i = j
                    continue
                }
                Role.Tool, Role.System -> Unit
            }
            i++
        }
        return out
    }

    fun preview(text: String): String = if (text.length <= 600) text else text.take(600) + "…"

    /** `{"x":10,"y":20}` → `x=10, y=20`, short enough for one line. */
    fun argsLine(raw: String): String {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return raw.replace('\n', ' ').take(120)
        return obj.entries.joinToString(", ") { (k, v) ->
            val value = when (v) {
                is JsonPrimitive -> if (v.isString) "\"${v.content.replace('\n', ' ')}\"" else v.content
                is JsonNull -> "null"
                is JsonArray -> "[${v.size}]"
                is JsonObject -> "{…}"
            }
            "$k=${if (value.length > 60) value.take(57) + "…\"" else value}"
        }
    }

    fun argsFull(raw: String): String =
        runCatching { pretty.encodeToString(JsonObject.serializer(), json.parseToJsonElement(raw) as JsonObject) }
            .getOrDefault(raw)
}
