package atom.extend.subagent

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.ContextPolicy
import atom.core.agent.RunControl
import atom.core.llm.ChatMessage
import atom.core.llm.PromptedToolEngine
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolRegistry
import atom.extend.SubagentEngine
import atom.extend.SubagentEngineProvider
import atom.extend.ToolCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SubagentTask(val def: SubagentDef, val task: String)

data class SubagentOutcome(
    val runId: String,
    val agent: String,
    val status: SubagentStatus,
    val summary: String,
    val toolCalls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    /** A tool the subagent used returned personal data, so the summary may contain it. */
    val sensitive: Boolean,
)

/** Everything a subagent run needs from the app, resolved per call. */
class SubagentEnvironment(
    val engines: SubagentEngineProvider,
    val parentTools: (taskId: String) -> List<Tool>,
    val categoryOf: (Tool) -> ToolCategory? = { null },
    val controlFor: (taskId: String) -> RunControl = { RunControl.None },
    val settings: () -> SubagentSettings,
    val timeBudgetMs: Long = 55_000,
)

/** Runs core [Agent] in a fresh context with a scoped tool set, and only ever inside a parent's tool call. */
class SubagentRunner(private val env: SubagentEnvironment) {

    suspend fun run(task: SubagentTask, parent: ToolContext): SubagentOutcome = runAll(listOf(task), parent).single()

    /** Up to [MAX_PARALLEL] tasks on one engine: concurrently in the cloud, one after another on the device. */
    suspend fun runAll(tasks: List<SubagentTask>, parent: ToolContext): List<SubagentOutcome> {
        require(tasks.isNotEmpty() && tasks.size <= MAX_PARALLEL) { "Give between 1 and $MAX_PARALLEL tasks." }
        val settings = env.settings()
        val role = tasks.map { it.def.name }.distinct().singleOrNull() ?: "parallel"
        val engine = env.engines.acquire(role, settings.modelId, parent.taskId)
        try {
            val deadline = System.currentTimeMillis() + env.timeBudgetMs
            return if (engine.onDevice || tasks.size == 1) {
                tasks.map { execute(it, engine, settings, parent, deadline) }
            } else {
                coroutineScope { tasks.map { async { execute(it, engine, settings, parent, deadline) } }.awaitAll() }
            }
        } finally {
            withContext(NonCancellable) { runCatching { engine.release() } }
        }
    }

