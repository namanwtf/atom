package atom.app.report

import atom.app.data.EntryKind
import atom.app.data.StoredMessage
import atom.core.agent.Scrubber
import atom.core.llm.Role
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class AppFacts(val versionName: String, val versionCode: Long, val packageName: String, val debugBuild: Boolean)

data class DeviceFacts(
    val androidRelease: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val rom: String?,
    val build: String?,
)

/** One tool call of the last run: its name, the shape of its arguments (never their values) and how it ended. */
data class StepFacts(val tool: String, val args: String, val state: String, val durationMs: Long?, val error: String?)

data class RunFacts(
    val outcome: String,
    val error: String? = null,
    val source: String? = null,
    val model: String? = null,
    val startedAt: Long? = null,
    val durationMs: Long? = null,
    val steps: List<StepFacts> = emptyList(),
)

/** A recent end of Atom's own process the system recorded (crash, ANR...). */
data class ExitFacts(val at: Long, val reason: String, val description: String?, val trace: List<String> = emptyList())

data class ReportInput(
    val createdAt: Long,
    val zone: ZoneId,
    val app: AppFacts,
    val device: DeviceFacts,
    val note: String = "",
    val run: RunFacts? = null,
    val exits: List<ExitFacts> = emptyList(),
    /** Raw lines of Atom's log tags, oldest first; null when the log could not be read. */
    val log: List<String>? = emptyList(),
    /** Why the log is missing or empty, when that is known. */
    val logNote: String? = null,
    /** The conversation as exported, when the person chose to include it. */
    val conversation: String? = null,
    val screenshot: Boolean = false,
)

/**
 * The text of a problem report (pure, unit-tested). Everything Atom collected on its own passes
 * [Redactor.personal]; what the person typed or chose to include passes [Redactor.secrets]; and the whole text passes
 * [Redactor.secrets] once more, so no key, token or saved secret ([known]) can reach the file.
 */
object ProblemReport {
    const val MAX_LOG_LINES = 200
    const val MAX_STEPS = 40
    private const val ERROR_CHARS = 300
    private val json = Json { isLenient = true }

