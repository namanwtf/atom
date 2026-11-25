package atom.app.agent

import android.app.Application
import atom.app.AppGraph
import atom.app.data.Attachment
import atom.app.data.AttachmentKind
import atom.app.data.Attachments
import atom.app.data.Conversation
import atom.app.data.EngineSelection
import atom.app.data.EntryKind
import atom.app.data.StoredMessage
import atom.app.data.TitleSource
import atom.app.engine.RouteException
import atom.app.engine.RoutedEngine
import atom.app.feature.Features
import atom.app.service.AgentService
import atom.core.agent.Agent
import atom.core.agent.AgentEvent
import atom.core.agent.Compactor
import atom.core.agent.ContextPacker
import atom.core.agent.HistorySearch
import atom.core.agent.MessageInfo
import atom.core.agent.RunControl
import atom.core.agent.Scrubber
import atom.core.agent.StopReason
import atom.core.agent.TitleGenerator
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.LlmErrors
import atom.core.llm.LlmException
import atom.core.llm.ReasoningSupport
import atom.core.llm.ReasoningEffort
import atom.core.llm.Role
import atom.core.llm.ToolSpec
import atom.core.runtime.PhoneLease
import atom.core.runtime.QueuedMessage
import atom.core.runtime.SteerQueue
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import atom.core.tool.schema
import atom.device.Accessibility
import atom.device.DeviceTools
import atom.device.tools.ConfirmsItself
import atom.linux.LinuxTools
import atom.ui.feature.AgentHost
import atom.ui.feature.HostApproval
import atom.ui.feature.HostRun
import atom.ui.feature.HostStep
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.feature.StepState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

data class PendingApproval(val id: Long, val action: String, val expiresAtMillis: Long)

enum class RunOutcome { Completed, StepLimit, OutputLimit, Failed, Cancelled }

data class RetryNotice(val attempt: Int, val of: Int, val untilMillis: Long, val reason: String)

/** Everything the UI and the notifications need about one conversation's run (active or just finished). */
data class LiveRun(
    val runId: String,
    /** The record so far: history, the new user turn and every committed step (sensitive results scrubbed). */
    val conversation: Conversation,
    val source: RunSource,
    val phase: RunPhase,
    /** Stored index where this run's turn begins. */
    val turnStart: Int,
    val startedAt: Long,
    val stream: StreamState = StreamState(),
    val engineLabel: String? = null,
    val onDevice: Boolean = false,
    val notice: String? = null,
    val retry: RetryNotice? = null,
    val pending: PendingApproval? = null,
    val currentTool: String? = null,
    val compacting: Boolean = false,
    val outcome: RunOutcome? = null,
    val error: String? = null,
    val finalText: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
) {
    val active: Boolean get() = phase.active
}

sealed interface CompactResult {
    data class Done(val summarized: Int, val tokensBefore: Int, val tokensAfter: Int) : CompactResult
    data object NothingToCompact : CompactResult
    data object Busy : CompactResult
    data class Failed(val message: String) : CompactResult
}

/**
 * Runs agent tasks for any number of conversations in parallel. Each run streams into its own [LiveRun], keeps a
 * small checkpoint per step, waits for phone control only when it actually calls a phone-UI tool, and can be
 * steered, paused, resumed or stopped at safe points. Every source (chat, bubble, voice, schedules) goes through it.
 */
