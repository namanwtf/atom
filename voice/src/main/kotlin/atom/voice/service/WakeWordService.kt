package atom.voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import atom.voice.R
import atom.voice.VoiceCommand
import atom.voice.Wake
import atom.voice.WakeState
import atom.voice.assist.AssistLaunch
import atom.voice.audio.TriggerDebouncer
import atom.voice.engine.WakeListener
import atom.voice.policy.ListenDecision
import atom.voice.policy.ListeningPolicy

/**
 * Opt-in "Hey Atom" foreground service (type microphone).
 *
 * The service stays in the foreground while enabled, even when paused: Android grants microphone access to a
 * background app only through a microphone FGS that was started while the app was visible, so stopping it on
 * pause would make resuming impossible. While paused it holds no AudioRecord, no thread, no interpreter and no
 * wake lock; it only has system callbacks registered.
 */
class WakeWordService : Service() {

    internal companion object {
        const val ACTION_STOP = "atom.voice.action.STOP_WAKE_WORD"
        private const val CHANNEL_ID = "atom_wake_word"
        private const val NOTIFICATION_ID = 0x41704d // "ApM"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val debouncer = TriggerDebouncer()
    private lateinit var monitor: DeviceMonitor
    private lateinit var capture: CommandCapture
    private var listener: WakeListener? = null
    private var inForeground = false
    private var shownState: WakeState? = null

    private val listenerCallback = object : WakeListener.Callback {
        override fun onWakeWord() = onTriggered()
        override fun onMicUnavailable(reason: String) {
            listener = null
            setState(WakeState.Paused(reason)) // retried on the next device event (e.g. recording callback)
        }
        override fun onFatal(reason: String) {
            listener = null
            fail(WakeState.Unavailable(reason))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Wake.service = this
        monitor = DeviceMonitor(this, handler) { evaluate() }
        capture = CommandCapture(this, handler)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            Wake.publish(WakeState.Off)
            return START_NOT_STICKY
        }
        if (!inForeground) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification(WakeState.Listening), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                inForeground = true
            } catch (e: RuntimeException) { // SecurityException / ForegroundServiceStartNotAllowedException
                fail(WakeState.Unavailable(Wake.REASON_BACKGROUND_START))
                return START_NOT_STICKY
            }
            monitor.start()
        }
        evaluate()
        // Not sticky: a system restart would come from the background, where a microphone FGS may not start.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopListening()
        capture.cancel()
        if (::monitor.isInitialized) monitor.stop()
        if (Wake.service === this) Wake.service = null
        if (Wake.state.value !is WakeState.Unavailable) Wake.publish(WakeState.Off)
        super.onDestroy()
    }

    internal fun onPolicyChanged() {
        debouncer.threshold = Wake.policy.threshold
        evaluate()
    }

    internal fun shutdown() {
        stopListening()
        capture.cancel()
        monitor.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    private fun fail(state: WakeState.Unavailable) {
        Wake.publish(state)
        shutdown()
    }

    /** The single decision point; runs on every relevant system event, never on a timer. */
    private fun evaluate() {
        if (!inForeground || capture.isActive) return
        debouncer.threshold = Wake.policy.threshold
        val conditions = monitor.conditions(ownSessionId = listener?.audioSessionId ?: 0, ignoreMic = false)
        when (val decision = ListeningPolicy.decide(conditions, Wake.policy)) {
            ListenDecision.Listen -> {
                if (listener == null) {
                    listener = WakeListener(applicationContext, debouncer, handler, listenerCallback).also { it.start() }
                }
                setState(WakeState.Listening)
            }
            is ListenDecision.Pause -> {
                stopListening()
                setState(WakeState.Paused(decision.reason))
            }
        }
    }

    private fun stopListening() {
        listener?.stop()
        listener = null
    }

    private fun onTriggered() {
        listener = null // the listener already released the mic and the models before posting this
        if (!inForeground) return
        haptic()
        capture.start { text ->
            if (text != null) Wake.emit(VoiceCommand(text, fromWakeWord = true))
            evaluate()
        }
    }

    private fun haptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        runCatching { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
    }

    private fun setState(state: WakeState) {
        Wake.publish(state)
        if (state != shownState && inForeground) {
            shownState = state
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.atom_voice_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.atom_voice_channel_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(state: WakeState): Notification {
        val text = when (state) {
            is WakeState.Paused -> getString(R.string.atom_voice_paused, state.reason)
            else -> getString(R.string.atom_voice_listening)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.atom_voice_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.atom_voice_turn_off), stop,
                ).build(),
            )
        AssistLaunch.intent(this, fromAssist = false)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }
}
