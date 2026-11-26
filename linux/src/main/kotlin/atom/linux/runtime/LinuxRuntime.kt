package atom.linux.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import atom.linux.jobs.JobManager
import atom.linux.term.TerminalSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the process alive while user work is running: terminal sessions, background commands and daemons each
 * hold a claim, and a foreground service exists only while at least one claim does. The last release stops the
 * service; nothing is left running or scheduled when idle.
 */
object LinuxRuntime {
    data class Counts(val sessions: Int = 0, val jobs: Int = 0, val daemons: Int = 0) {
        val total get() = sessions + jobs + daemons
    }

    enum class Claim { Session, Job, Daemon }

    @SuppressLint("StaticFieldLeak") // application context
    @Volatile private var app: Context? = null
    private val claims = HashMap<String, Claim>()
    private val _counts = MutableStateFlow(Counts())
    val counts: StateFlow<Counts> = _counts.asStateFlow()

    fun acquire(context: Context, key: String, claim: Claim) {
        app = context.applicationContext
        val start: Boolean
        synchronized(claims) {
            start = claims.isEmpty()
            claims[key] = claim
            publish()
        }
        if (start) startService(context.applicationContext) else LinuxRuntimeService.refresh()
    }

    fun release(key: String) {
        val stop: Boolean
        synchronized(claims) {
            if (claims.remove(key) == null) return
            stop = claims.isEmpty()
            publish()
        }
        val ctx = app ?: return
        if (stop) ctx.stopService(Intent(ctx, LinuxRuntimeService::class.java)) else LinuxRuntimeService.refresh()
    }

    /** Ends everything (the notification's Stop all). */
    fun stopAll(context: Context) {
        TerminalSessions.closeAll()
        JobManager.get(context).stopAll()
    }

    private fun publish() {
        _counts.value = Counts(
            sessions = claims.values.count { it == Claim.Session },
            jobs = claims.values.count { it == Claim.Job },
            daemons = claims.values.count { it == Claim.Daemon },
        )
    }

    private fun startService(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, LinuxRuntimeService::class.java))
        } catch (e: Exception) {
            // Not allowed from the background right now; the work still runs while the process lives.
        }
    }
}

class LinuxRuntimeService : Service() {
    companion object {
        private const val CHANNEL = "atom_linux_runtime"
        private const val ID = 0x4C1A0100
        @Volatile private var instance: LinuxRuntimeService? = null

        fun refresh() {
            instance?.let { s -> s.getSystemService(NotificationManager::class.java)?.notify(ID, s.build()) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, build())
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (LinuxRuntime.counts.value.total == 0) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun build(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Terminal and background commands", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a terminal session or background command is running"
                    setShowBadge(false)
                },
            )
        }
        val c = LinuxRuntime.counts.value
        val parts = buildList {
            if (c.sessions > 0) add("${c.sessions} terminal session${if (c.sessions == 1) "" else "s"}")
            if (c.jobs > 0) add("${c.jobs} background command${if (c.jobs == 1) "" else "s"}")
            if (c.daemons > 0) add("${c.daemons} daemon${if (c.daemons == 1) "" else "s"}")
        }
        val stop = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, LinuxRuntimeReceiver::class.java).setAction(LinuxRuntimeReceiver.ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(atom.ui.R.drawable.ic_stat_atom)
            .setContentTitle("Terminal running")
            .setContentText(parts.joinToString(" · ").ifEmpty { "Finishing" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop all", stop).build())
            .build()
    }
}

class LinuxRuntimeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_ALL = "atom.linux.STOP_ALL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_ALL) LinuxRuntime.stopAll(context)
    }
}
