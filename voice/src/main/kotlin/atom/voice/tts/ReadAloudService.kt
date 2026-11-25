package atom.voice.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import atom.voice.R

/**
 * Media-playback foreground service that exists only while a chat answer is being read aloud, so it keeps
 * playing in the background and on the lock screen. Shows "Atom is reading aloud" with a Stop button and goes
 * away the moment speaking ends.
 */
class ReadAloudService : Service() {
    internal companion object {
        private const val CHANNEL_ID = "atom_read_aloud"
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
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.speech_reading_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, ReadAloudService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.speech_reading_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), getString(R.string.speech_stop), stop).build())
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0))
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }
}
