package atom.app.agent

import android.app.Application
import atom.app.AppGraph
import atom.app.data.Conversation
import atom.app.data.StoredMessage
import atom.app.engine.RouteException
import atom.app.engine.RoutedEngine
import atom.app.service.AgentService
import atom.core.agent.Agent
import atom.core.agent.AgentEvent
import atom.core.agent.StopReason
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.tool.Risk
import atom.core.tool.ToolContext
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import atom.device.Accessibility
import atom.device.DeviceTools
import atom.linux.LinuxTools
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class TaskPhase {
    Preparing, Running, Completed, StepLimit, Failed, Cancelled;

    val active: Boolean get() = this == Preparing || this == Running
}

data class PendingApproval(val id: Long, val action: String, val expiresAtMillis: Long)

/** Everything the UI and the notification need about the task in flight (or the one that just ended). */
data class LiveTask(
    val taskId: String,
    /** While running: history plus the new user turn. Once finished: the saved conversation. */
    val conversation: Conversation,
    /** Streamed since the task began; empty once finished (the conversation then holds everything). */
    val items: List<TranscriptItem> = emptyList(),
    val phase: TaskPhase = TaskPhase.Preparing,
    val engineLabel: String? = null,
    val onDevice: Boolean = false,
    val notice: String? = null,
    val pending: PendingApproval? = null,
    val currentTool: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val error: String? = null,
    val finalText: String? = null,
)

/**
 * Runs one agent task at a time. All task state is mutated on a single-threaded dispatcher, so streamed text,
 * tool steps and the throttled text flush can never interleave out of order.
 */
class TaskRunner(private val app: Application, private val graph: AppGraph) {
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val _live = MutableStateFlow<LiveTask?>(null)
    val live: StateFlow<LiveTask?> = _live.asStateFlow()

    private var job: Job? = null
    private val approvalSeq = AtomicLong()
    private val approval = AtomicReference<Pair<Long, CompletableDeferred<Boolean>>?>(null)

    val isRunning: Boolean get() = _live.value?.phase?.active == true

    /** Starts a task continuing [base] (or a new conversation). Returns false if one is already running. */
    fun start(base: Conversation?, prompt: String): Boolean {
        val text = prompt.trim()
        if (text.isEmpty() || isRunning) return false
        val now = System.currentTimeMillis()
        val origin = base ?: Conversation(
            id = Conversation.newId(),
            title = Conversation.titleFrom(text),
            createdAt = now,
            updatedAt = now,
            messages = emptyList(),
        )
        val conversation = origin.copy(updatedAt = now, messages = origin.messages + StoredMessage(ChatMessage.user(text)))
        val task = LiveTask(taskId = UUID.randomUUID().toString(), conversation = conversation)
        _live.value = task
        job = scope.launch { Execution(task.taskId, conversation).run() }
        AgentService.start(app)
        return true
    }

    /** Cancels the task immediately; whatever finished so far is kept in the conversation. */
    fun stop() {
        job?.cancel()
    }

    fun answer(approvalId: Long, approved: Boolean) {
        val current = approval.get() ?: return
        if (current.first == approvalId) current.second.complete(approved)
    }

    /** Drops the banner state of a finished task. */
    fun clearFinished() {
        _live.update { if (it != null && !it.phase.active) null else it }
    }

