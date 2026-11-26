package atom.core.agent

import atom.core.llm.SearchSuggestions
import atom.core.llm.ChatMessage
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmErrors
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmFailure
import atom.core.llm.LlmRequest
import atom.core.llm.PromptedToolEngine
import atom.core.llm.ReasoningEffort
import atom.core.llm.ToolCall
import atom.core.tool.Risk
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolExposure
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class AgentConfig(
    val model: String,
    val systemPrompt: String,
    val maxSteps: Int = 30,
    val maxOutputTokens: Int = 2048,
    val reasoning: ReasoningEffort? = null,
    val toolTimeoutMs: Long = 60_000,
    /** Retries of a transient model failure; waits double from [retryDelayMs] (2 s, 4 s, 8 s by default). */
    val maxRetries: Int = 3,
    val context: ContextPolicy = ContextPolicy(),
    val retryDelayMs: Long = 2_000,
    /** The model's context window in tokens. Null means unknown: usage is still reported, auto-compaction is off. */
    val contextWindow: Int? = null,
    val autoCompact: Boolean = true,
    /** Fraction of the usable window at which the conversation is compacted automatically. */
    val compactAt: Double = ContextMeter.DEFAULT_COMPACT_AT,
    /**
     * When the run stops before the model finished (the step budget ran out, or the loop guard found it stuck), this
     * is sent as an internal user turn, after a sentence saying why, in one more model call with no tools, so the
     * model reports what it did, what it found and what is left instead of the run ending without an answer. Null
     * ends the run with a fixed notice.
     */
    val wrapUpPrompt: String? = Agent.WRAP_UP,
    /** Whether the loop guard may end a run that keeps repeating itself without new information. */
    val stopWhenStuck: Boolean = true,
) {
    /**
     * What one prompt may actually use: the model window minus room for the answer, or the policy's deliberate
     * cap when one is set and smaller. Null when the window is unknown.
     */
    val usableWindow: Int?
        get() = contextWindow?.takeIf { it > 0 }?.let { w ->
            context.budgetTokens?.let { minOf(w, it) } ?: ContextPolicy.promptBudget(w, maxOutputTokens)
        }

    /** The packing policy with its budget resolved: the deliberate cap, else the usable window, else a generous default. */
    val packing: ContextPolicy
        get() = if (context.budgetTokens != null) context else context.copy(budgetTokens = usableWindow ?: ContextPolicy.UNKNOWN_WINDOW_BUDGET)

    fun retryDelay(attempt: Int): Long = (retryDelayMs shl attempt.coerceIn(0, 10)).coerceAtMost(retryDelayMs * 8)
}

enum class StopReason {
    Completed,
    StepLimit,
    OutputLimit,

    /** The loop guard ended a run whose recent steps repeated earlier ones without bringing new information. */
    Stuck,
}

/** Metadata the app stores next to a message the agent appended. */
data class MessageInfo(
    val reasoning: String? = null,
    val reasoningMs: Long? = null,
    /** Provider search suggestions for this assistant message (Gemini grounding); null when none. */
    val searchSuggestions: SearchSuggestions? = null,
    val durationMs: Long? = null,
    val isError: Boolean = false,
    /** Only for the current turn; persist [Scrubber.scrub] of it instead. */
    val sensitive: Boolean = false,
    /** A loop-guard nudge: sent to the model, never shown as the user's words. */
    val internal: Boolean = false,
    /** A queued user message delivered to the running task. */
    val steering: Boolean = false,
)