    fun text(input: ReportInput, known: Collection<String> = emptyList()): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US).withZone(input.zone)
        fun at(ms: Long) = time.format(Instant.ofEpochMilli(ms))
        fun mine(s: String) = Redactor.personal(s, known)
        val head = buildString {
            appendLine("Atom problem report")
            appendLine("Created ${at(input.createdAt)} (${input.zone.id})")
            appendLine("Made on the phone and shared by hand: Atom sends nothing on its own. Keys, tokens and personal data are left out.")

            section("What happened")
            appendLine(input.note.trim().ifEmpty { "(not described)" }.let { Redactor.secrets(it, known) })
        }
        // Versions and build names come from the system, not from the person or a model: only saved secrets are
        // matched there, so a build id is never mistaken for a token.
        val facts = buildString {
            section("App")
            appendLine("Atom ${input.app.versionName} (${input.app.versionCode})${if (input.app.debugBuild) ", debug build" else ""}")
            appendLine("Package: ${input.app.packageName}")

            section("Device")
            appendLine("Android ${input.device.androidRelease} (API ${input.device.sdk})")
            appendLine("Model: ${input.device.manufacturer} ${input.device.model}".trimEnd())
            appendLine("ROM: ${input.device.rom ?: "not detected"}")
            input.device.build?.takeIf { it.isNotBlank() }?.let { appendLine("Build: $it") }
        }
        val body = buildString {
            section("Last run")
            val run = input.run
            if (run == null) {
                appendLine("No run recorded.")
            } else {
                appendLine("Outcome: ${run.outcome}")
                run.error?.takeIf { it.isNotBlank() }?.let { appendLine("Error: ${mine(it.take(ERROR_CHARS * 2))}") }
                listOfNotNull(
                    run.source?.let { "Source: $it" },
                    run.model?.let { "Model: ${mine(it)}" },
                    run.startedAt?.let { "Started ${at(it)}" },
                    run.durationMs?.let { "took ${seconds(it)}" },
                ).takeIf { it.isNotEmpty() }?.let { appendLine(it.joinToString(" · ")) }
                if (run.steps.isEmpty()) {
                    appendLine("No tool steps.")
                } else {
                    val failed = run.steps.count { it.state == STATE_FAILED }
                    appendLine("Tool steps: ${run.steps.size}${if (failed > 0) ", $failed failed" else ""}")
                    run.steps.takeLast(MAX_STEPS).forEachIndexed { i, s ->
                        append("  ").append(i + 1).append(". ").append(s.tool).append('(').append(s.args).append(") · ").append(s.state)
                        s.durationMs?.let { append(" · ").append(seconds(it)) }
                        appendLine()
                        s.error?.let { appendLine("     ${mine(it.take(ERROR_CHARS))}") }
                    }
                    appendLine("Argument values and tool results are left out; failed steps keep their error.")
                }
            }

            section("Recent crashes and freezes")
            if (input.exits.isEmpty()) appendLine("None recorded.")
            input.exits.forEach { e ->
                appendLine("${at(e.at)} · ${e.reason}${e.description?.takeIf { it.isNotBlank() }?.let { " · " + mine(it.take(ERROR_CHARS)) } ?: ""}")
                e.trace.forEach { appendLine("    " + mine(it)) }
            }

            val log = input.log
            if (log == null) {
                section("Atom log")
                appendLine("Could not be read on this phone.")
                input.logNote?.let { appendLine("(${mine(it)})") }
            } else {
                val lines = log.takeLast(MAX_LOG_LINES)
                section(if (lines.isEmpty()) "Atom log" else "Atom log (last ${lines.size} lines)")
                if (lines.isEmpty()) appendLine("No lines from Atom's log tags." + (input.logNote?.let { " (${mine(it)})" } ?: ""))
                lines.forEach { appendLine(logLine(it, known)) }
            }

            input.conversation?.let {
                section("Conversation (included by you)")
                appendLine(Redactor.secrets(it.trim(), known))
            }

            section("Attachments")
            appendLine(if (input.screenshot) "Screenshot attached." else "No screenshot.")
        }
        return Redactor.secrets(head, known) + Redactor.exact(facts, known) + Redactor.secrets(body, known)
    }

    private fun StringBuilder.section(title: String) {
        appendLine()
        appendLine("== $title ==")
    }

    private fun seconds(ms: Long) = if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)

    /** Logcat "threadtime" lines keep their time, ids, level and tag; only the message is cleaned. */
    private val THREADTIME = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+\\d+\\s+\\d+\\s+[VDIWEFA]\\s+[^:]*?:\\s?)(.*)$")

    internal fun logLine(line: String, known: Collection<String>): String {
        val m = THREADTIME.find(line) ?: return Redactor.personal(line, known)
        return m.groupValues[1] + Redactor.personal(m.groupValues[2], known)
    }

    const val STATE_OK = "ok"
    const val STATE_FAILED = "failed"
    const val STATE_NO_RESULT = "no result"

    /**
     * The tool steps of the turn that starts at [from] in [messages]. Results the agent marked sensitive were
     * already replaced by a placeholder when they were stored, so their text is never shown, not even an error.
     */
    fun steps(messages: List<StoredMessage>, from: Int): List<StepFacts> {
        val results = HashMap<String, StoredMessage>()
        val calls = ArrayList<atom.core.llm.ToolCall>()
        for (s in messages.drop(from.coerceIn(0, messages.size))) {
            // Hidden entries (the run's own tool preload, loop-guard nudges) are no steps the person took or saw.
            if (s.kind != EntryKind.Message || s.hidden) continue
            when (s.message.role) {
                Role.Assistant -> calls += s.message.toolCalls
                Role.Tool -> s.message.toolCallId?.let { results[it] = s }
                else -> Unit
            }
        }
        return calls.map { call ->
            val r = results[call.id]
            val scrubbed = r != null && Scrubber.isScrubbed(r.message)
            StepFacts(
                tool = toolName(call.name),
                args = argShape(call.argumentsJson),
                state = when {
                    r == null -> STATE_NO_RESULT
                    r.isError -> STATE_FAILED
                    else -> STATE_OK
                },
                durationMs = r?.durationMs,
                error = when {
                    r == null || !r.isError -> null
                    scrubbed -> "(sensitive result, left out)"
                    else -> r.message.text.trim().lineSequence().take(4).joinToString(" ").take(ERROR_CHARS)
                },
            )
        }
    }

    /** Where the last turn of [messages] begins: its last visible user message. */
    fun lastTurnStart(messages: List<StoredMessage>): Int = messages.indexOfLast { it.isVisibleUser }.coerceAtLeast(0)

    /** The outcome of a turn read back from the saved record (when the run is not in memory any more). */
    fun storedOutcome(messages: List<StoredMessage>, from: Int): RunFacts {
        val turn = messages.drop(from.coerceIn(0, messages.size))
        val notice = turn.lastOrNull { it.kind == EntryKind.Notice }
        val answered = turn.any { it.kind == EntryKind.Message && it.message.role == Role.Assistant && it.message.toolCalls.isEmpty() && it.message.text.isNotBlank() }
        val first = turn.firstOrNull()?.createdAt?.takeIf { it > 0 }
        val last = turn.lastOrNull()?.createdAt?.takeIf { it > 0 }
        return RunFacts(
            outcome = when {
                notice?.isError == true -> "Failed"
                notice != null && !answered -> "Ended: " + notice.message.text.lineSequence().first().take(80)
                answered -> "Completed"
                turn.isEmpty() -> "No run recorded"
                else -> "Unknown"
            },
            error = notice?.takeIf { it.isError }?.message?.text,
            startedAt = first,
            durationMs = if (first != null && last != null && last >= first) last - first else null,
            steps = steps(messages, from),
        )
    }

    private fun toolName(name: String) = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.take(64).ifEmpty { "tool" }

    /** `to: text, count: number, silent: true`: the argument names and kinds, never what they contain. */
    internal fun argShape(argumentsJson: String): String {
        if (argumentsJson.isBlank()) return ""
        val obj = runCatching { json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull() ?: return "unreadable arguments"
        val keys = obj.entries.take(12).joinToString(", ") { (k, v) ->
            val kind = when (v) {
                is JsonNull -> "null"
                is JsonPrimitive -> when {
                    v.isString -> "text"
                    v.booleanOrNull != null -> v.content
                    else -> "number"
                }
                is JsonArray -> "list"
                is JsonObject -> "object"
            }
            toolName(k) + ": " + kind
        }
        return if (obj.size > 12) "$keys, …" else keys
    }
}