    private inner class Execution(private val taskId: String, private val base: Conversation) {
        private var seq = 0
        private var textKey: String? = null
        private val pendingText = StringBuilder()
        private var flushJob: Job? = null
        private var runningStepKey: String? = null
        private var risks: Map<String, Risk> = emptyMap()

        // Mirror of the agent's transcript, so a cancelled task still keeps its completed steps.
        private val committed = ArrayList<StoredMessage>()
        private val streamText = StringBuilder()
        private var blockOpen = false
        private var blockText = ""
        private val blockCalls = ArrayList<ToolCall>()
        private val blockResults = ArrayList<StoredMessage>()
        private val metas = ArrayList<StoredMessage>()

        private val toolContext = object : ToolContext {
            override val taskId: String = this@Execution.taskId
            override suspend fun confirm(action: String): Boolean = requestApproval(action)
        }

        suspend fun run() {
            var routed: RoutedEngine? = null
            var powerStarted = false
            try {
                graph.conversations.save(base)
                graph.power.start()
                powerStarted = true
                Accessibility.setTaskActive(true)
                val settings = graph.settings.current()
                val shell = graph.shell()
                val engine = graph.router.acquire().also { routed = it }
                update { copy(phase = TaskPhase.Running, engineLabel = engine.label, onDevice = engine.onDevice, notice = engine.notice) }

                val deviceTools = DeviceTools.create(app, shell) + LinuxTools.create(app)
                val tools = if (settings.confirmActTools) {
                    deviceTools.map { if (it.risk == Risk.Act) ConfirmingTool(it) else it }
                } else {
                    deviceTools
                }
                risks = tools.associate { it.spec.name to it.risk }
                val registry = ToolRegistry(tools)
                val config = engine.agentConfig(
                    systemPrompt = SystemPrompt.build(shell.tier, engine.onDevice),
                    tools = registry.specs,
                    slowApprovals = settings.confirmActTools,
                )
                Agent(engine.engine, registry, config).run(base.history, toolContext).collect { handle(it) }
                if (_live.value?.takeIf { it.taskId == taskId }?.phase?.active == true) {
                    finish(TaskPhase.Completed, mirrored(), error = null, finalText = null)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finish(TaskPhase.Cancelled, mirrored(), error = null, finalText = null) }
                throw e
            } catch (e: RouteException) {
                finish(TaskPhase.Failed, emptyList(), error = e.message, finalText = null)
            } catch (e: Exception) {
                finish(TaskPhase.Failed, mirrored(), error = e.message ?: e::class.simpleName, finalText = null)
            } finally {
                withContext(NonCancellable) {
                    approval.getAndSet(null)?.second?.cancel()
                    routed?.let { graph.router.release(it) }
                    Accessibility.setTaskActive(false)
                    if (powerStarted) graph.power.stop()
                }
            }
        }

        private suspend fun handle(event: AgentEvent) {
            when (event) {
                is AgentEvent.Text -> {
                    if (blockOpen) commitBlock()
                    streamText.append(event.delta)
                    pendingText.append(event.delta)
                    scheduleFlush()
                }
                is AgentEvent.Reasoning -> Unit
                is AgentEvent.Usage -> {
                    // Usage closes the model's turn; the tools of that turn follow.
                    if (blockOpen) commitBlock()
                    blockText = streamText.toString()
                    streamText.clear()
                    blockOpen = true
                    update { copy(inputTokens = event.inputTokens, outputTokens = event.outputTokens) }
                }
                is AgentEvent.ToolStarted -> {
                    flushText()
                    textKey = null
                    val key = "L${seq++}"
                    runningStepKey = key
                    val call = event.call
                    val step = TranscriptItem.Step(
                        key = key,
                        name = call.name,
                        argsLine = Transcript.argsLine(call.argumentsJson),
                        argsFull = Transcript.argsFull(call.argumentsJson),
                        status = StepStatus.Running,
                        durationMs = null,
                        result = null,
                        risk = risks[call.name],
                    )
                    update { copy(items = items + step, currentTool = call.name) }
                }
                is AgentEvent.ToolFinished -> {
                    val stored = StoredMessage(
                        message = ChatMessage.toolResult(event.call, textOnly(event.result)),
                        durationMs = event.durationMs,
                        isError = event.result.isError,
                        risk = risks[event.call.name],
                    )
                    metas += stored
                    blockCalls += event.call
                    blockResults += stored
                    val key = runningStepKey
                    runningStepKey = null
                    update {
                        copy(
                            currentTool = null,
                            items = items.map { item ->
                                if (item is TranscriptItem.Step && item.key == key) {
                                    item.copy(
                                        status = if (event.result.isError) StepStatus.Error else StepStatus.Ok,
                                        durationMs = event.durationMs,
                                        result = Transcript.preview(event.result.text),
                                    )
                                } else {
                                    item
                                }
                            },
                        )
                    }
                }
                is AgentEvent.Done -> {
                    flushText()
                    val phase = if (event.reason == StopReason.StepLimit) TaskPhase.StepLimit else TaskPhase.Completed
                    finish(phase, fromAgent(event.newMessages), error = null, finalText = event.finalText)
                }
                is AgentEvent.Failed -> {
                    flushText()
                    finish(TaskPhase.Failed, fromAgent(event.newMessages), error = event.message, finalText = null)
                }
            }
        }

        private suspend fun requestApproval(action: String): Boolean {
            val id = approvalSeq.incrementAndGet()
            val deferred = CompletableDeferred<Boolean>()
            val entry = id to deferred
            approval.set(entry)
            val stepKey = runningStepKey
            update {
                copy(
                    pending = PendingApproval(id, action, System.currentTimeMillis() + APPROVAL_TIMEOUT_MS),
                    items = items.withStepStatus(stepKey, StepStatus.AwaitingApproval),
                )
            }
            val approved = try {
                withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
            } finally {
                approval.compareAndSet(entry, null)
            }
            update { copy(pending = null, items = items.withStepStatus(stepKey, StepStatus.Running)) }
            return approved
        }

        private fun List<TranscriptItem>.withStepStatus(key: String?, status: StepStatus) =
            if (key == null) this else map { if (it is TranscriptItem.Step && it.key == key) it.copy(status = status) else it }

        /** Coalesces token deltas into at most one state update per frame-ish interval. */
        private fun scheduleFlush() {
            if (flushJob?.isActive == true) return
            flushJob = scope.launch {
                delay(TEXT_FLUSH_MS)
                flushText()
            }
        }

        private fun flushText() {
            if (pendingText.isEmpty()) return
            val chunk = pendingText.toString()
            pendingText.clear()
            val key = textKey
            if (key == null) {
                val newKey = "L${seq++}"
                textKey = newKey
                update { copy(items = items + TranscriptItem.Assistant(newKey, chunk)) }
            } else {
                update {
                    val last = items.lastOrNull()
                    if (last is TranscriptItem.Assistant && last.key == key) {
                        copy(items = items.dropLast(1) + last.copy(text = last.text + chunk))
                    } else {
                        copy(items = items + TranscriptItem.Assistant(key, chunk))
                    }
                }
            }
        }

        private fun commitBlock() {
            committed += StoredMessage(ChatMessage.assistant(blockText, blockCalls.toList()))
            committed += blockResults
            blockOpen = false
            blockText = ""
            blockCalls.clear()
            blockResults.clear()
        }

        /** The transcript so far, never leaving a tool call without its result. */
        private fun mirrored(): List<StoredMessage> = buildList {
            addAll(committed)
            if (blockOpen && (blockText.isNotEmpty() || blockCalls.isNotEmpty())) {
                add(StoredMessage(ChatMessage.assistant(blockText, blockCalls.toList())))
                addAll(blockResults)
            }
            if (streamText.isNotEmpty()) add(StoredMessage(ChatMessage.assistant(streamText.toString())))
        }

        private fun fromAgent(messages: List<ChatMessage>): List<StoredMessage> {
            var toolIndex = 0
            return messages.map { msg ->
                when (msg.role) {
                    Role.Tool -> metas.getOrNull(toolIndex++)?.copy(message = stripImages(msg)) ?: StoredMessage(stripImages(msg))
                    Role.User -> StoredMessage(msg, hidden = true)
                    else -> StoredMessage(stripImages(msg))
                }
            }
        }

        private suspend fun finish(phase: TaskPhase, added: List<StoredMessage>, error: String?, finalText: String?) {
            flushJob?.cancel()
            pendingText.clear()
            val final = base.copy(updatedAt = System.currentTimeMillis(), messages = base.messages + added)
            runCatching { graph.conversations.save(final) }
            update {
                copy(
                    conversation = final,
                    items = emptyList(),
                    phase = phase,
                    pending = null,
                    currentTool = null,
                    error = error,
                    finalText = finalText,
                )
            }
        }

        private inline fun update(crossinline change: LiveTask.() -> LiveTask) {
            _live.update { if (it != null && it.taskId == taskId) it.change() else it }
        }
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 60_000L

        /** ~2 frames at 60 Hz: smooth to the eye, an order of magnitude fewer recompositions than per-token. */
        const val TEXT_FLUSH_MS = 32L

        fun textOnly(result: ToolResult): List<ContentPart> = listOf(ContentPart.Text(result.text))

        fun stripImages(msg: ChatMessage): ChatMessage =
            if (msg.parts.none { it is ContentPart.Image }) msg else msg.copy(parts = msg.parts.filterIsInstance<ContentPart.Text>())
    }
}
