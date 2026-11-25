package atom.vdisplay.display

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Frames of the hidden display, straight from its ImageReader (no screen capture, no extra permission).
 *
 * Battery: the compositor only produces a frame when the hidden app redraws. Without viewers the newest frame is
 * kept as a GPU buffer (for screenshots) and older ones are returned at once. With viewers, frames are published to
 * [preview] as zero-copy hardware bitmaps, capped by [FrameGate]; Compose scales them on the GPU.
 *
 * All image handling happens on [handler]'s thread.
 */
internal class FrameStore(val handler: Handler) : ImageReader.OnImageAvailableListener {
    private var latest: Image? = null
    private var shown: Image? = null
    private var viewers = 0
    private var gate = FrameGate(PREVIEW_FPS)
    private val showLatest = Runnable { publish(SystemClock.uptimeMillis(), scheduled = true) }

    private val _preview = MutableStateFlow<Bitmap?>(null)

    /** Live preview frames while at least one viewer is attached; null otherwise. */
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    @Volatile
    var frames = 0L
        private set

    override fun onImageAvailable(reader: ImageReader) {
        val next = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null // all buffers held; the next callback catches up
        } ?: return
        frames++
        val old = latest
        latest = next
        if (old != null && old !== shown) old.close()
        if (viewers == 0) return
        when (val d = gate.offer(SystemClock.uptimeMillis())) {
            FrameGate.Decision.ShowNow -> publish(SystemClock.uptimeMillis(), scheduled = false)
            is FrameGate.Decision.ShowAt -> handler.postAtTime(showLatest, d.time)
            FrameGate.Decision.AlreadyScheduled -> Unit
        }
    }

    /** A viewfinder appeared ([fps] ≤ 10) or disappeared. Call on [handler]'s thread. */
    fun addViewer(fps: Int) {
        viewers++
        gate = FrameGate(fps.coerceIn(1, PREVIEW_FPS))
        publish(SystemClock.uptimeMillis(), scheduled = false)
    }

    fun removeViewer() {
        viewers = (viewers - 1).coerceAtLeast(0)
        if (viewers > 0) return
        handler.removeCallbacks(showLatest)
        gate.reset()
        _preview.value = null
        shown?.let { if (it !== latest) it.close() }
        shown = null
    }

    private fun publish(now: Long, scheduled: Boolean) {
        if (scheduled) gate.shown(now)
        val img = latest ?: return
        if (img === shown) return
        val bmp = wrap(img) ?: return
        val previous = shown
        shown = img
        _preview.value = bmp
        if (previous != null && previous !== latest) previous.close()
    }

    /** A software copy of the newest frame for the agent, or null if nothing was drawn yet. */
    fun snapshot(): Bitmap? {
        val img = latest ?: return null
        val hw = wrap(img) ?: return null
        return try {
            hw.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hw.recycle()
        }
    }

    private fun wrap(img: Image): Bitmap? = try {
        val buffer = img.hardwareBuffer ?: return null
        try {
            Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
        } finally {
            buffer.close() // the bitmap holds its own reference
        }
    } catch (t: Throwable) {
        null
    }

    fun close() {
        handler.removeCallbacks(showLatest)
        _preview.value = null
        shown?.close()
        if (latest !== shown) latest?.close()
        shown = null
        latest = null
        viewers = 0
    }

    companion object {
        const val PREVIEW_FPS = 10
        /** Newest + shown + one being acquired + one for the producer. */
        const val MAX_IMAGES = 4
    }
}
