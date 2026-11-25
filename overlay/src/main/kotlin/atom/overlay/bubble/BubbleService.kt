package atom.overlay.bubble

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Started (never foreground) while the bubble is switched on. It holds no work; being sticky lets the system
 * restart the process after a low-memory kill, and the app's start brings the bubble back.
 */
class BubbleService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Bubble.sync()
        if (!Bubble.wantsService) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