class TaskRunner(private val app: Application, private val graph: AppGraph) : AgentHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val executions = HashMap<String, Execution>()
    private val byRunId = ConcurrentHashMap<String, Execution>()
    private val queues = ConcurrentHashMap<String, SteerQueue>()
    private val approvals = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val approvalSeq = AtomicLong()
    private var holdCount = 0

    /** Exclusive phone-UI control shared by every run (and visible to overlays). */
    val lease = PhoneLease()

    private val _live = MutableStateFlow<Map<String, LiveRun>>(emptyMap())

    /** Active runs plus the last finished run per conversation, by conversation id. */
    val liveRuns: StateFlow<Map<String, LiveRun>> = _live.asStateFlow()

    /** The run that most needs attention (pending approval first, then the newest active run). */
    val live: StateFlow<LiveRun?> = _live
        .map { runs -> runs.values.firstOrNull { it.pending != null } ?: runs.values.filter { it.active }.maxByOrNull { it.startedAt } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val runs: StateFlow<List<HostRun>> = _live
        .map { runs -> runs.values.sortedByDescending { it.startedAt }.map(::toHostRun) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val isRunning: Boolean get() = _live.value.values.any { it.active }

    fun isRunning(conversationId: String): Boolean = _live.value[conversationId]?.active == true

    fun queue(conversationId: String): SteerQueue = queues.getOrPut(conversationId) { SteerQueue() }

    // ------------------------------------------------------------------ Starting

    /** Legacy entry used by voice commands: a new conversation from [prompt]. */
    fun start(base: Conversation?, prompt: String): Boolean {
        val id = send(base, prompt, emptyList(), RunSource.Chat) ?: return false
        graph.postChatRequest(atom.app.ChatRequest.Open(id))
        return true
    }

    override fun start(prompt: String, conversationId: String?, source: RunSource, attachments: List<String>): String? {
        if (conversationId != null && isRunning(conversationId)) return null
        val atts = attachments.mapNotNull { Attachments.fromPath(it) }
        return launchTurn(TurnRequest(conversationId ?: Conversation.newId(), base = null, prompt = prompt, attachments = atts, source = source))
    }

    /**
     * Sends a user message in [conversation] (a new one when null). While that conversation runs, the message is
     * queued instead ("Queued · N"). Returns the conversation id, or null if there was nothing to send.
     */
    fun send(conversation: Conversation?, text: String, attachments: List<Attachment>, source: RunSource = RunSource.Chat): String? {
        if (text.isBlank() && attachments.isEmpty()) return null
        val id = conversation?.id ?: Conversation.newId()
        val busy = synchronized(lock) { executions.containsKey(id) }
        if (busy) {
            val queued = queue(id).add(text.trim(), attachments.map { it.path }, hasImages = attachments.any { it.kind == AttachmentKind.Image })
            queuedAttachments["$id#${queued.id}"] = attachments
            return id
        }
        return launchTurn(TurnRequest(id, conversation, text.trim(), attachments, source))
    }

    /** Runs [conversation] as it is; its last visible message must be the user's (regenerate, edit). */
    fun resend(conversation: Conversation, source: RunSource = RunSource.Chat): String? {
        if (isRunning(conversation.id)) return null
        return launchTurn(TurnRequest(conversation.id, conversation, prompt = null, attachments = emptyList(), source = source))
    }

    private val queuedAttachments = ConcurrentHashMap<String, List<Attachment>>()

    private fun takeQueuedAttachments(conversationId: String, m: QueuedMessage): List<Attachment> =
        queuedAttachments.remove("$conversationId#${m.id}") ?: m.attachments.mapNotNull { Attachments.fromPath(it) }

    private class TurnRequest(
        val conversationId: String,
        val base: Conversation?,
        val prompt: String?,
        val attachments: List<Attachment>,
        val source: RunSource,
    )

    private fun launchTurn(request: TurnRequest): String? {
        val now = System.currentTimeMillis()
        val execution = synchronized(lock) {
            if (executions[request.conversationId] != null) return null
            Execution(UUID.randomUUID().toString(), request).also {
                executions[request.conversationId] = it
                byRunId[it.runId] = it
            }
        }
        val placeholder = (request.base ?: Conversation.empty(request.conversationId, now)).let { base ->
            if (request.prompt == null) base else base.copy(messages = base.messages + userEntry(request, now))
        }
        _live.update {
            it + (request.conversationId to LiveRun(
                runId = execution.runId,
                conversation = placeholder,
                source = request.source,
                phase = RunPhase.Starting,
                turnStart = (request.base?.messages?.size ?: 0).coerceAtMost(placeholder.messages.size),
                startedAt = now,
            ))
        }
        execution.job = scope.launch(execution.dispatcher) { execution.run() }
        AgentService.start(app)
        return request.conversationId
    }

    private fun userEntry(request: TurnRequest, now: Long) =
        StoredMessage(ChatMessage.user(request.prompt.orEmpty()), attachments = request.attachments, createdAt = now)

    // ------------------------------------------------------------------ Control

    override fun steer(conversationId: String, text: String) {
        if (text.isBlank()) return
        if (!isRunning(conversationId)) {
            start(text, conversationId, RunSource.Chat)
            return
        }
        queue(conversationId).add(text.trim(), steer = true)
    }

    /** "Send now" for a queued message; starts a turn right away when nothing runs. */
    fun sendNow(conversationId: String, queuedId: Long) {
        val q = queue(conversationId)
        if (isRunning(conversationId)) {
            q.sendNow(queuedId)
            return
        }
        val message = q.items.value.firstOrNull { it.id == queuedId } ?: return
        q.remove(queuedId)
        scope.launch {
            val base = graph.conversations.load(conversationId)
            send(base, message.text, takeQueuedAttachments(conversationId, message))
        }
    }

    fun removeQueued(conversationId: String, queuedId: Long) {
        queuedAttachments.remove("$conversationId#$queuedId")?.let(Attachments::deleteImages)
        queue(conversationId).remove(queuedId)
    }

    override fun pause(conversationId: String) {
        val e = synchronized(lock) { executions[conversationId] } ?: return
        if (e.paused.value) return
        e.paused.value = true
        lease.pause(e.runId)
        updateRun(conversationId) { copy(phase = RunPhase.Paused) }
        refreshHolds()
    }

    override fun resume(conversationId: String) {
        val e = synchronized(lock) { executions[conversationId] } ?: return
        if (!e.paused.value) return
        e.paused.value = false
        lease.resume(e.runId)
        updateRun(conversationId) { copy(phase = if (pending != null) RunPhase.WaitingForApproval else RunPhase.Running) }
        refreshHolds()
    }

    /** Cancels the run; whatever finished so far is kept. */
    override fun stop(conversationId: String) {
        synchronized(lock) { executions[conversationId] }?.job?.cancel()
    }

    fun stopAll() {
        synchronized(lock) { executions.values.toList() }.forEach { it.job?.cancel() }
    }

    /** Stops the run and waits until its results are saved. Returns false if saving failed. */
    suspend fun stopAndSave(conversationId: String): Boolean {
        val e = synchronized(lock) { executions[conversationId] } ?: return true
        e.job?.cancel()
        e.job?.join()
        return e.saved
    }

    override fun answer(approvalId: Long, approved: Boolean) {
        approvals[approvalId]?.complete(approved)
    }

    /**
     * Changes a conversation's own settings (title, pin, model, reasoning). A running task keeps the change, so its
     * final save cannot undo it. Returns the saved conversation, or null if it was never saved yet.
     */
    suspend fun editConversation(conversationId: String, change: (Conversation) -> Conversation): Conversation? {
        val e = synchronized(lock) { executions[conversationId] }
        e?.applyMeta(change)
        return graph.conversations.update(conversationId, change)
    }

    /** Drops the finished state of [conversationId] (its conversation is saved). */
    fun clearFinished(conversationId: String) {
        _live.update { runs -> runs[conversationId]?.takeIf { !it.active }?.let { runs - conversationId } ?: runs }
    }

    /** Finished runs stay visible for the host's "last result", but only a few are kept in memory. */
    private fun pruneFinished() {
        _live.update { runs ->
            val finished = runs.values.filter { !it.active }.sortedByDescending { it.startedAt }
            if (finished.size <= KEEP_FINISHED) runs else runs - finished.drop(KEEP_FINISHED).map { it.conversation.id }.toSet()
        }
    }

    // ------------------------------------------------------------------ For subagents

    /** The tools of run [runId] (a [ToolContext.taskId]), without the run's own lease and history tools. */
    fun toolsOf(runId: String): List<Tool> = byRunId[runId]?.tools.orEmpty().filter { it.spec.name !in RUN_SCOPED }

    /** Run [runId]'s pause and phone-lease control, for agents it spawns. Steering stays with the parent. */
    fun controlOf(runId: String): RunControl {
        val e = byRunId[runId] ?: return RunControl.None
        return object : RunControl {
            override suspend fun checkpoint() = e.control.checkpoint()
            override suspend fun acquirePhone() = e.control.acquirePhone()
        }
    }

    /** The model run [runId] actually uses. */
    fun selectionOf(runId: String): EngineSelection? = byRunId[runId]?.selectionInUse

    /** Context window of the newest active run, for features that size themselves to it. */
    fun currentContextWindow(): Int? = byRunId.values.maxByOrNull { it.startedAt }?.windowInUse

    // ------------------------------------------------------------------ Manual compaction

    suspend fun compact(conversationId: String): CompactResult {
        if (isRunning(conversationId)) return CompactResult.Busy
        val conversation = graph.conversations.load(conversationId) ?: return CompactResult.NothingToCompact
        val view = ModelHistory.build(conversation.messages)
        var routed: RoutedEngine? = null
        return try {
            val engine = graph.router.acquire(selectionFor(conversation).first).also { routed = it }
            val config = engine.agentConfig("", emptyList(), slowApprovals = false)
            val window = minOf(engine.contextWindow ?: config.context.budgetTokens, config.context.budgetTokens)
            val cut = Compactor.cutIndex(view.messages, keepRecentTokens = (window * 0.3).toInt())
            if (cut <= 0) return CompactResult.NothingToCompact
            val before = ContextPacker.measure("", view.messages, config.context)
            val result = Compactor.summarize(
                engine.engine,
                Compactor.request(engine.model, view.messages.subList(0, cut), maxOutputTokens = (window / 8).coerceIn(400, 2_000), transcriptBudgetChars = window * 3),
            )
            val summary = Compactor.summaryMessage(result.summary)
            val at = view.storedIndex.getOrElse(cut) { conversation.messages.size }
            val after = ContextPacker.measure("", listOf(summary) + view.messages.drop(cut), config.context)
            graph.conversations.update(conversationId) { c ->
                val messages = c.messages.toMutableList().apply { add(at.coerceAtMost(size), StoredMessage.summary(summary)) }
                c.copy(messages = messages, stats = c.stats.withCompaction(result.stats, before, after), contextTokens = after, updatedAt = System.currentTimeMillis())
            }
            CompactResult.Done(cut, before, after)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RouteException) {
            CompactResult.Failed(e.message ?: "")
        } catch (e: LlmException) {
            CompactResult.Failed(LlmErrors.explain(e))
        } finally {
            withContext(NonCancellable) { routed?.let { graph.router.release(it) } }
        }
    }

    // ------------------------------------------------------------------ Recovery

    /** Folds checkpoints of runs that a crash or kill cut short back in, and marks them interrupted. Never replays. */
    suspend fun recover() {
        for (id in graph.journal.pending()) {
            if (isRunning(id)) continue
            val saved = graph.conversations.load(id)
            if (saved == null) {
                graph.journal.finish(id)
                continue
            }
            val replayed = graph.journal.replay(id, saved) ?: saved
            val closed = replayed.messages + ModelHistory.closeOpenCalls(replayed.messages, INTERRUPTED_RESULT)
            val notice = StoredMessage.notice(app.getString(atom.app.R.string.run_interrupted))
            runCatching { graph.conversations.save(replayed.copy(messages = closed + notice, updatedAt = System.currentTimeMillis())) }
                .onSuccess { graph.journal.finish(id) }
        }
    }

    // ------------------------------------------------------------------ Shared helpers

    private fun updateRun(conversationId: String, change: LiveRun.() -> LiveRun) {
        _live.update { runs -> runs[conversationId]?.let { runs + (conversationId to it.change()) } ?: runs }
    }

    /** Power, accessibility events, wake lock and keep-screen-on follow the set of active, unpaused runs. */
    private fun refreshHolds() {
        val hold = synchronized(lock) { executions.isNotEmpty() }
        synchronized(lock) {
            if (hold && holdCount == 0) {
                graph.power.start()
                Accessibility.setTaskActive(true)
            } else if (!hold && holdCount > 0) {
                Accessibility.setTaskActive(false)
                graph.power.stop()
            }
            holdCount = if (hold) 1 else 0
        }
        scope.launch {
            holdsMutex.withLock {
                val now = synchronized(lock) { executions.values.count { !it.paused.value } }
                graph.runPower.update(now, graph.runtimePrefs.current().keepScreenOn)
            }
        }
    }

    private val holdsMutex = Mutex()

    init {
        // "Keep screen on while running" applies at once, also to a run already going.
        scope.launch { graph.runtimePrefs.settings.collect { refreshHolds() } }
    }

    /** The conversation's own model if it still exists, else the default; second is a notice when it fell back. */
    private suspend fun selectionFor(conversation: Conversation): Pair<EngineSelection?, String?> {
        val default = graph.settings.current().selection
        val saved = conversation.selection ?: return default to null
        if (available(saved)) return saved to null
        val notice = app.getString(atom.app.R.string.run_model_gone, describe(saved), default?.let(::describe) ?: app.getString(atom.app.R.string.engine_none))
        return default to notice
    }

    private fun available(selection: EngineSelection): Boolean = runCatching { graph.modelInfo.exists(selection) }.getOrDefault(true)

    private fun describe(selection: EngineSelection): String = runCatching { graph.modelInfo.label(selection) }.getOrDefault(
        when (selection) {
            is EngineSelection.Cloud -> selection.model
            is EngineSelection.Local -> selection.installedModelId
        },
    )

    private fun toHostRun(run: LiveRun): HostRun {
        val messages = run.conversation.messages
        val steps = ArrayList<HostStep>()
        for (i in run.turnStart until messages.size) {
            val m = messages[i]
            if (m.message.role != Role.Assistant) continue
            for (call in m.message.toolCalls) {
                val result = messages.drop(i + 1).takeWhile { it.message.role == Role.Tool }.firstOrNull { it.message.toolCallId == call.id }
                steps += HostStep(
                    id = "$i:${call.id}",
                    tool = call.name,
                    summary = Transcript.argsLine(call.argumentsJson),
                    state = when {
                        result == null -> if (run.active) StepState.Running else StepState.Unknown
                        result.isError -> StepState.Failed
                        else -> StepState.Completed
                    },
                    durationMs = result?.durationMs,
                )
            }
        }
        val lastAnswer = messages.lastOrNull { it.message.role == Role.Assistant && it.message.toolCalls.isEmpty() && it.kind == EntryKind.Message }
        return HostRun(
            conversationId = run.conversation.id,
            title = run.conversation.title,
            source = run.source,
            phase = run.phase,
            text = run.stream.text.ifEmpty { if (!run.active) lastAnswer?.message?.text.orEmpty() else "" },
            steps = steps,
            pending = run.pending?.let { HostApproval(it.id, it.action, it.expiresAtMillis) },
            queued = queues[run.conversation.id]?.items?.value?.map { it.text }.orEmpty(),
            error = run.error,
        )
    }

    // ------------------------------------------------------------------ One run

    private inner class Execution(val runId: String, private val request: TurnRequest) {
        val dispatcher = Dispatchers.IO.limitedParallelism(1)
        var job: Job? = null
        val paused = MutableStateFlow(false)

        @Volatile
        var saved = true

        private val id = request.conversationId
        private lateinit var conversation: Conversation
        private val stored = ArrayList<StoredMessage>()
        private val view = ArrayList<Int>()
        private val pendingSteer = ArrayDeque<QueuedMessage>()
        private var turnStart = 0
        private var stats = atom.core.agent.SessionStats()
        private var contextTokens: Int? = null
        private var contextWindow: Int? = null
        private var outcome: RunOutcome? = null
        private var error: String? = null
        private var finalText: String? = null
        private var routed: RoutedEngine? = null
        private var support = ReasoningSupport.None
        private var journalOpen = false

        private val pendingText = StringBuilder()
        private val pendingReasoning = StringBuilder()
        private var flushJob: Job? = null

        private val myApprovals = ConcurrentHashMap.newKeySet<Long>()
        private var titleCandidate: Conversation? = null

        private val toolContext = object : ToolContext {
            override val taskId: String = runId
            override suspend fun confirm(action: String): Boolean = requestApproval(action)
        }

        val startedAt = System.currentTimeMillis()

        @Volatile
        var tools: List<Tool> = emptyList()

        @Volatile
        var selectionInUse: EngineSelection? = null

        @Volatile
        var windowInUse: Int? = null

        val control = object : RunControl {
            override suspend fun checkpoint() {
                if (!paused.value) return
                flushStream()
                paused.first { !it }
            }

            override fun drainSteering(): List<ChatMessage> {
                val messages = queue(id).drainSteering()
                pendingSteer.addAll(messages)
                return messages.map { m ->
                    val atts = takeQueuedAttachments(id, m)
                    pendingAttachments.addLast(atts)
                    val note = Attachments.describeForModel(atts)
                    ChatMessage.user(listOfNotNull(m.text.takeIf { it.isNotBlank() }, note).joinToString("\n\n"))
                }
            }

            override suspend fun acquirePhone() {
                if (lease.holds(runId)) return
                if (lease.state.value.holder != null) updateRun(id) { copy(phase = RunPhase.WaitingForControl) }
                lease.acquire(runId)
                updateRun(id) { if (phase == RunPhase.WaitingForControl) copy(phase = RunPhase.Running) else this }
            }
        }

        private val pendingAttachments = ArrayDeque<List<Attachment>>()

        suspend fun run() {
            try {
                prepare()
                refreshHolds()
                val (selection, fallbackNotice) = selectionFor(conversation)
                val engine = graph.router.acquire(selection).also { routed = it }
                selectionInUse = engine.selection ?: selection
                windowInUse = engine.contextWindow
                support = ReasoningSupport.of(engine.reasoning.supported, engine.reasoning.levels)
                val settings = graph.settings.current()
                val prefs = graph.runtimePrefs.current()
                val shell = graph.shell()
                val tools = buildTools(settings.confirmActTools, shell)
                this.tools = tools
                val registry = ToolRegistry(tools)
                val reasoningSetting = support.request(
                    enabled = conversation.reasoningEnabled ?: settings.reasoningByDefault,
                    effort = conversation.reasoningEffort ?: settings.defaultReasoning,
                )
                val window = engine.contextWindow
                val config = engine.agentConfig(
                    systemPrompt = SystemPrompt.build(shell.tier, engine.onDevice) +
                        Features.all.mapNotNull { runCatching { it.promptHint(app) }.getOrNull() }.joinToString("") { "\n\n" + it },
                    tools = registry.specs,
                    slowApprovals = settings.confirmActTools,
                ).copy(reasoning = reasoningSetting, contextWindow = window, autoCompact = prefs.autoCompact)
                val notice = listOfNotNull(
                    fallbackNotice,
                    engine.notice,
                    if (window == null && prefs.autoCompact) app.getString(atom.app.R.string.run_window_unknown) else null,
                ).joinToString("\n").ifEmpty { null }
                if (fallbackNotice != null) commit(StoredMessage.notice(fallbackNotice), modelVisible = false)
                contextWindow = config.usableWindow
                updateRun(id) { copy(phase = if (paused.value) RunPhase.Paused else RunPhase.Running, engineLabel = engine.label, onDevice = engine.onDevice, notice = notice) }

                val images = loadTurnImages()
                val history = ModelHistory.build(stored, images)
                view.clear()
                view.addAll(history.storedIndex)
                stats = stats.withTurn()
                Agent(engine.engine, registry, config).run(history.messages, toolContext, control).collect { handle(it) }
                if (outcome == null) outcome = RunOutcome.Completed
                finish()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    outcome = RunOutcome.Cancelled
                    finish()
                }
                throw e
            } catch (e: RouteException) {
                outcome = RunOutcome.Failed
                error = e.message
                finish()
            } catch (e: Exception) {
                outcome = RunOutcome.Failed
                error = e.message ?: e::class.simpleName
                finish()
            } finally {
                withContext(NonCancellable) { cleanup() }
            }
        }

        private suspend fun prepare() {
            val now = System.currentTimeMillis()
            val loaded = request.base ?: graph.conversations.load(id)
            val base = loaded ?: Conversation.empty(id, now)
            val withUser = if (request.prompt != null) {
                base.copy(
                    messages = base.messages + userEntry(request, now),
                    title = base.title.ifBlank { Conversation.titleFrom(request.prompt.ifBlank { request.attachments.firstOrNull()?.name.orEmpty() }) },
                    updatedAt = now,
                )
            } else {
                base.copy(updatedAt = now)
            }
            conversation = withUser
            stats = withUser.stats
            stored.addAll(withUser.messages)
            turnStart = (stored.indexOfLast { it.isVisibleUser }).coerceAtLeast(0)
            publish()
            updateRun(id) { copy(turnStart = this@Execution.turnStart) }
            graph.conversations.save(snapshot())
            graph.journal.begin(id, stored.size)
            journalOpen = true
        }

        private suspend fun loadTurnImages(): Map<Int, List<ContentPart.Image>> {
            val at = stored.indexOfLast { it.isVisibleUser }
            if (at < 0) return emptyMap()
            val paths = stored[at].attachments.filter { it.kind == AttachmentKind.Image }.take(Attachments.MAX_IMAGES)
            if (paths.isEmpty()) return emptyMap()
            return mapOf(at to paths.mapNotNull { Attachments.loadImage(it.path) })
        }

        private suspend fun buildTools(confirmAct: Boolean, shell: atom.device.ShellExecutor): List<Tool> {
            val base = DeviceTools.create(app, shell) + LinuxTools.create(app) +
                Features.all.flatMap { f -> runCatching { f.tools(app) }.getOrDefault(emptyList()) }
            val wrapped = base.map { if (confirmAct && it.risk == Risk.Act && it !is ConfirmsItself) ConfirmingTool(it) else it }
            val runScoped = listOf(HistorySearch.tool { ModelHistory.searchable(stored.toList()) }, releaseTool())
            return (runScoped + wrapped).distinctBy { it.spec.name }.onEach { riskOf[it.spec.name] = it.risk }
        }

        private fun releaseTool(): Tool = object : Tool {
            override val spec = ToolSpec(
                name = RUN_SCOPED.first(),
                description = "Give phone control back so the user or another task can use the screen. Call it once you " +
                    "no longer need to read or operate the phone UI for this task.",
                parameters = schema(),
            )
            override val risk = Risk.Read
            override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
                lease.release(runId)
                return ToolResult.ok("Phone control released. Calling a screen tool again waits for control.")
            }
        }

        private suspend fun handle(event: AgentEvent) {
            when (event) {
                is AgentEvent.Text -> {
                    pendingText.append(event.delta)
                    scheduleFlush()
                }
                is AgentEvent.Reasoning -> {
                    pendingReasoning.append(event.delta)
                    scheduleFlush()
                }
                is AgentEvent.Retrying -> {
                    if (event.discardPartial) {
                        pendingText.clear()
                        pendingReasoning.clear()
                        updateRun(id) { copy(stream = StreamState()) }
                    }
                    val notice = RetryNotice(event.attempt, event.maxAttempts, System.currentTimeMillis() + event.delayMs, event.reason)
                    updateRun(id) { copy(retry = notice) }
                }
                is AgentEvent.ModelCall -> {
                    stats = stats.withModelCall(event.stats)
                    updateRun(id) { copy(retry = null) }
                    graph.runPower.progress()
                }
                is AgentEvent.Usage -> updateRun(id) { copy(inputTokens = event.inputTokens, outputTokens = event.outputTokens) }
                is AgentEvent.Appended -> appended(event.message, event.info)
                is AgentEvent.ToolStarted -> {
                    flushStream()
                    updateRun(id) {
                        copy(
                            currentTool = event.call.name,
                            stream = stream.copy(runningCallId = event.call.id, runningSince = System.currentTimeMillis()),
                        )
                    }
                }
                is AgentEvent.ToolFinished -> {
                    stats = stats.withTool(event.durationMs, event.result.isError)
                    updateRun(id) { copy(currentTool = null, stream = stream.copy(runningCallId = null, runningSince = null)) }
                    graph.runPower.progress()
                }
                is AgentEvent.ContextUsage -> {
                    contextTokens = event.usedTokens
                    if (event.windowTokens != null) contextWindow = event.windowTokens
                    publish()
                }
                AgentEvent.Compacting -> updateRun(id) { copy(compacting = true) }
                is AgentEvent.Compacted -> {
                    val at = view.getOrElse(event.summarizedCount) { stored.size }
                    val entry = StoredMessage.summary(event.summary)
                    stored.add(at, entry)
                    for (k in view.indices) if (view[k] >= at) view[k] = view[k] + 1
                    val kept = view.drop(event.summarizedCount)
                    view.clear()
                    view += at
                    view += kept
                    if (journalOpen) graph.journal.insert(id, at, entry)
                    stats = stats.withCompaction(event.cost, event.tokensBefore, event.tokensAfter)
                    updateRun(id) { copy(compacting = false) }
                    publish()
                }
                is AgentEvent.CompactionFailed -> {
                    updateRun(id) { copy(compacting = false) }
                    commit(StoredMessage.notice(app.getString(atom.app.R.string.run_compaction_failed, event.reason)), modelVisible = false)
                }
                is AgentEvent.Done -> {
                    flushStream()
                    finalText = event.finalText
                    outcome = when (event.reason) {
                        StopReason.Completed -> RunOutcome.Completed
                        StopReason.StepLimit -> RunOutcome.StepLimit
                        StopReason.OutputLimit -> RunOutcome.OutputLimit
                    }
                }
                is AgentEvent.Failed -> {
                    flushStream()
                    outcome = RunOutcome.Failed
                    error = event.message
                }
            }
        }

        private fun appended(message: ChatMessage, info: MessageInfo) {
            val now = System.currentTimeMillis()
            val entry = when (message.role) {
                Role.Assistant -> {
                    flushJob?.cancel()
                    pendingText.clear()
                    pendingReasoning.clear()
                    updateRun(id) { copy(stream = stream.copy(text = "", reasoning = "", reasoningStartedAt = null, reasoningEndedAt = null)) }
                    StoredMessage(message, reasoning = info.reasoning, reasoningMs = info.reasoningMs, createdAt = now)
                }
                Role.Tool -> StoredMessage(
                    message = if (info.sensitive) Scrubber.scrub(message) else stripImages(message),
                    durationMs = info.durationMs,
                    isError = info.isError,
                    risk = message.toolName?.let { riskOf[it] },
                    createdAt = now,
                )
                else -> if (info.steering) {
                    val q = pendingSteer.removeFirstOrNull()
                    StoredMessage(ChatMessage.user(q?.text ?: message.text), attachments = pendingAttachments.removeFirstOrNull().orEmpty(), steering = true, createdAt = now)
                } else {
                    StoredMessage(message, hidden = info.internal, createdAt = now)
                }
            }
            commit(entry, modelVisible = true)
            if (message.role == Role.Assistant || message.role == Role.Tool) graph.runPower.progress()
        }

        private val riskOf = HashMap<String, Risk>()

        private fun commit(entry: StoredMessage, modelVisible: Boolean) {
            stored += entry
            if (modelVisible) view += stored.lastIndex
            if (journalOpen) graph.journal.append(id, entry)
            publish()
        }

        suspend fun applyMeta(change: (Conversation) -> Conversation) = withContext(dispatcher) {
            if (!::conversation.isInitialized) return@withContext
            val changed = change(conversation)
            conversation = conversation.copy(
                title = changed.title,
                titleSource = changed.titleSource,
                pinned = changed.pinned,
                selection = changed.selection,
                reasoningEnabled = changed.reasoningEnabled,
                reasoningEffort = changed.reasoningEffort,
            )
            publish()
        }

        private fun snapshot(): Conversation = conversation.copy(
            messages = stored.toList(),
            stats = stats,
            contextTokens = contextTokens ?: conversation.contextTokens,
            contextWindow = contextWindow ?: conversation.contextWindow,
            updatedAt = System.currentTimeMillis(),
        )

        private fun publish() {
            val snap = snapshot()
            updateRun(id) { copy(conversation = snap) }
        }

        private fun scheduleFlush() {
            if (flushJob?.isActive == true) return
            flushJob = scope.launch(dispatcher) {
                delay(TEXT_FLUSH_MS)
                flushStream()
            }
        }

        private fun flushStream() {
            if (pendingText.isEmpty() && pendingReasoning.isEmpty()) return
            val text = pendingText.toString()
            val reasoning = pendingReasoning.toString()
            pendingText.clear()
            pendingReasoning.clear()
            val now = System.currentTimeMillis()
            updateRun(id) {
                val s = stream
                copy(
                    stream = s.copy(
                        text = s.text + text,
                        reasoning = s.reasoning + reasoning,
                        reasoningStartedAt = s.reasoningStartedAt ?: if (reasoning.isNotEmpty()) now else null,
                        reasoningEndedAt = s.reasoningEndedAt ?: if (text.isNotEmpty() && (s.reasoning.isNotEmpty() || reasoning.isNotEmpty())) now else null,
                    ),
                )
            }
        }

        private suspend fun requestApproval(action: String): Boolean {
            val approvalId = approvalSeq.incrementAndGet()
            val deferred = CompletableDeferred<Boolean>()
            approvals[approvalId] = deferred
            myApprovals += approvalId
            val pending = PendingApproval(approvalId, action, System.currentTimeMillis() + APPROVAL_TIMEOUT_MS)
            updateRun(id) { copy(pending = pending, phase = RunPhase.WaitingForApproval, stream = stream.copy(awaitingApproval = true)) }
            val approved = try {
                withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
            } finally {
                approvals.remove(approvalId)
                myApprovals -= approvalId
                updateRun(id) {
                    copy(
                        pending = null,
                        phase = if (paused.value) RunPhase.Paused else RunPhase.Running,
                        stream = stream.copy(awaitingApproval = false),
                    )
                }
            }
            return approved
        }

        private suspend fun finish() {
            flushJob?.cancel()
            if (!::conversation.isInitialized) {
                saved = false
                updateRun(id) { copy(phase = if (outcome == RunOutcome.Cancelled) RunPhase.Cancelled else RunPhase.Failed, outcome = outcome, error = error) }
                return
            }
            val live = _live.value[id]
            val partial = (live?.stream?.text.orEmpty() + pendingText).trim()
            val partialReasoning = (live?.stream?.reasoning.orEmpty() + pendingReasoning).trim()
            pendingText.clear()
            pendingReasoning.clear()
            if (partial.isNotEmpty() || partialReasoning.isNotEmpty()) {
                stored += StoredMessage(ChatMessage.assistant(partial), reasoning = partialReasoning.ifEmpty { null }, createdAt = System.currentTimeMillis())
            }
            stored += ModelHistory.closeOpenCalls(stored, if (outcome == RunOutcome.Cancelled) STOPPED_RESULT else INTERRUPTED_RESULT)
            when (outcome) {
                RunOutcome.Cancelled -> stored += StoredMessage.notice(app.getString(atom.app.R.string.run_stopped))
                RunOutcome.Failed -> stored += StoredMessage.notice(error ?: app.getString(atom.app.R.string.run_failed)).copy(isError = true)
                RunOutcome.StepLimit -> stored += StoredMessage.notice(app.getString(atom.app.R.string.run_step_limit))
                RunOutcome.OutputLimit -> stored += StoredMessage.notice(LlmErrors.explain(atom.core.llm.LlmFailure.OutputLimit))
                else -> Unit
            }
            val final = snapshot()
            saved = runCatching { graph.conversations.save(final) }.isSuccess
            if (saved && journalOpen) {
                graph.journal.finish(id)
                journalOpen = false
            }
            val phase = when (outcome) {
                RunOutcome.Failed -> RunPhase.Failed
                RunOutcome.Cancelled -> RunPhase.Cancelled
                else -> RunPhase.Done
            }
            updateRun(id) {
                copy(
                    conversation = final,
                    stream = StreamState(),
                    phase = phase,
                    outcome = outcome,
                    error = error,
                    finalText = finalText,
                    pending = null,
                    currentTool = null,
                    retry = null,
                    compacting = false,
                )
            }
            if (outcome == RunOutcome.Completed) titleCandidate = final
            pruneFinished()
        }

        private suspend fun generateTitle(final: Conversation) {
            if (final.titleSource != TitleSource.Placeholder) return
            val user = final.messages.firstOrNull { it.isVisibleUser }?.message?.text ?: return
            val answer = final.messages.lastOrNull { it.message.role == Role.Assistant && it.message.toolCalls.isEmpty() && it.message.text.isNotBlank() }
                ?.message?.text ?: return
            val choice = runCatching { graph.modelRoles.titleModel(final.selection ?: selectionUsed) }.getOrNull() ?: return
            var extra: RoutedEngine? = null
            try {
                val engine = runCatching { graph.router.acquire(choice.selection) }.getOrNull()?.also { extra = it } ?: return
                val reasoning = if (choice.reasoning == null && ReasoningSupport.of(engine.reasoning.supported, engine.reasoning.levels).canDisable) {
                    ReasoningEffort.Off
                } else {
                    choice.reasoning
                }
                val title = withTimeoutOrNull(TITLE_TIMEOUT_MS) {
                    runCatching { TitleGenerator.generate(engine.engine, TitleGenerator.request(engine.model, user, answer, reasoning)) }.getOrNull()
                } ?: return
                // Through editConversation, so a turn that started meanwhile keeps the title when it saves.
                val updated = editConversation(id) { c ->
                    if (c.titleSource == TitleSource.Placeholder) c.copy(title = title, titleSource = TitleSource.Generated) else c
                } ?: return
                if (!isRunning(id)) updateRun(id) { copy(conversation = conversation.copy(title = updated.title, titleSource = updated.titleSource)) }
            } finally {
                extra?.let { graph.router.release(it) }
            }
        }

        private suspend fun cleanup() {
            myApprovals.forEach { approvals.remove(it)?.cancel() }
            selectionUsed = routed?.selection
            routed?.let { graph.router.release(it) }
            routed = null
            lease.release(runId)
            byRunId.remove(runId)
            val next = synchronized(lock) {
                executions.remove(id)
                if (outcome == RunOutcome.Completed) queue(id).pollNext() else null
            }
            refreshHolds()
            if (next != null) {
                val base = _live.value[id]?.conversation
                send(base, next.text, takeQueuedAttachments(id, next), request.source)
            }
            // Titles are written off the run's critical path, so the conversation is free for the next turn at once.
            titleCandidate?.let { final -> scope.launch { generateTitle(final) } }
        }

        private var selectionUsed: EngineSelection? = null

        private fun stripImages(msg: ChatMessage): ChatMessage =
            if (msg.parts.none { it is ContentPart.Image }) msg else msg.copy(parts = msg.parts.filterIsInstance<ContentPart.Text>())
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 60_000L
        const val TITLE_TIMEOUT_MS = 20_000L
        const val KEEP_FINISHED = 6
        val RUN_SCOPED = setOf("release_phone_control")

        /** About two frames at 60 Hz: smooth to the eye, far fewer recompositions than per token. */
        const val TEXT_FLUSH_MS = 32L

        const val STOPPED_RESULT = "[Stopped by the user before this finished. Outcome unknown.]"
        const val INTERRUPTED_RESULT = "[Interrupted: Atom was closed before this finished. Outcome unknown.]"
    }
}
