package atom.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import atom.ui.R
import kotlin.math.roundToInt

/**
 * ATOM's own picture for notifications: the silver octopus coin of the launcher icon, as the large icon. Some
 * phones draw a notification with an app icon they cached long ago (an older app on the same package); the large
 * icon is drawn from these pixels, so the notification always shows ATOM. Decoded once per process, at the
 * notification large-icon size, and kept (a few kilobytes).
 */
object NotificationArt {
    @Volatile
    private var cached: Bitmap? = null

    /** Null if the artwork cannot be decoded; callers then simply post without a large icon. */
    fun largeIcon(context: Context): Bitmap? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: decode(context).also { cached = it }
        }
    }

    private fun decode(context: Context): Bitmap? = runCatching {
        val res = context.applicationContext.resources
        val side = res.getDimensionPixelSize(android.R.dimen.notification_large_icon_width).coerceAtLeast(1)
        val full = BitmapFactory.decodeResource(res, R.mipmap.ic_launcher_fg) ?: return null
        // The coin covers the middle EMBLEM_SHARE of the adaptive-icon foreground; the rest is transparent margin.
        val coin = (full.width * EMBLEM_SHARE).roundToInt().coerceIn(1, full.width)
        val left = (full.width - coin) / 2
        val top = (full.height - coin).coerceAtLeast(0) / 2
        val cropped = Bitmap.createBitmap(full, left, top, coin, coin.coerceAtMost(full.height - top))
        val scaled = Bitmap.createScaledBitmap(cropped, side, side, true)
        if (cropped !== full && cropped !== scaled) cropped.recycle()
        if (full !== scaled) full.recycle()
        scaled
    }.getOrNull()

    /** The coin's share of the launcher foreground (242 of 432 px). */
    private const val EMBLEM_SHARE = 242f / 432f
}
