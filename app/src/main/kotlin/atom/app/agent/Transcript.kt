package atom.app.agent

import atom.app.data.Attachment
import atom.app.data.EntryKind
import atom.app.data.StoredMessage
import atom.core.llm.Role
import atom.core.tool.Risk
import atom.ui.feature.StepState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One tool call as the timeline shows it. */
data class StepCall(
    val key: String,
    val callId: String,
    val name: String,
    val argsLine: String,
    val argsFull: String,
    val state: StepState,
    val durationMs: Long?,
    val result: String?,
    val risk: Risk?,
    /** Wall-clock start while it runs, for the live timer. */
    val startedAt: Long? = null,
    val awaitingApproval: Boolean = false,
    /** Last line of terminal output, shown on the collapsed row. */
    val lastLine: String? = null,
    /** A result the chat shows as a card under the timeline (generated image, browser preview). */
    val card: StepCard? = null,
)

/** Tool results with their own chat card; [text] is what the card composable parses. */
sealed interface StepCard {
    val text: String

    data class Image(override val text: String) : StepCard
    data class Browser(override val text: String) : StepCard
}

sealed interface WorkEntry {
    val key: String

    data class Thought(override val key: String, val text: String, val durationMs: Long?, val live: Boolean = false) : WorkEntry

    /** Text the model wrote between tool calls. */
    data class Note(override val key: String, val text: String) : WorkEntry

    /** Consecutive calls of the same tool, merged into one row. */
    data class Steps(override val key: String, val tool: String, val calls: List<StepCall>) : WorkEntry
}

data class WorkSummary(val thoughts: Int, val toolCalls: Int, val failed: Int, val running: Boolean)

/** What the chat list renders: built from the stored record plus the live stream of a running task. */
sealed interface ChatItem {
    val key: String

    data class User(
        override val key: String,
        val index: Int,
        val text: String,
        val attachments: List<Attachment>,
        val steering: Boolean,
        val edited: Boolean = false,
    ) : ChatItem

    data class Turn(
        override val key: String,
        /** Stored index of the user message that started it, if any. */
        val userIndex: Int?,
        val work: List<WorkEntry>,
        val answer: String?,
        val answerIndex: Int?,
        val answerReasoning: WorkEntry.Thought?,
        val live: Boolean,
        /** Streaming text not yet committed to a message. */
        val streaming: Boolean,
        /** The turn ended (with an answer, or stopped); message actions apply. */
        val finished: Boolean,
        /** Search suggestions the provider attached to the answer (Gemini grounding), shown under it. */
        val answerSuggestions: atom.core.llm.SearchSuggestions? = null,
    ) : ChatItem {
        val summary: WorkSummary
            get() {
                val calls = work.filterIsInstance<WorkEntry.Steps>().flatMap { it.calls }
                val thoughts = work.count { it is WorkEntry.Thought } + if (answerReasoning != null) 1 else 0
                return WorkSummary(thoughts, calls.size, calls.count { it.state == StepState.Failed }, calls.any { it.state == StepState.Running })
            }
    }

    data class Notice(override val key: String, val index: Int, val text: String, val error: Boolean = false) : ChatItem

    data class Compacted(override val key: String, val index: Int, val summary: String) : ChatItem
}

/** Live state of the message being streamed. */
data class StreamState(
    val text: String = "",
    val reasoning: String = "",
    val reasoningStartedAt: Long? = null,
    val reasoningEndedAt: Long? = null,
    val runningCallId: String? = null,
    val runningSince: Long? = null,
    val awaitingApproval: Boolean = false,
    /** The conversation of the running call, so a live image card only shows that conversation's job. */
    val conversationId: String? = null,
) {
    val isEmpty: Boolean get() = text.isEmpty() && reasoning.isEmpty()
}

object Transcript {
    private val json = Json { isLenient = true }
    private val pretty = Json { prettyPrint = true }

    /** [live] is the stream of the running task in this conversation, or null if none runs. */
    fun of(messages: List<StoredMessage>, live: StreamState? = null, now: Long = System.currentTimeMillis()): List<ChatItem> {
        val out = ArrayList<ChatItem>()
        var turn: TurnBuilder? = null
        fun close() {
            turn?.let { out += it.build(live = false, stream = null, now) }
            turn = null
        }
        for ((i, s) in messages.withIndex()) {
            val msg = s.message
            when {
                s.kind == EntryKind.Notice -> {
                    close()
                    out += ChatItem.Notice("n$i", i, msg.text, s.isError)
                }
                s.kind == EntryKind.Summary -> {
                    close()
                    out += ChatItem.Compacted("c$i", i, msg.text.substringAfter('\n').trim())
                }
                s.isVisibleUser -> {
                    close()
                    out += ChatItem.User("u$i", i, msg.text, s.attachments, s.steering, s.edited)
                    turn = TurnBuilder("t$i", i)
                }
                msg.role == Role.User -> Unit
                msg.role == Role.Assistant -> (turn ?: TurnBuilder("t$i", null).also { turn = it }).assistant(i, s)
                msg.role == Role.Tool -> turn?.tool(s)
                else -> Unit
            }
        }
        if (live != null) {
            val t = turn ?: TurnBuilder("t${messages.size}", null)
            out += t.build(live = true, stream = live, now)
        } else {
            close()
        }
        return out
    }

    private class TurnBuilder(val key: String, val userIndex: Int?) {
        val work = ArrayList<WorkEntry>()
        var answer: String? = null
        var answerIndex: Int? = null
        var answerReasoning: WorkEntry.Thought? = null
        var answerSuggestions: atom.core.llm.SearchSuggestions? = null
        val calls = LinkedHashMap<String, StepCall>()