sealed interface AgentEvent {
    data class Text(val delta: String) : AgentEvent
    data class Reasoning(val delta: String) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult, val durationMs: Long) : AgentEvent

    /** Cumulative for this run. */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AgentEvent

    /** Every message the agent adds to the conversation, in order, as soon as it is final. */
    data class Appended(val message: ChatMessage, val info: MessageInfo = MessageInfo()) : AgentEvent

    data class ModelCall(val stats: ModelCallStats) : AgentEvent

    /** How much of the window the conversation uses, measured after each response. */
    data class ContextUsage(val usedTokens: Int, val windowTokens: Int?) : AgentEvent

    /**
     * A transient model failure; the request is repeated after [delayMs]. When [discardPartial] is true the text
     * and reasoning streamed for this step so far are void and will be streamed again.
     */
    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val reason: String,
        val discardPartial: Boolean,
    ) : AgentEvent

    data object Compacting : AgentEvent

    /**
     * A provider-hosted tool (its web search, code execution...) started ([running]) or finished. [callId] tells
     * calls that overlap apart (null when the provider gives none).
     */
    data class HostedTool(val kind: atom.core.llm.HostedToolKind, val running: Boolean, val callId: String? = null) : AgentEvent

    /**
     * The first [summarizedCount] messages of the conversation the agent works on (history given to [Agent.run]
     * plus what it appended) are now represented by [summary].
     */
    data class Compacted(
        val summary: ChatMessage,
        val summarizedCount: Int,
        val tokensBefore: Int,
        val tokensAfter: Int,
        val cost: ModelCallStats,
    ) : AgentEvent

    data class CompactionFailed(val reason: String) : AgentEvent

    /** [newMessages] is everything this run appended after the given history; persist it to continue later. */
    data class Done(
        val finalText: String,
        val reason: StopReason,
        val steps: Int,
        val newMessages: List<ChatMessage>,
    ) : AgentEvent

    data class Failed(
        val message: String,
        val newMessages: List<ChatMessage>,
        val failure: LlmFailure = LlmFailure.Unknown,
        val detail: String? = null,
    ) : AgentEvent
}

/** Hooks the runtime uses to steer, pause and serialise phone access. All are called at safe points only. */
interface RunControl {
    /** Suspends while the run is paused. Called before every model request and every tool call. */
    suspend fun checkpoint() {}

    /** User messages queued to steer the run; drained between steps. */
    fun drainSteering(): List<ChatMessage> = emptyList()

    /** Called before a tool that drives the phone UI; suspends until this run holds phone control. */
    suspend fun acquirePhone() {}

    companion object {
        val None: RunControl = object : RunControl {}
    }
}

