package atom.automation.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import atom.automation.AutomationState
import atom.automation.Automations
import atom.automation.RunLauncher
import atom.automation.Snapshot
import atom.automation.ToolInvoker
import atom.automation.rule.ConflictDetector
import atom.automation.rule.DeviceState
import atom.automation.rule.Rule
import atom.automation.rule.RuleEngine
import atom.automation.rule.RuleJson
import atom.automation.rule.RulePorts
import atom.automation.rule.Trigger
import atom.automation.rule.WaitFor
import atom.automation.task.ScheduledTask
import atom.automation.task.TaskEngine
import atom.automation.task.TaskNotices
import atom.automation.task.TaskPorts
import atom.automation.task.TaskRun
import atom.ui.feature.Atom
import atom.ui.feature.HostRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/** The live engine for this process: state on disk, both engines, the one alarm and the system listeners. */
internal class AutomationRuntime private constructor(context: Context) {
    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(this.context.filesDir, "atom4_automation/state.json")
    private val writes = Channel<Snapshot>(Channel.CONFLATED)
    val state: AutomationState = AutomationState(load()) { writes.trySend(it) }
    val notifier = Notifier(this.context)
    private val alarms = AlarmScheduler(this.context)
    private val started = AtomicBoolean(false)
    private val watching = AtomicBoolean(false)

    val tasks = TaskEngine(state, { Automations.launcher }, TaskBridge(), scope)
    val rules = RuleEngine(state, RuleBridge(), scope)

    init {
        scope.launch(Dispatchers.IO) {
            for (snapshot in writes) save(snapshot)
        }
    }

    /** Process start: recover, re-plan, re-arm, re-register. Cheap; safe to call repeatedly. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        tasks.recoverInterrupted()
        tasks.replan()
        syncListeners()
        watchRuns()
    }

    fun onWake() {
        start()
        tasks.onWake()
        rules.onWake()
        rearm()
    }

    /** Boot, update, clock or zone change. */
    fun onSystemChange() {
        start()
        tasks.replan()
        syncListeners()
        rearm()
    }

    fun rearm() {
        val now = Instant.now()
        val next = listOfNotNull(tasks.earliestWakeup(), rules.earliestWakeup(now)).minOrNull()
        alarms.arm(next?.let { maxOf(it, now.toEpochMilli() + 500) })
    }

    fun conflicts() = ConflictDetector.find(state.value.rules, ZoneId.systemDefault())

    fun syncListeners() {
        val enabled = state.value.rules.filter { it.enabled }
        TriggerService.sync(context, enabled)
        setComponent(BluetoothReceiver::class.java, enabled.any { it.trigger is Trigger.BluetoothConnected || it.trigger is Trigger.BluetoothDisconnected })
        Places.sync(context, enabled.filter { it.trigger is Trigger.Location })
        runCatching { Automations.onListenersChanged() }
    }

    private fun setComponent(cls: Class<*>, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val name = ComponentName(context, cls)
        if (context.packageManager.getComponentEnabledSetting(name) == state) return
        runCatching { context.packageManager.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP) }
    }

    fun watchRuns() {
        val host = runCatching { Atom.host }.getOrNull() ?: return
        if (!watching.compareAndSet(false, true)) return
        scope.launch { host.runs.collect { tasks.onRuns(it) } }
    }

    private fun load(): Snapshot = runCatching {
        if (!file.exists()) Snapshot() else RuleJson.json.decodeFromString(Snapshot.serializer(), file.readText())
    }.getOrElse {
        runCatching { file.copyTo(File(file.parentFile, "state.broken.json"), overwrite = true) }
        Snapshot()
    }

    private fun save(s: Snapshot) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "state.json.tmp")
            tmp.writeText(RuleJson.json.encodeToString(Snapshot.serializer(), s))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private inner class TaskBridge : TaskPorts {
        override fun now(): Instant = Instant.now()
        override fun zone(): ZoneId = ZoneId.systemDefault()
        override fun isLocked() = PhoneState.locked(context)
        override fun backgroundDisplay() = runCatching { Automations.backgroundDisplay.available() }.getOrDefault(false)
        override fun currentRuns(): List<HostRun> {
            watchRuns()
            return runCatching { Atom.host.runs.value }.getOrDefault(emptyList())
        }
        override fun stop(conversationId: String) {
            runCatching { Atom.host.stop(conversationId) }
        }
        override fun notifyResult(task: ScheduledTask, run: TaskRun, text: String) =
            notifier.result("task:${task.id}", task.name, TaskNotices.resultBody(text), run.conversationId)
        override fun notifyAlert(task: ScheduledTask, message: String) =
            notifier.alert("task:${task.id}", TaskNotices.failedTitle(task.name), TaskNotices.failedBody(message))
        override fun notifyPaused(task: ScheduledTask, message: String) =
            notifier.alert("task:${task.id}", TaskNotices.pausedTitle(task.name), message)
        override fun notifyReadyToRun(task: ScheduledTask) = notifier.readyToRun(task.id, task.name)
        override fun wakeupsChanged() = rearm()
    }

    private inner class RuleBridge : RulePorts {
        override fun device(): DeviceState = AndroidDeviceState(context)
        override fun tools(): ToolInvoker? = Automations.toolInvoker
        override fun launcher(): RunLauncher = Automations.launcher
        override suspend fun awaitRun(conversationId: String, timeoutMs: Long): HostRun? {
            val host = runCatching { Atom.host }.getOrNull() ?: return null
            return withTimeoutOrNull(timeoutMs) {
                host.runs.first { list -> list.any { it.conversationId == conversationId && !it.phase.active } }
                    .first { it.conversationId == conversationId }
            }
        }
        override fun defer(rule: Rule, actionIndex: Int, vars: Map<String, String>, waitFor: WaitFor) =
            DeferredActionWorker.enqueue(context, rule.id, actionIndex, vars, waitFor)
        override fun notify(rule: Rule, title: String, text: String) =
            notifier.result("rule:${rule.id}:$title", title, text, null)
        override fun wakeupsChanged() = rearm()
        override fun listenersChanged() = syncListeners()
    }

    companion object {
        @Volatile
        private var instance: AutomationRuntime? = null

        fun get(context: Context): AutomationRuntime =
            instance ?: synchronized(this) { instance ?: AutomationRuntime(context).also { instance = it } }
    }
}