        fun assistant(i: Int, s: StoredMessage) {
            // A previous candidate answer turned out to be interim text.
            demoteAnswer()
            val msg = s.message
            val thought = s.reasoning?.takeIf { it.isNotBlank() }?.let { WorkEntry.Thought("r$i", it.trim(), s.reasoningMs) }
            val text = msg.text.trim()
            if (msg.toolCalls.isEmpty()) {
                answerReasoning = thought
                if (text.isNotEmpty()) {
                    answer = text
                    answerIndex = i
                    answerSuggestions = s.searchSuggestions?.takeIf { !it.isEmpty }
                }
                return
            }
            thought?.let { work += it }
            if (text.isNotEmpty()) work += WorkEntry.Note("a$i", text)
            msg.toolCalls.forEachIndexed { k, call ->
                val step = StepCall(
                    key = "s$i.$k",
                    callId = call.id,
                    name = call.name,
                    argsLine = argsLine(call.argumentsJson),
                    argsFull = argsFull(call.argumentsJson),
                    state = StepState.Unknown,
                    durationMs = null,
                    result = null,
                    risk = null,
                )
                calls["$i:${call.id}"] = step
                addStep(step)
            }
        }

        fun tool(s: StoredMessage) {
            val id = s.message.toolCallId ?: return
            val entry = calls.entries.lastOrNull { it.key.endsWith(":$id") && it.value.state == StepState.Unknown } ?: return
            val text = s.message.text
            val updated = entry.value.copy(
                state = if (s.isError) StepState.Failed else StepState.Completed,
                durationMs = s.durationMs,
                result = preview(text),
                risk = s.risk,
                lastLine = if (isTerminal(entry.value.name)) text.lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.take(160) else null,
                // A failed image job keeps its card ("Generation failed" with the reason).
                card = if (s.isError && IMAGE_MARKER !in text) null else cardFor(text),
            )
            calls[entry.key] = updated
            replaceStep(updated)
        }

        private fun demoteAnswer() {
            val a = answer ?: run {
                answerReasoning?.let { work += it }
                answerReasoning = null
                return
            }
            answerReasoning?.let { work += it }
            work += WorkEntry.Note("a${answerIndex}", a)
            answer = null
            answerIndex = null
            answerReasoning = null
            answerSuggestions = null
        }

        private fun addStep(step: StepCall) {
            val last = work.lastOrNull()
            if (last is WorkEntry.Steps && last.tool == step.name) {
                work[work.lastIndex] = last.copy(calls = last.calls + step)
            } else {
                work += WorkEntry.Steps("g" + step.key, step.name, listOf(step))
            }
        }

        private fun replaceStep(step: StepCall) {
            for (w in work.indices) {
                val entry = work[w]
                if (entry is WorkEntry.Steps && entry.calls.any { it.key == step.key }) {
                    work[w] = entry.copy(calls = entry.calls.map { if (it.key == step.key) step else it })
                    return
                }
            }
        }

        fun build(live: Boolean, stream: StreamState?, now: Long): ChatItem.Turn {
            if (live) {
                for (w in work.indices) {
                    val entry = work[w]
                    if (entry !is WorkEntry.Steps) continue
                    work[w] = entry.copy(
                        calls = entry.calls.map { c ->
                            if (c.state != StepState.Unknown) return@map c
                            val running = stream?.runningCallId == c.callId
                            val since = if (running) stream.runningSince else null
                            val conversation = if (running) stream.conversationId else null
                            c.copy(
                                state = StepState.Running,
                                startedAt = since,
                                awaitingApproval = running && stream.awaitingApproval,
                                // Image calls get their card while they run ("Generating N images", live timer).
                                card = c.card ?: atom.web.image.ImageJobs.liveCardText(c.name, c.argsFull, since, conversation)?.let { StepCard.Image(it) },
                            )
                        },
                    )
                }
            }
            var streaming = false
            if (stream != null && !stream.isEmpty) {
                demoteAnswer()
                if (stream.reasoning.isNotBlank()) {
                    val ms = stream.reasoningStartedAt?.let { (stream.reasoningEndedAt ?: now) - it }
                    answerReasoning = WorkEntry.Thought("rlive", stream.reasoning.trim(), ms, live = stream.reasoningEndedAt == null)
                }
                if (stream.text.isNotEmpty()) {
                    answer = stream.text
                    answerIndex = null
                    streaming = true
                }
            }
            return ChatItem.Turn(
                key = key,
                userIndex = userIndex,
                work = work.toList(),
                answer = answer,
                answerIndex = answerIndex,
                answerReasoning = answerReasoning,
                live = live,
                streaming = streaming,
                finished = !live,
                answerSuggestions = answerSuggestions.takeIf { !streaming },
            )
        }
    }

    /** Image jobs carry an `image_job:` marker; browser steps end with a `Preview: <path>` line. */
    fun cardFor(text: String): StepCard? {
        if (IMAGE_MARKER in text) return StepCard.Image(text.take(8_000))
        val preview = text.lineSequence().lastOrNull { it.startsWith(BROWSER_PREVIEW) } ?: return null
        return StepCard.Browser(preview)
    }

    private val IMAGE_MARKER = atom.web.image.ImageJobs.MARKER
    private val BROWSER_PREVIEW = atom.web.browser.BrowserCards.PREVIEW

    fun isTerminal(tool: String): Boolean {
        val n = tool.lowercase()
        return "shell" in n || "terminal" in n || "linux" in n || "command" in n || n == "run" || "exec" in n
    }

    fun preview(text: String): String = if (text.length <= 4_000) text else text.take(4_000) + "…"

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

    /** The exact command of a terminal-like call, for "Copy command". */
    fun command(raw: String): String? {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        for (key in listOf("command", "cmd", "script", "code")) {
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
        }
        return null
    }
}
