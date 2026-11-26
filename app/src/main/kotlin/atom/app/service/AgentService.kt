package atom.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import atom.app.agent.LiveRun
import atom.app.graph
import atom.ui.feature.RunPhase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground presence for running tasks, and nothing else: it starts with the first run, mirrors what each run is
 * doing into the "Running tasks" notification (Open and Stop per run) and stops itself when the last run ends.
 * The agent loops live in TaskRunner; wake lock and keep-screen-on live in RunPower.
 */
class AgentService : LifecycleService() {
    private var observing = false
    private val children = HashSet<String>()
    private val announced = HashSet<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val graph = applicationContext.graph
        val active = graph.tasks.liveRuns.value.values.filter { it.active }
        // Must reach startForeground promptly even if the runs already ended, or the system kills us.
        val notification = graph.notifications.foreground(active)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, TaskNotifications.ID_TASK, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TaskNotifications.ID_TASK, notification)
        }
        if (!observing) {
            observing = true
            observe()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        val graph = applicationContext.graph
        // Runs that ended before this service instance existed were already announced (or seen in the app).
        announced += graph.tasks.liveRuns.value.values.filter { !it.active }.map { it.runId }
        lifecycleScope.launch {
            // Only re-post when what the notifications show changes, never per streamed token.
            // A screen action waiting for the screen to be unlocked changes the text too.
            graph.tasks.liveRuns
                .combine(atom.ui.feature.ScreenWait.tasks) { runs, waiting -> runs.values.sortedBy { it.startedAt } to waiting }
                .distinctUntilChanged { a, b -> a.second == b.second && a.first.map(Key::of) == b.first.map(Key::of) }
                .map { it.first }
                .collect { runs ->
                    val active = runs.filter { it.active }
                    runs.filter { !it.active && it.runId !in announced }.forEach { finished ->
                        announced += finished.runId
                        if (!graph.uiVisible) graph.notifications.showResult(finished)
                    }
                    val pending = active.firstOrNull { it.pending != null }?.pending
                    // In-app, the dialog asks; the heads-up is for when Atom is driving another app.
                    if (pending == null) graph.notifications.cancelApproval() else if (!graph.uiVisible) graph.notifications.showApproval(pending)
                    if (active.isEmpty()) {
                        stop()
                        return@collect
                    }
                    graph.notifications.notifyForeground(active)
                    graph.notifications.syncChildren(active, children)
                }
        }
    }

    private fun stop() {
        val graph = applicationContext.graph
        graph.notifications.syncChildren(emptyList(), children)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private data class Key(
        val runId: String,
        val active: Boolean,
        val phase: RunPhase,
        val tool: String?,
        val pendingId: Long?,
        val engine: String?,
        val title: String,
        val retrying: Boolean,
        val compacting: Boolean,
        val answering: Boolean,
    ) {
        companion object {
            fun of(run: LiveRun) = Key(
                runId = run.runId,
                active = run.active,
                phase = run.phase,
                tool = run.currentTool,
                pendingId = run.pending?.id,
                engine = run.engineLabel,
                title = run.conversation.title,
                retrying = run.retry != null,
                compacting = run.compacting,
                answering = run.stream.text.isNotEmpty(),
            )
        }
    }

    companion object {
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            } catch (_: IllegalStateException) {
                // Foreground start not allowed right now (e.g. started from the background). The runs still
                // execute; they just lack the ongoing notification.
            } catch (_: SecurityException) {
                // Same, on ROMs that report it differently.
            }
        }
    }
}
