package atom.core.agent

import atom.core.llm.ChatMessage
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.ToolCall
import atom.core.tool.Risk
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
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
    val maxRetries: Int = 2,
    val context: ContextPolicy = ContextPolicy(),
)

enum class StopReason { Completed, StepLimit, OutputLimit }

sealed interface AgentEvent {
    data class Text(val delta: String) : AgentEvent
    data class Reasoning(val delta: String) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult, val durationMs: Long) : AgentEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AgentEvent

    /** [newMessages] is everything this run appended after the given history; persist it to continue later. */
    data class Done(
        val finalText: String,
        val reason: StopReason,
        val steps: Int,
        val newMessages: List<ChatMessage>,
    ) : AgentEvent

    data class Failed(val message: String, val newMessages: List<ChatMessage>) : AgentEvent
}

class Agent(
    private val engine: LlmEngine,
    private val tools: ToolRegistry,
    private val config: AgentConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Runs until the model answers without tool calls, the step budget runs out, or the flow is cancelled. */
    fun run(history: List<ChatMessage>, ctx: ToolContext): Flow<AgentEvent> = flow {
        val convo = history.toMutableList()
        val firstNew = convo.size
        val guard = LoopGuard()
        var inTokens = 0
        var outTokens = 0

        for (step in 1..config.maxSteps) {
            val request = LlmRequest(
                model = config.model,
                messages = ContextPacker.pack(config.systemPrompt, convo, config.context),
                tools = tools.specs,
                maxOutputTokens = config.maxOutputTokens,
                reasoning = config.reasoning,
            )
            val turn = try {
                streamWithRetry(request)
            } catch (e: LlmException) {
                emit(AgentEvent.Failed(e.message ?: "Model request failed", convo.subList(firstNew, convo.size).toList()))
                return@flow
            }
            inTokens += turn.inputTokens
            outTokens += turn.outputTokens
            emit(AgentEvent.Usage(inTokens, outTokens))

            convo += ChatMessage.assistant(turn.text, turn.calls)

            if (turn.calls.isEmpty()) {
                val reason = if (turn.finish == FinishReason.Length) StopReason.OutputLimit else StopReason.Completed
                emit(AgentEvent.Done(turn.text, reason, step, convo.subList(firstNew, convo.size).toList()))
                return@flow
            }

            for (call in turn.calls) {
                emit(AgentEvent.ToolStarted(call))
                val started = System.nanoTime()
                val result = execute(call, ctx)
                emit(AgentEvent.ToolFinished(call, result, (System.nanoTime() - started) / 1_000_000))
                convo += ChatMessage.toolResult(call, result.toParts())
            }

            guard.nudgeFor(turn.calls)?.let { convo += ChatMessage.user(it) }
        }

        emit(
            AgentEvent.Done(
                finalText = "Stopped after ${config.maxSteps} steps without finishing.",
                reason = StopReason.StepLimit,
                steps = config.maxSteps,
                newMessages = convo.subList(firstNew, convo.size).toList(),
            ),
        )
    }

    private class Turn(
        val text: String,
        val calls: List<ToolCall>,
        val finish: FinishReason,
        val inputTokens: Int,
        val outputTokens: Int,
    )

    private suspend fun FlowCollector<AgentEvent>.streamWithRetry(request: LlmRequest): Turn {
        var attempt = 0
        while (true) {
            var emittedAny = false
            try {
                val text = StringBuilder()
                val calls = mutableListOf<ToolCall>()
                var finish = FinishReason.Stop
                var input = 0
                var output = 0
                engine.stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            emittedAny = true
                            text.append(event.text)
                            emit(AgentEvent.Text(event.text))
                        }
                        is LlmEvent.ReasoningDelta -> {
                            emittedAny = true
                            emit(AgentEvent.Reasoning(event.text))
                        }
                        is LlmEvent.ToolCallReady -> calls += event.call
                        is LlmEvent.Usage -> {
                            input = event.inputTokens
                            output = event.outputTokens
                        }
                        is LlmEvent.Finished -> finish = event.reason
                    }
                }
                return Turn(text.toString(), calls, finish, input, output)
            } catch (e: LlmException) {
                // A retry after partial output would duplicate text on screen, so only retry clean failures.
                if (!e.retryable || emittedAny || attempt >= config.maxRetries) throw e
                delay(backoffMs(attempt))
                attempt++
            }
        }
    }

    private suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult.error("Unknown tool '${call.name}'. Available: ${tools.specs.joinToString { it.name }}")

        val args: JsonObject = try {
            if (call.argumentsJson.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(call.argumentsJson).jsonObject
        } catch (e: Exception) {
            return ToolResult.error("Arguments for '${call.name}' were not a valid JSON object: ${e.message}")
        }

        if (tool.risk == Risk.Sensitive && !ctx.confirm(describe(call))) {
            return ToolResult.error("The user declined this action. Do not retry it; ask or choose another approach.")
        }

        return try {
            withTimeout(config.toolTimeoutMs) { tool.run(args, ctx) }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("'${call.name}' timed out after ${config.toolTimeoutMs / 1000}s.")
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

    private fun backoffMs(attempt: Int): Long = 800L shl attempt
}

/**
 * Stops the most common phone-agent failure: re-issuing the same action because the model assumes it
 * did not land. Every repeat is a wasted model call, which is wasted battery.
 */
internal class LoopGuard(private val threshold: Int = 3) {
    private var lastSignature: String? = null
    private var repeats = 0

    fun nudgeFor(calls: List<ToolCall>): String? {
        val signature = calls.joinToString("|") { it.name + ":" + it.argumentsJson.filterNot(Char::isWhitespace) }
        repeats = if (signature == lastSignature) repeats + 1 else 1
        lastSignature = signature
        if (repeats < threshold) return null
        repeats = 0
        return "You have issued the identical action $threshold times in a row. Stop repeating it. " +
            "Re-read the current state, then either take a different action or explain what is blocking you."
    }
}
