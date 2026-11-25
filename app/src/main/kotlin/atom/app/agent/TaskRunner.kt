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
import atom.automation.CapabilityTools
import atom.automation.ToolOrigin
import atom.linux.LinuxTools
import atom.ui.feature.AgentHost
import atom.ui.feature.HostApproval
import atom.ui.feature.HostRun
import atom.ui.feature.HostStep
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.ui.feature.StepState
import atom.app.data.ModelRole
import atom.characters.session.CharacterRun
import atom.characters.session.CharacterSession
import atom.extend.subagent.SubagentRuns
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
    /** "<name> is replying…" in character chats. */
    val replyingLabel: String? = null,
    /** This run produces an alternative character reply (no tools) instead of a new turn. */
    val regenerating: Boolean = false,
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

    override fun start(prompt: String, conversationId: String?, source: RunSource, attachments: List<String>): String? =
        start(prompt, conversationId, source, attachments, capabilities = null)

    /**
     * Starts a run whose tool set is limited to [capabilities] (scheduled tasks, automation agent steps). Null keeps
     * every tool; an empty set is "Chat only" and the run gets no tools at all.
     */
    fun start(
        prompt: String,
        conversationId: String?,
        source: RunSource,
        attachments: List<String>,
        capabilities: Set<atom.automation.Capability>?,
    ): String? {
        if (conversationId != null && isRunning(conversationId)) return null
        val id = conversationId ?: Conversation.newId()
        if (attachments.isEmpty()) {
            return launchTurn(TurnRequest(id, base = null, prompt = prompt, attachments = emptyList(), source = source, capabilities = capabilities))
        }
        if (!claim(id)) return null
        scope.launch {
            // Image files are sent as pixels (downscaled copies); anything else is passed as a path reference.
            val atts = attachments.mapNotNull { Attachments.fromPathForModel(app, it) }
            release(id)
            launchTurn(TurnRequest(id, base = null, prompt = prompt, attachments = atts, source = source, capabilities = capabilities))
        }
        return id
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

    /** Conversations reserved by [start] while their attachments are prepared, so a second start is refused. */
    private val claimed = HashSet<String>()

    private fun claim(id: String): Boolean = synchronized(lock) { if (executions.containsKey(id) || id in claimed) false else claimed.add(id) }

    private fun release(id: String) = synchronized(lock) { claimed.remove(id) }

    /**
     * Character chats: produces another reply for the character message at stored [answerIndex] (no tools, one
     * model call). The reply is kept as a switchable candidate; later turns stay as they are.
     */
    fun regenerateCharacterReply(conversation: Conversation, answerIndex: Int): String? {
        if (isRunning(conversation.id)) return null
        val answer = conversation.messages.getOrNull(answerIndex) ?: return null
        if (answer.message.role != Role.Assistant || answer.message.toolCalls.isNotEmpty()) return null
        val base = conversation.copy(messages = conversation.messages.take(answerIndex))
        return launchTurn(
            TurnRequest(conversation.id, base, prompt = null, attachments = emptyList(), source = RunSource.Chat, regenerate = Regenerate(conversation, answerIndex)),
        )
    }

    /** Switches the character message at [answerIndex] to candidate [index] and saves it. */
    suspend fun selectCandidate(conversationId: String, answerIndex: Int, index: Int): Boolean {
        if (isRunning(conversationId)) return false
        val c = graph.conversations.load(conversationId) ?: return false
        val m = c.messages.getOrNull(answerIndex) ?: return false
        val set = runCatching { CharacterSession.selectCandidate(conversationId, m.createdAt.toString(), index) }.getOrNull() ?: return false
        graph.conversations.update(conversationId) { conv ->
            conv.copy(messages = conv.messages.mapIndexed { i, s -> if (i == answerIndex) s.copy(message = ChatMessage.assistant(set.current)) else s })
        }
        return true
    }

    /** Character chats: changes a stored message's text in place, keeping every later turn. */
    suspend fun editInPlace(conversationId: String, index: Int, text: String): Boolean {
        if (isRunning(conversationId)) return false
        val clean = text.trim()
        if (clean.isEmpty()) return false
        var key: String? = null
        var assistant = false
        val saved = graph.conversations.update(conversationId) { conv ->
            val m = conv.messages.getOrNull(index) ?: return@update conv
            key = m.createdAt.toString()
            assistant = m.message.role == Role.Assistant
            val msg = if (assistant) ChatMessage.assistant(clean) else ChatMessage.user(clean)
            conv.copy(messages = conv.messages.mapIndexed { i, s -> if (i == index) s.copy(message = msg) else s }, updatedAt = System.currentTimeMillis())
        } ?: return false
        if (assistant) key?.let { runCatching { CharacterSession.replaceCandidate(saved.id, it, clean) } }
        return true
    }

    private fun takeQueuedAttachments(conversationId: String, m: QueuedMessage): List<Attachment> =
        queuedAttachments.remove("$conversationId#${m.id}") ?: m.attachments.mapNotNull { Attachments.fromPath(it) }

    /** The full conversation and the character reply being regenerated. */
    private class Regenerate(val original: Conversation, val answerIndex: Int)

    private class TurnRequest(
        val conversationId: String,
        val base: Conversation?,
        val prompt: String?,
        val attachments: List<Attachment>,
        val source: RunSource,
        val regenerate: Regenerate? = null,
        /** Tool families this run may use; null = all tools, empty = none. */
        val capabilities: Set<atom.automation.Capability>? = null,
    )

    private fun launchTurn(request: TurnRequest): String? {
        val now = System.currentTimeMillis()
        val execution = synchronized(lock) {
            if (executions[request.conversationId] != null || request.conversationId in claimed) return null
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
                regenerating = request.regenerate != null,
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

    /** The conversation run [runId] (a [ToolContext.taskId]) belongs to, e.g. for WebHost.conversationOf. */
    fun conversationOf(runId: String): String? = byRunId[runId]?.conversationId

    /** The model run [runId] actually uses. */
    fun selectionOf(runId: String): EngineSelection? = byRunId[runId]?.selectionInUse

    /** False for a capability-limited run without the web: its subagents get no provider-hosted search either. */
    fun hostedSearchOf(runId: String): Boolean = byRunId[runId]?.hostedSearchInUse ?: true

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
            val known = engine.contextWindow ?: runCatching { graph.modelInfo.contextWindow(engine.selection ?: selectionFor(conversation).first) }.getOrNull()
            val window = minOf(known ?: config.context.budgetTokens, config.context.budgetTokens)
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
                graph.runPower.update(now, graph.settings.current().keepScreenOn)
            }
        }
    }

    private val holdsMutex = Mutex()

    init {
        // "Keep screen on while running" applies at once, also to a run already going.
        scope.launch { graph.settings.settings.map { it.keepScreenOn }.distinctUntilChanged().collect { refreshHolds() } }
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

    /** The model role a run from [source] uses when its conversation has no model of its own; null = primary. */
    private fun roleFor(source: RunSource): ModelRole? = when (source) {
        RunSource.Chat -> null
        RunSource.Bubble, RunSource.Assistant, RunSource.Voice -> ModelRole.Assistant
        RunSource.Automation, RunSource.Schedule -> ModelRole.Scheduled
    }

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
        val conversationId: String get() = id
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

        val hostedSearchInUse: Boolean get() = CapabilityTools.allowsHostedSearch(request.capabilities)

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
            var character: CharacterRun? = null
            var subagentWatch: Job? = null
            try {
                prepare()
                refreshHolds()
                val settings = graph.settings.current()
                val (saved, fallbackNotice) = selectionFor(conversation)
                // Runs from the bubble, the assistant, voice or schedules use their role's model unless the
                // conversation has its own.
                val role = roleFor(request.source)?.takeIf { conversation.selection == null }
                val roleSelection = role?.let { r -> runCatching { graph.modelRoles.resolve(r, null) }.getOrNull() }
                val selection = roleSelection ?: saved
                // Capability-limited runs without the web get no provider-hosted search either.
                val hostedSearch = CapabilityTools.allowsHostedSearch(request.capabilities)
                val engine = graph.router.acquire(selection, hostedSearch = hostedSearch).also { routed = it }
                selectionInUse = engine.selection ?: selection
                val window = engine.contextWindow ?: runCatching { graph.modelInfo.contextWindow(selectionInUse) }.getOrNull()
                windowInUse = window
                support = ReasoningSupport.of(engine.reasoning.supported, engine.reasoning.levels)
                val conversationEffort = conversation.reasoningEffort ?: settings.defaultReasoning
                val wantedEffort = if (role != null && roleSelection != null) {
                    runCatching { graph.modelRoles.resolveReasoning(role, roleSelection, conversationEffort) }.getOrDefault(conversationEffort)
                } else {
                    conversationEffort
                }
                val reasoningSetting = support.request(
                    enabled = conversation.reasoningEnabled ?: settings.reasoningByDefault,
                    effort = engine.snapReasoning(wantedEffort),
                )

                val images = loadTurnImages()
                val history = ModelHistory.build(stored, images)
                view.clear()
                view.addAll(history.storedIndex)

                // Character chats: the character block is built (and the chat marked active, which enables the
                // story memory tool) before the tools are gathered.
                character = runCatching {
                    CharacterSession.prepareRun(id, history.messages, window, regenerate = request.regenerate != null)
                }.getOrNull()
                character?.let { c -> updateRun(id) { copy(replyingLabel = c.replyingLabel) } }

                val shell = graph.shell()
                val gathered = buildTools(settings.confirmActTools, shell)
                val tools = character?.filterTools(gathered) ?: gathered
                this.tools = tools
                val registry = ToolRegistry(tools)
                val basePrompt = SystemPrompt.build(shell.tier, engine.onDevice) +
                    Features.all.mapNotNull { runCatching { it.promptHint(app) }.getOrNull() }.joinToString("") { "\n\n" + it }
                val systemPrompt = character?.let { basePrompt + "\n\n" + it.prompt.systemWithPostHistory() } ?: basePrompt
                val autoCompact = settings.autoCompaction
                val config = engine.agentConfig(systemPrompt = systemPrompt, tools = registry.specs, slowApprovals = settings.confirmActTools)
                    .let { c -> if (request.regenerate != null) c.copy(maxSteps = 1) else c }
                    .copy(reasoning = reasoningSetting, contextWindow = window, autoCompact = autoCompact)
                val notice = listOfNotNull(
                    fallbackNotice?.takeIf { roleSelection == null },
                    engine.notice,
                    if (window == null && autoCompact) app.getString(atom.app.R.string.run_window_unknown) else null,
                ).joinToString("\n").ifEmpty { null }
                if (fallbackNotice != null && roleSelection == null && request.regenerate == null) commit(StoredMessage.notice(fallbackNotice), modelVisible = false)
                contextWindow = config.usableWindow
                updateRun(id) { copy(phase = if (paused.value) RunPhase.Paused else RunPhase.Running, engineLabel = engine.label, onDevice = engine.onDevice, notice = notice) }

                // Subagents report progress through SubagentRuns; it keeps the wake lock and screen timers alive
                // during long subagent calls. Event-driven and scoped to this run.
                subagentWatch = scope.launch {
                    SubagentRuns.runs
                        .map { list -> list.filter { it.parentTaskId == runId }.sumOf { it.steps.size + it.text.length / 200 + it.status.ordinal } }
                        .distinctUntilChanged()
                        .drop(1)
                        .collect { graph.runPower.progress() }
                }

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
                subagentWatch?.cancel()
                if (character != null) CharacterSession.endRun(id)
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
            // A regeneration works on a truncated copy; the saved conversation stays whole until it finishes.
            if (request.regenerate != null) return
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
            val origin = java.util.IdentityHashMap<Tool, ToolOrigin>()
            val gathered = DeviceTools.create(app, shell).onEach { origin[it] = ToolOrigin.Device } +
                LinuxTools.create(app).onEach { origin[it] = ToolOrigin.Linux } +
                Features.all.flatMap { f ->
                    val o = originOf(f.id)
                    runCatching { f.tools(app) }.getOrDefault(emptyList()).onEach { origin[it] = o }
                }
            val caps = request.capabilities
            val base = if (caps == null) gathered else CapabilityTools.filter(gathered, caps) { t -> familyOf(t, origin[t] ?: ToolOrigin.Other) }
            val wrapped = base.map { if (confirmAct && it.risk == Risk.Act && it !is ConfirmsItself) ConfirmingTool(it) else it }.map(LongTools::apply)
            val runScoped = listOf(HistorySearch.tool { ModelHistory.searchable(stored.toList()) }, releaseTool())
                .let { list -> if (caps == null) list else CapabilityTools.filter(list, caps) { familyOf(it, ToolOrigin.Other) } }
            return (runScoped + wrapped).distinctBy { it.spec.name }.onEach { riskOf[it.spec.name] = it.risk }
        }

        private fun originOf(featureId: String): ToolOrigin = when (featureId) {
            "device", "context", "vdisplay" -> ToolOrigin.Device
            "linux" -> ToolOrigin.Linux
            "web" -> ToolOrigin.Web
            "extend" -> ToolOrigin.Extend
            else -> ToolOrigin.Other
        }

        private fun familyOf(tool: Tool, origin: ToolOrigin) =
            CapabilityTools.classify(tool.spec.name, tool.spec.description, origin, tool.risk, tool.usesPhoneUi)

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
                    StoredMessage(
                        message,
                        reasoning = info.reasoning,
                        reasoningMs = info.reasoningMs,
                        createdAt = now,
                        searchSuggestions = info.searchSuggestions,
                    )
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
            val final = request.regenerate?.let { regenerated(it) } ?: snapshot()
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
            if (outcome == RunOutcome.Completed && request.regenerate == null) titleCandidate = final
            pruneFinished()
        }

        /**
         * The saved conversation after a character-reply regeneration: the original record with the reply switched
         * to the new candidate. Anything else (failure, stop, no text) leaves the original untouched.
         */
        private suspend fun regenerated(r: Regenerate): Conversation {
            val original = r.original.copy(updatedAt = System.currentTimeMillis(), stats = stats)
            val target = original.messages.getOrNull(r.answerIndex) ?: return original
            val reply = stored.drop(turnStartOfRegenerate(r)).lastOrNull { it.message.role == Role.Assistant && it.message.toolCalls.isEmpty() }
                ?.message?.text?.trim()
            if (outcome == RunOutcome.Failed) {
                return original.copy(messages = original.messages + StoredMessage.notice(error ?: app.getString(atom.app.R.string.run_failed)).copy(isError = true))
            }
            if (outcome != RunOutcome.Completed || reply.isNullOrEmpty()) return original
            val set = runCatching { CharacterSession.addCandidate(id, target.createdAt.toString(), target.message.text, reply) }.getOrNull()
            val text = set?.current ?: reply
            return original.copy(
                messages = original.messages.mapIndexed { i, s ->
                    if (i == r.answerIndex) s.copy(message = ChatMessage.assistant(text), reasoning = null, reasoningMs = null) else s
                },
            )
        }

        private fun turnStartOfRegenerate(r: Regenerate): Int = r.answerIndex.coerceAtMost(stored.size)

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
