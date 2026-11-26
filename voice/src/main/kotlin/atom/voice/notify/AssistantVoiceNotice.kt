package atom.voice.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import atom.voice.R
import atom.voice.tts.ReadAloud

/**
 * The "Assistant voice" notification: "Atom is listening" while the microphone is open for the assistant or the
 * floating bubble (outside Atom's own screens), "Atom is reading aloud" while an answer is spoken there. Both carry
 * a Stop button. Chat answers read aloud in the background use the same channel through the media service.
 */
object AssistantVoiceNotice {
    const val CHANNEL_ID = "atom_assistant_voice"

    /** The channel used before "Assistant voice" existed; removed so the user does not see two. */
    private const val OLD_READ_ALOUD_CHANNEL = "atom_read_aloud"

    private const val ID_LISTENING = 0x41564c // "AVL"
    private const val ID_READING = 0x415652 // "AVR"
    internal const val ACTION_STOP = "atom.voice.action.STOP_ASSISTANT_VOICE"
    internal const val EXTRA_MODE = "mode"

    enum class Mode { Listening, Reading }

    @Volatile
    private var stopListening: (() -> Unit)? = null

    /** Identifies the listening session the notice belongs to, so an older session cannot take down a newer one's. */
    @Volatile
    private var listeningToken = 0L

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { if (nm.getNotificationChannel(OLD_READ_ALOUD_CHANNEL) != null) nm.deleteNotificationChannel(OLD_READ_ALOUD_CHANNEL) }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.assistant_voice_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.assistant_voice_channel_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    /**
     * Shows "Atom is listening"; [onStop] runs when the user taps Stop (on the main thread). Returns the token to
     * pass to [hideListening] when that session ends.
     */
    fun showListening(context: Context, onStop: () -> Unit): Long {
        val token = synchronized(this) { ++listeningToken }
        stopListening = onStop
        post(context, Mode.Listening)
        return token
    }

    /** Hides "Atom is listening" if it still belongs to the session that got [token]. */
    fun hideListening(context: Context, token: Long) {
        if (token == 0L || synchronized(this) { token != listeningToken }) return
        hide(context, Mode.Listening)
    }

    fun showReading(context: Context) = post(context, Mode.Reading)

    fun hide(context: Context, mode: Mode) {
        if (mode == Mode.Listening) stopListening = null
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(idOf(mode)) }
    }

    internal fun onStop(context: Context, mode: Mode) {
        when (mode) {
            Mode.Listening -> stopListening?.invoke()
            Mode.Reading -> ReadAloud.stop()
        }
        hide(context, mode)
    }

    /** Builds the notification (also used by the background read-aloud service with its own Stop intent). */
    fun build(context: Context, mode: Mode, stop: PendingIntent = stopIntent(context, mode)): Notification {
        ensureChannel(context)
        val title = context.getString(if (mode == Mode.Listening) R.string.assistant_voice_listening else R.string.speech_reading_title)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_media_pause),
            context.getString(R.string.speech_stop),
            stop,
        ).build()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(atom.ui.R.drawable.ic_stat_atom)
            .setColor(android.graphics.Color.BLACK)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(if (mode == Mode.Listening) Notification.CATEGORY_STATUS else Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(stopAction)
        if (mode == Mode.Listening) builder.setContentText(context.getString(R.string.assistant_voice_listening_text))
        if (mode == Mode.Reading) builder.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0))
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }

    private fun post(context: Context, mode: Mode) {
        val app = context.applicationContext
        // Without the notification permission nothing is shown; the voice session itself is unaffected.
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(idOf(mode), build(app, mode)) }
    }

    private fun stopIntent(context: Context, mode: Mode): PendingIntent = PendingIntent.getBroadcast(
        context,
        idOf(mode),
        Intent(context, AssistantVoiceStopReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_MODE, mode.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun idOf(mode: Mode) = if (mode == Mode.Listening) ID_LISTENING else ID_READING
}

/** The Stop button of the "Assistant voice" notification. */
class AssistantVoiceStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AssistantVoiceNotice.ACTION_STOP) return
        val mode = AssistantVoiceNotice.Mode.entries.firstOrNull { it.name == intent.getStringExtra(AssistantVoiceNotice.EXTRA_MODE) } ?: return
        AssistantVoiceNotice.onStop(context.applicationContext, mode)
    }
}
