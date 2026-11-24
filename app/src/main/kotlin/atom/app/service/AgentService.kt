package atom.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import atom.app.agent.LiveTask
import atom.app.graph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground presence for a running task, and nothing else: it starts with the task, mirrors the task's
 * step into its notification and stops itself the moment the task ends. The agent loop itself lives in
 * TaskRunner. No wake lock while the screen is on; a timed partial one only while it is off.
 */
class AgentService : LifecycleService() {
    private var observing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> acquireWakeLock()
                Intent.ACTION_SCREEN_ON -> releaseWakeLock()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val graph = applicationContext.graph
        val notification = graph.notifications.progress(graph.tasks.live.value)
        // Must reach startForeground promptly even if the task already ended, or the system kills us.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                TaskNotifications.ID_TASK,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(TaskNotifications.ID_TASK, notification)
        }
        if (!observing) {
            observing = true
            registerScreenReceiver()
            if (getSystemService(PowerManager::class.java)?.isInteractive == false) acquireWakeLock()
            observe()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        val graph = applicationContext.graph
        lifecycleScope.launch {
            // Only re-post when what the notification shows changes, never per streamed token.
            graph.tasks.live
                .map { live -> NotificationKey.of(live) to live }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (key, live) ->
                    if (!key.active) {
                        finishWith(live)
                        return@collect
                    }
                    graph.notifications.notifyProgress(live)
                    val pending = live?.pending
                    // In-app, the dialog asks; the heads-up is for when Atom is driving another app.
                    if (pending == null) {
                        graph.notifications.cancelApproval()
                    } else if (!graph.uiVisible) {
                        graph.notifications.showApproval(pending)
                    }
                }
        }
    }

    private fun finishWith(live: LiveTask?) {
        val graph = applicationContext.graph
        graph.notifications.cancelApproval()
        if (live != null && !graph.uiVisible) graph.notifications.showResult(live)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private fun acquireWakeLock() {
        val held = wakeLock
        if (held?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    private data class NotificationKey(val active: Boolean, val tool: String?, val pendingId: Long?, val engine: String?) {
        companion object {
            fun of(live: LiveTask?) = NotificationKey(
                active = live?.phase?.active == true,
                tool = live?.currentTool,
                pendingId = live?.pending?.id,
                engine = live?.engineLabel,
            )
        }
    }

    companion object {
        private const val WAKE_TAG = "atom:task"

        /** Upper bound per screen-off period; re-acquired on the next screen-off if the task is still going. */
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java))
            } catch (_: IllegalStateException) {
                // Foreground start not allowed right now (e.g. started from the background). The task still
                // runs; it just lacks the ongoing notification.
            }
        }
    }
}
