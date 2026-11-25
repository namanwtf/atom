package atom.voice.tts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import atom.voice.notify.AssistantVoiceNotice

/**
 * Media-playback foreground service that exists only while a chat answer is being read aloud, so it keeps
 * playing in the background and on the lock screen. Shows "Atom is reading aloud" ("Assistant voice" channel) with a Stop button and goes
 * away the moment speaking ends.
 */
class ReadAloudService : Service() {
    internal companion object {
        private const val NOTIFICATION_ID = 0x41524c // "ARL"
        private const val ACTION_STOP = "atom.voice.action.STOP_READ_ALOUD"

        @Volatile private var instance: ReadAloudService? = null
        @Volatile private var wanted = false

        fun start(context: Context) {
            wanted = true
            if (instance != null) return
            runCatching { context.startForegroundService(Intent(context, ReadAloudService::class.java)) }
            // Starting from the background can be refused (Android 12+); playback continues while the app lives.
        }

        fun stop() {
            wanted = false
            instance?.finish()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReadAloud.onStopPressed()
            finish()
            return START_NOT_STICKY
        }
        instance = this
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: RuntimeException) {
            finish()
            return START_NOT_STICKY
        }
        if (!wanted) finish() // speaking already ended before the service came up
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun finish() {
        if (instance === this) instance = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, ReadAloudService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return AssistantVoiceNotice.build(this, AssistantVoiceNotice.Mode.Reading, stop)
    }
}
