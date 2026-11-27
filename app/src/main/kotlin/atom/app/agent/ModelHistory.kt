package atom.app.agent

import atom.app.data.Attachments
import atom.app.data.EntryKind
import atom.app.data.StoredMessage
import atom.core.agent.HistoryEntry
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.Role

/** Builds what the model sees from the stored record, keeping a map back to stored positions. */
object ModelHistory {
    class View(val messages: List<ChatMessage>, val storedIndex: List<Int>)

    /**
     * Everything from the latest compaction summary on, notices left out, attachments described as paths. Tool calls
     * whose results never arrived are dropped from the view (the stored record keeps them) so providers accept it.
     * A tool-call turn carries its stored reasoning only when [replaysReasoning] (the engine sends it back, see
     * [atom.core.llm.EngineCapabilities.replaysReasoning]); otherwise it would only make the context look fuller.
     * User messages that do not start a turn (Atom's nudges, steering sent mid-run) are marked
     * [ChatMessage.midTurn].
     */
    fun build(
        stored: List<StoredMessage>,
        images: Map<Int, List<ContentPart.Image>> = emptyMap(),
        replaysReasoning: Boolean = false,
    ): View {
        val start = stored.indexOfLast { it.kind == EntryKind.Summary }.coerceAtLeast(0)
        val messages = ArrayList<ChatMessage>()
        val index = ArrayList<Int>()
        for (i in start until stored.size) {
            val s = stored[i]
            if (s.kind == EntryKind.Notice) continue
            var msg = s.message
            when (msg.role) {
                Role.User -> {
                    val note = Attachments.describeForModel(s.attachments)
                    val text = listOfNotNull(msg.text.takeIf { it.isNotBlank() }, note).joinToString("\n\n")
                    msg = msg.copy(parts = listOf(ContentPart.Text(text)) + images[i].orEmpty(), midTurn = s.hidden || s.steering || msg.midTurn)
                }
                Role.Assistant -> if (msg.toolCalls.isNotEmpty()) {
                    val answered = answeredIds(stored, i)
                    val kept = msg.toolCalls.filter { it.id in answered }
                    if (kept.size != msg.toolCalls.size) msg = msg.copy(toolCalls = kept)
                    // Thinking vendors want each tool-call turn's reasoning back, also after the app restarted.
                    val reasoning = if (replaysReasoning) (s.reasoning ?: msg.reasoning)?.takeIf { it.isNotBlank() } else null
                    if (reasoning != msg.reasoning) msg = msg.copy(reasoning = reasoning)
                }
                Role.Tool -> {
                    val prev = messages.lastOrNull { it.role != Role.Tool }
                    if (prev == null || prev.role != Role.Assistant || prev.toolCalls.none { it.id == msg.toolCallId }) {
                        msg = ChatMessage.user("[Earlier result of ${msg.toolName ?: "a tool"}]\n" + msg.text).copy(midTurn = true)
                    }
                }
                Role.System -> continue
            }
            if (msg.role == Role.Assistant && msg.parts.isEmpty() && msg.toolCalls.isEmpty()) continue
            messages += msg
            index += i
        }
        return View(messages, index)
    }

    /** Ids of the calls of the assistant message at [at] that have a result right after it. */
    private fun answeredIds(stored: List<StoredMessage>, at: Int): Set<String> {
        val ids = HashSet<String>()
        var j = at + 1
        while (j < stored.size && (stored[j].message.role == Role.Tool || stored[j].kind == EntryKind.Notice)) {
            stored[j].message.toolCallId?.let { ids += it }
            j++
        }
        return ids
    }

    /** The full record for `search_history`. */
    fun searchable(stored: List<StoredMessage>): List<HistoryEntry> = stored.mapIndexedNotNull { i, s ->
        if (s.kind == EntryKind.Notice || s.hidden) return@mapIndexedNotNull null
        val text = buildString {
            append(s.message.text)
            s.message.toolCalls.forEach { append("\n[called ").append(it.name).append(' ').append(it.argumentsJson.take(300)).append(']') }
            Attachments.describeForModel(s.attachments)?.let { append('\n').append(it) }
        }
        if (text.isBlank()) null else HistoryEntry(i, s.message.role, text, s.message.toolName)
    }

    /** Synthetic results for calls that never finished, so a stopped or crashed run leaves a consistent record. */
    fun closeOpenCalls(stored: List<StoredMessage>, reason: String): List<StoredMessage> {
        val lastAssistant = stored.indexOfLast { it.message.role == Role.Assistant && it.kind == EntryKind.Message }
        if (lastAssistant < 0) return emptyList()
        val calls = stored[lastAssistant].message.toolCalls
        if (calls.isEmpty()) return emptyList()
        val answered = answeredIds(stored, lastAssistant)
        return calls.filter { it.id !in answered }.map { call ->
            StoredMessage(ChatMessage.toolResult(call, listOf(ContentPart.Text(reason))), isError = true, createdAt = System.currentTimeMillis())
        }
    }
}