    private suspend fun execute(
        task: SubagentTask,
        engine: SubagentEngine,
        settings: SubagentSettings,
        parent: ToolContext,
        deadline: Long,
    ): SubagentOutcome {
        val def = task.def
        val tools = ToolScoping.select(env.parentTools(parent.taskId), def.tools, def.readOnly, env.categoryOf)
        val llm = if (engine.engine.capabilities.nativeToolCalls) engine.engine else PromptedToolEngine(engine.engine)
        val window = engine.contextWindow ?: engine.engine.capabilities.contextWindow.takeIf { it > 0 }
        val config = AgentConfig(
            model = engine.model,
            systemPrompt = systemPrompt(def, tools),
            // The time budget is the real bound; the step cap and the loop guard catch runaway loops.
            maxSteps = if (engine.onDevice) 16 else 60,
            maxOutputTokens = if (engine.onDevice) 768 else 2048,
            reasoning = settings.reasoning,
            contextWindow = window,
            // Cloud: the model's own window (compaction near 85% of it). On-device every prompt token is prefill on the
            // phone, so a deliberate cap stays.
            context = ContextPolicy(budgetTokens = if (engine.onDevice) window?.let { (it * 0.7).toInt().coerceIn(2_500, 24_000) } ?: 2_500 else null),
            // At the step limit (or when stuck) the subagent still writes its summary for the main agent, with no more tools.
            wrapUpPrompt = WRAP_UP,
        )
        val runId = SubagentRuns.start(parent.taskId, def.name, task.task)
        SubagentRuns.update(runId) { copy(model = engine.model) }
        val child = object : ToolContext {
            override val taskId: String = parent.taskId
            override suspend fun confirm(action: String) = parent.confirm("Subagent ${def.name}: $action")
            override suspend fun approve(request: atom.core.tool.ApprovalRequest) =
                parent.approve(request.copy(action = "Subagent ${def.name}: ${request.action}"))
        }
        val text = StringBuilder()
        var lastPublish = 0L
        var done: AgentEvent.Done? = null
        var failed: AgentEvent.Failed? = null
        var calls = 0
        var tokensIn = 0
        var tokensOut = 0
        var tokensCached = 0
        var sensitive = false
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(5_000)
        val finished = try {
            withTimeoutOrNull(remaining) {
                Agent(llm, ToolRegistry(tools), config).run(listOf(ChatMessage.user(task.task)), child, env.controlFor(parent.taskId)).collect { e ->
                    when (e) {
                        is AgentEvent.Text -> {
                            text.append(e.delta)
                            val now = System.currentTimeMillis()
                            if (now - lastPublish > PUBLISH_MS) {
                                lastPublish = now
                                val snapshot = text.toString()
                                SubagentRuns.update(runId) { copy(text = snapshot) }
                            }
                        }
                        is AgentEvent.ToolStarted -> {
                            calls++
                            text.setLength(0)
                            SubagentRuns.update(runId) {
                                copy(
                                    text = "",
                                    toolCalls = calls,
                                    steps = steps + SubagentStep(e.call.id, e.call.name, e.call.argumentsJson.take(300), SubagentStepState.Running),
                                )
                            }
                        }
                        is AgentEvent.ToolFinished -> {
                            if (e.result.sensitive) sensitive = true
                            val state = if (e.result.isError) SubagentStepState.Failed else SubagentStepState.Done
                            SubagentRuns.update(runId) {
                                copy(steps = steps.map { if (it.id == e.call.id) it.copy(state = state, durationMs = e.durationMs) else it })
                            }
                        }
                        is AgentEvent.ModelCall -> {
                            tokensCached += e.stats.cacheReadTokens
                            SubagentRuns.update(runId) { copy(cachedTokens = tokensCached) }
                        }
                        is AgentEvent.Usage -> {
                            tokensIn = e.inputTokens
                            tokensOut = e.outputTokens
                            SubagentRuns.update(runId) { copy(inputTokens = tokensIn, outputTokens = tokensOut) }
                        }
                        is AgentEvent.Retrying -> if (e.discardPartial) text.setLength(0)
                        is AgentEvent.Done -> done = e
                        is AgentEvent.Failed -> failed = e
                        else -> Unit
                    }
                }
                true
            } ?: false
        } catch (e: CancellationException) {
            SubagentRuns.update(runId) { copy(status = SubagentStatus.Cancelled, finishedAt = System.currentTimeMillis()) }
            throw e
        }
        val d = done
        val f = failed
        val (status, summary) = when {
            d != null && d.reason == atom.core.agent.StopReason.Completed -> SubagentStatus.Done to d.finalText
            d != null && d.reason == atom.core.agent.StopReason.StepLimit -> SubagentStatus.Partial to
                (d.finalText.ifBlank { "Stopped before finishing." } + "\n[The subagent reached its step limit; this is partial.]")
            d != null && d.reason == atom.core.agent.StopReason.Stuck -> SubagentStatus.Partial to
                (d.finalText.ifBlank { "Stopped before finishing." } + "\n[The subagent kept repeating itself and was stopped; this is partial.]")
            d != null -> SubagentStatus.Partial to d.finalText.ifBlank { "Stopped before finishing." }
            f != null -> SubagentStatus.Failed to "Failed: ${f.message}"
            !finished -> SubagentStatus.Partial to (text.toString().ifBlank { "Ran out of time after $calls tool calls before writing a summary." } +
                "\n[The subagent ran out of time; this is partial.]")
            else -> SubagentStatus.Failed to "The subagent ended without an answer."
        }
        val trimmed = summary.trim().let { if (it.length > MAX_SUMMARY) it.take(MAX_SUMMARY) + "\n…[summary trimmed]" else it }
        SubagentRuns.update(runId) {
            copy(
                status = status,
                summary = trimmed,
                text = "",
                error = f?.message,
                toolCalls = calls,
                inputTokens = tokensIn,
                outputTokens = tokensOut,
                finishedAt = System.currentTimeMillis(),
            )
        }
        return SubagentOutcome(runId, def.name, status, trimmed, calls, tokensIn, tokensOut, sensitive)
    }

    companion object {
        const val MAX_PARALLEL = 4
        private const val MAX_SUMMARY = 6_000
        private const val PUBLISH_MS = 150L

        /** Sent with no tools offered when the subagent stops before finishing (step limit, stuck), so it still reports back. */
        const val WRAP_UP =
            "Reply to the main agent now with your summary: what you did, what you found, and what is still unfinished."

        fun systemPrompt(def: SubagentDef, tools: List<Tool>): String = buildString {
            append("You are \"${def.name}\", a subagent of Atom, an assistant on the user's Android phone. ")
            append("You were handed one task by the main agent and work in your own context: you cannot see its conversation ")
            append("and you cannot ask the user questions, so make reasonable assumptions and note them.\n\n")
            append(def.instructions.trim())
            append("\n\n")
            if (tools.isEmpty()) {
                append("You have no tools; answer from reasoning alone.\n\n")
            } else {
                append("Use only the tools you were given. Never invent results of actions you did not take.\n\n")
            }
            append("When done, reply with a concise summary for the main agent (under 250 words): what you found or did, ")
            append("key facts with sources or paths, and anything unfinished or uncertain. No preamble.")
        }
    }
}