class Agent(
    private val engine: LlmEngine,
    private val tools: ToolRegistry,
    private val config: AgentConfig,
    /** Which tools each request offers; by default all of [tools]. A [atom.core.tool.ToolLoadout] offers a core set. */
    private val exposure: ToolExposure = ToolExposure.All(tools),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Runs until the model answers without tool calls, the step budget runs out, or the flow is cancelled. At the step
     * limit the model gets one last call without tools to write its reply ([AgentConfig.wrapUpPrompt]).
     */
    fun run(history: List<ChatMessage>, ctx: ToolContext, control: RunControl = RunControl.None): Flow<AgentEvent> = flow {
        val state = RunState(history)
        val guard = LoopGuard()
        var stuckAt: Int? = null

        for (step in 1..config.maxSteps) {
            control.checkpoint()
            deliverSteering(state, control)
            val specs = exposure.specs()
            state.toolTokens = ContextPacker.estimateTools(specs)
            maybeCompact(state)

            val request = LlmRequest(
                model = config.model,
                messages = ContextPacker.pack(config.systemPrompt, state.convo, config.packing),
                tools = specs,
                maxOutputTokens = config.maxOutputTokens,
                reasoning = config.reasoning,
            )
            val turn = try {
                streamWithRetry(request)
            } catch (e: LlmException) {
                val failure = LlmErrors.classify(e)
                emit(AgentEvent.Failed(LlmErrors.explain(e), state.appended.toList(), failure, e.message))
                return@flow
            }
            state.inTokens += turn.stats.inputTokens
            state.outTokens += turn.stats.outputTokens
            emit(AgentEvent.ModelCall(turn.stats))
            emit(AgentEvent.Usage(state.inTokens, state.outTokens))

            val answer = ChatMessage.assistant(turn.text, turn.calls)
            append(state, answer, MessageInfo(turn.reasoning, turn.reasoningMs, searchSuggestions = turn.suggestions))
            // The provider's prompt count already holds the system prompt and every tool schema. Reasoning is not
            // sent back, so the answer counts by what it adds to the next prompt rather than by output tokens.
            state.lastReported = if (turn.reportedInput > 0) turn.reportedInput + ContextPacker.estimate(answer) else 0
            state.sinceReport = 0
            emit(AgentEvent.ContextUsage(state.used(), config.usableWindow))

            if (turn.calls.isEmpty()) {
                if (deliverSteering(state, control)) continue
                val reason = if (turn.finish == FinishReason.Length) StopReason.OutputLimit else StopReason.Completed
                emit(AgentEvent.Done(turn.text, reason, step, state.appended.toList()))
                return@flow
            }

            val results = ArrayList<ToolResult>(turn.calls.size)
            for (call in turn.calls) {
                control.checkpoint()
                emit(AgentEvent.ToolStarted(call))
                val started = System.nanoTime()
                val result = execute(call, ctx, control)
                val duration = (System.nanoTime() - started) / 1_000_000
                emit(AgentEvent.ToolFinished(call, result, duration))
                append(
                    state,
                    ChatMessage.toolResult(call, result.toParts()),
                    MessageInfo(durationMs = duration, isError = result.isError, sensitive = result.sensitive),
                )
                results += result
            }

            val verdict = guard.after(turn.calls, results)
            if (verdict.stuck && config.stopWhenStuck) {
                stuckAt = step
                break
            }
            // On the last step the wrap-up request below takes the place of a nudge.
            if (verdict.nudge != null && step < config.maxSteps) append(state, ChatMessage.user(verdict.nudge), MessageInfo(internal = true))
        }

        val stuck = stuckAt != null
        val steps = stuckAt ?: config.maxSteps
        val wrapped = config.wrapUpPrompt?.let { wrapUp(state, control, (if (stuck) STUCK_REASON else STEP_LIMIT_REASON) + " " + it) }
        emit(
            AgentEvent.Done(
                finalText = wrapped ?: if (stuck) {
                    "Stopped after $steps steps: the last steps repeated earlier ones without new information."
                } else {
                    "Stopped after ${config.maxSteps} steps without finishing."
                },
                reason = if (stuck) StopReason.Stuck else StopReason.StepLimit,
                steps = steps,
                newMessages = state.appended.toList(),
            ),
        )
    }

    /**
     * The run is ending before the model finished (step budget spent, or stuck): one last request without tools, so
     * the model tells the user where things stand.
     * Returns its text, or null when the call failed or came back empty (the caller keeps its fixed notice). Any
     * tool call the model makes anyway is dropped: nothing runs after the limit.
     */
    private suspend fun FlowCollector<AgentEvent>.wrapUp(state: RunState, control: RunControl, prompt: String): String? {
        control.checkpoint()
        append(state, ChatMessage.user(prompt), MessageInfo(internal = true))
        state.toolTokens = 0
        maybeCompact(state)
        val messages = ContextPacker.pack(config.systemPrompt, state.convo, config.packing)
        val request = LlmRequest(
            model = config.model,
            messages = messages,
            tools = emptyList(),
            maxOutputTokens = config.maxOutputTokens,
            reasoning = config.reasoning,
        )
        val turn = try {
            streamWithRetry(request)
        } catch (e: LlmException) {
            // Some providers refuse tool calls in the history of a request that declares no tools; the same
            // conversation as plain text carries everything the model needs for its reply.
            if (e.retryable) return null
            try {
                streamWithRetry(request.copy(messages = PromptedToolEngine.asPlainText(messages)))
            } catch (e: LlmException) {
                return null
            }
        }
        state.inTokens += turn.stats.inputTokens
        state.outTokens += turn.stats.outputTokens
        emit(AgentEvent.ModelCall(turn.stats))
        emit(AgentEvent.Usage(state.inTokens, state.outTokens))
        if (turn.text.isBlank()) return null
        val answer = ChatMessage.assistant(turn.text)
        append(state, answer, MessageInfo(turn.reasoning, turn.reasoningMs, searchSuggestions = turn.suggestions))
        state.lastReported = if (turn.reportedInput > 0) turn.reportedInput + ContextPacker.estimate(answer) else 0
        state.sinceReport = 0
        emit(AgentEvent.ContextUsage(state.used(), config.usableWindow))
        return turn.text
    }

    private inner class RunState(history: List<ChatMessage>) {
        val convo = history.toMutableList()
        val sensitive = MutableList(history.size) { false }
        val appended = ArrayList<ChatMessage>()
        var inTokens = 0
        var outTokens = 0

        /** Provider-reported size of the last exchange (prompt + answer), 0 when not reported. */
        var lastReported = 0

        /** Estimated tokens added since that report. */
        var sinceReport = 0

        /** Estimated size of the tool declarations the last request offered. */
        var toolTokens = 0

        /** Conversation size right after the last automatic compaction, and the tokens it left; -1 before any. */
        var compactedSize = -1
        var compactedTokens = 0

        /** Estimated prompt size from the conversation itself: system prompt, tools, shrunk messages. */
        fun estimated(): Int = ContextPacker.measure(config.systemPrompt, convo, config.packing) + toolTokens

        fun used(): Int = if (lastReported > 0) lastReported + sinceReport else estimated()
    }

    private suspend fun FlowCollector<AgentEvent>.append(state: RunState, message: ChatMessage, info: MessageInfo) {
        state.convo += message
        state.sensitive += info.sensitive
        state.appended += message
        state.sinceReport += ContextPacker.estimate(message)
        emit(AgentEvent.Appended(message, info))
    }

    private suspend fun FlowCollector<AgentEvent>.deliverSteering(state: RunState, control: RunControl): Boolean {
        val messages = control.drainSteering()
        for (m in messages) append(state, m, MessageInfo(steering = true))
        return messages.isNotEmpty()
    }

    private suspend fun FlowCollector<AgentEvent>.maybeCompact(state: RunState) {
        val window = config.usableWindow ?: return
        if (!config.autoCompact) return
        val used = maxOf(state.estimated(), if (state.lastReported > 0) state.used() else 0)
        if (used < window * config.compactAt) return
        // Never twice in a row: after a compaction, wait for new messages and real growth, so a prompt whose fixed
        // part (system prompt, tools) alone sits near the threshold does not compact on every step.
        if (state.compactedSize >= 0) {
            if (state.convo.size <= state.compactedSize) return
            if (used - state.compactedTokens < window * MIN_GROWTH_SHARE) return
        }
        val cut = Compactor.cutIndex(state.convo, keepRecentTokens = (window * KEEP_RECENT_SHARE).toInt())
        if (cut <= 0) return
        emit(AgentEvent.Compacting)
        val request = Compactor.request(
            model = config.model,
            prefix = state.convo.subList(0, cut).toList(),
            sensitive = { state.sensitive[it] },
            maxOutputTokens = (window / 8).coerceIn(400, 2_000),
            transcriptBudgetChars = (window * 3).coerceAtLeast(8_000),
            reasoning = if (config.reasoning == ReasoningEffort.Off) ReasoningEffort.Off else null,
        )
        val result = try {
            Compactor.summarize(engine, request)
        } catch (e: LlmException) {
            emit(AgentEvent.CompactionFailed(LlmErrors.explain(e)))
            return
        }
        val summary = Compactor.summaryMessage(result.summary)
        val kept = state.convo.subList(cut, state.convo.size).toList()
        val keptFlags = state.sensitive.subList(cut, state.sensitive.size).toList()
        state.convo.clear()
        state.convo += summary
        state.convo += kept
        state.sensitive.clear()
        state.sensitive += false
        state.sensitive += keptFlags
        val after = state.estimated()
        state.lastReported = 0
        state.sinceReport = 0
        state.compactedSize = state.convo.size
        state.compactedTokens = after
        emit(AgentEvent.Compacted(summary, cut, used, after, result.stats))
        emit(AgentEvent.ContextUsage(after, window))
    }

    private class Turn(
        val text: String,
        val calls: List<ToolCall>,
        val finish: FinishReason,
        val reasoning: String?,
        val reasoningMs: Long?,
        val reportedInput: Int,
        val reportedOutput: Int,
        val stats: ModelCallStats,
        val suggestions: SearchSuggestions? = null,
    )

    private suspend fun FlowCollector<AgentEvent>.streamWithRetry(request: LlmRequest): Turn {
        var attempt = 0
        while (true) {
            var emittedAny = false
            try {
                val started = System.nanoTime()
                var firstToken: Long? = null
                var reasoningStart: Long? = null
                var reasoningEnd: Long? = null
                val text = StringBuilder()
                val reasoning = StringBuilder()
                val calls = mutableListOf<ToolCall>()
                var finish = FinishReason.Stop
                var usage: LlmEvent.Usage? = null
                var suggestions: SearchSuggestions? = null
                engine.stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            emittedAny = true
                            val now = System.nanoTime()
                            if (firstToken == null) firstToken = now
                            if (reasoningStart != null && reasoningEnd == null) reasoningEnd = now
                            text.append(event.text)
                            emit(AgentEvent.Text(event.text))
                        }
                        is LlmEvent.ReasoningDelta -> {
                            emittedAny = true
                            val now = System.nanoTime()
                            if (firstToken == null) firstToken = now
                            if (reasoningStart == null) reasoningStart = now
                            reasoning.append(event.text)
                            emit(AgentEvent.Reasoning(event.text))
                        }
                        is LlmEvent.ToolCallReady -> {
                            val now = System.nanoTime()
                            if (firstToken == null) firstToken = now
                            if (reasoningStart != null && reasoningEnd == null) reasoningEnd = now
                            calls += event.call
                        }
                        is LlmEvent.Usage -> usage = event
                        is LlmEvent.Finished -> finish = event.reason
                        is LlmEvent.Suggestions -> if (!event.suggestions.isEmpty) suggestions = event.suggestions
                        is LlmEvent.HostedTool -> emit(AgentEvent.HostedTool(event.kind, event.running, event.callId))
                    }
                }
                val end = System.nanoTime()
                val reported = usage
                val estimatedIn = request.messages.sumOf { ContextPacker.estimate(it) } + ContextPacker.estimateTools(request.tools)
                val estimatedOut = ContextPacker.estimate(text.toString()) + ContextPacker.estimate(reasoning.toString())
                return Turn(
                    text = text.toString(),
                    calls = calls,
                    finish = finish,
                    reasoning = reasoning.toString().takeIf { it.isNotBlank() },
                    reasoningMs = reasoningStart?.let { ((reasoningEnd ?: end) - it) / 1_000_000 },
                    reportedInput = reported?.inputTokens ?: 0,
                    reportedOutput = reported?.outputTokens ?: 0,
                    stats = ModelCallStats(
                        ttftMs = firstToken?.let { (it - started) / 1_000_000 },
                        durationMs = (end - started) / 1_000_000,
                        inputTokens = reported?.inputTokens?.takeIf { it > 0 } ?: estimatedIn,
                        outputTokens = reported?.outputTokens?.takeIf { it > 0 } ?: estimatedOut,
                        cacheReadTokens = reported?.cacheReadTokens ?: 0,
                        cacheWriteTokens = reported?.cacheWriteTokens ?: 0,
                    ),
                    suggestions = suggestions,
                )
            } catch (e: LlmException) {
                if (!e.retryable || attempt >= config.maxRetries) throw e
                val wait = config.retryDelay(attempt)
                emit(AgentEvent.Retrying(attempt + 1, config.maxRetries, wait, LlmErrors.explain(e), discardPartial = emittedAny))
                delay(wait)
                attempt++
            }
        }
    }

    private suspend fun execute(call: ToolCall, ctx: ToolContext, control: RunControl): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult.error("Unknown tool '${call.name}'. Available: ${exposure.specs().joinToString { it.name }}")
        exposure.onCall(call.name)

        val args: JsonObject = try {
            if (call.argumentsJson.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(call.argumentsJson).jsonObject
        } catch (e: Exception) {
            return ToolResult.error("Arguments for '${call.name}' were not a valid JSON object: ${e.message}")
        }

        if (tool.usesPhoneUi) control.acquirePhone()

        val risk = runCatching { tool.riskFor(args) }.getOrDefault(Risk.Sensitive)
        if (risk == Risk.Sensitive && !ctx.confirm(tool.approvalText(args) ?: describe(call))) {
            return ToolResult.error("The user declined this action. Do not retry it; ask or choose another approach.")
        }

        val limit = tool.timeoutMs?.takeIf { it > 0 } ?: config.toolTimeoutMs
        return try {
            withTimeout(limit) { tool.run(args, ctx) }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("'${call.name}' timed out after ${limit / 1000}s. Its outcome is unknown; check the state before retrying.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolArgumentException) {
            ToolResult.error(e.message ?: "Invalid arguments")
        } catch (e: Exception) {
            ToolResult.error("'${call.name}' failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun describe(call: ToolCall): String {
        val args = call.argumentsJson.take(400)
        return "${call.name}($args)"
    }

    companion object {
        /** What the final internal user turn asks for when a run ends before the model finished. */
        const val WRAP_UP =
            "Reply to the user now: what you did, what you found, what's left, and how they can continue " +
                "(for example by asking you to carry on)."

        /** Says why the run ends; put before [AgentConfig.wrapUpPrompt]. */
        const val STEP_LIMIT_REASON = "You've reached this run's step limit, so no more tools will run."
        const val STUCK_REASON =
            "This run is stopping because its last steps repeated earlier actions without bringing new information, " +
                "so no more tools will run."

        private const val KEEP_RECENT_SHARE = 0.3

        /** Growth (share of the window) needed after one automatic compaction before the next. */
        private const val MIN_GROWTH_SHARE = 0.1
    }
}

/**
 * Stops the most common phone-agent failure: re-issuing the same action because the model assumes it did not land,
 * or circling through the same few actions (scroll down, scroll up, read the screen...) without learning anything.
 * Every repeat is a wasted model call, which is wasted battery.
 *
 * Two signals, per step:
 * - the identical set of calls [threshold] times in a row gets a nudge;
 * - a step is stale when every call in it was made before (within the last [window] steps) and returned the same
 *   result as then: it brought no new information. [staleNudgeAt] stale steps in a row get a nudge; [staleStopAt]
 *   in a row mean the run is stuck and should end (with the model's wrap-up reply).
 * A step with any new call or any changed result resets the stale streak, so long runs that keep making progress are
 * never stopped by it.
 */
internal class LoopGuard(
    private val threshold: Int = 3,
    private val staleNudgeAt: Int = 4,
    private val staleStopAt: Int = 8,
    private val window: Int = 24,
) {
    data class Verdict(val nudge: String? = null, val stuck: Boolean = false)

    private var lastSignature: String? = null
    private var repeats = 0
    private var staleStreak = 0
    private val seen = ArrayDeque<Set<String>>()

    fun after(calls: List<ToolCall>, results: List<ToolResult>): Verdict {
        val keys = calls.mapIndexed { i, c -> callKey(c) + "=>" + digest(results.getOrNull(i)) }.toSet()
        val stale = keys.isNotEmpty() && keys.all { k -> seen.any { k in it } }
        seen.addLast(keys)
        while (seen.size > window) seen.removeFirst()
        staleStreak = if (stale) staleStreak + 1 else 0
        if (staleStreak >= staleStopAt) return Verdict(stuck = true)
        nudgeFor(calls)?.let { return Verdict(nudge = it) }
        if (staleStreak == staleNudgeAt) {
            return Verdict(
                nudge = "Your last $staleNudgeAt steps repeated earlier actions and returned nothing new. Stop circling. " +
                    "Try a clearly different approach, or, if you are blocked or already have the answer, stop and reply to the user.",
            )
        }
        return Verdict()
    }

    fun nudgeFor(calls: List<ToolCall>): String? {
        val signature = calls.joinToString("|") { callKey(it) }
        repeats = if (signature == lastSignature) repeats + 1 else 1
        lastSignature = signature
        if (repeats < threshold) return null
        repeats = 0
        return "You have issued the identical action $threshold times in a row. Stop repeating it. " +
            "Re-read the current state, then either take a different action or explain what is blocking you."
    }

    private fun callKey(c: ToolCall) = c.name + ":" + c.argumentsJson.filterNot(Char::isWhitespace)

    /** What a result said, ignoring per-call noise (the browser card's preview file name). */
    private fun digest(r: ToolResult?): String {
        if (r == null) return "-"
        val text = r.text.lineSequence().filterNot { it.startsWith("Preview: ") }.joinToString("\n")
        val images = r.images.joinToString(",") { it.hashCode().toString() }
        return "${r.isError}:${text.hashCode()}:${text.length}:$images"
    }
}
