package atom.vdisplay.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import atom.vdisplay.route.DisplayInfo
import atom.vdisplay.route.DisplayUnavailable
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The hidden display: an app-owned VirtualDisplay rendering into an ImageReader.
 *
 * Approach (Android 14-17, third-party app with root): the app creates an untrusted virtual display with no special
 * permission (public so accessibility can see it, private as a fallback). An untrusted display only accepts other
 * apps' activities that allow embedding, so the target app is started on it by root (`am start --display`): root
 * holds INTERNAL_SYSTEM_WINDOW, which the activity starter accepts for any display. Being untrusted, the display gets no system decorations and no keyboard; the IME policy is set to "hide"
 * through [RootHelper] so a focused text field never pops the keyboard on the main screen (text goes in as key
 * events or a paste). A trusted/public display would need a root helper that creates the display itself and passes
 * the Surface back over binder; it is not needed for launching, input or frames, so it is not used. Secure windows
 * (FLAG_SECURE) render black here, as on any capture.
 *
 * Untrusted also means no own focus (OWN_FOCUS needs TRUSTED): starting an app here can take input focus from the
 * main screen until the user next touches it.
 *
 * Removal is non-destructive (no DESTROY_CONTENT_ON_REMOVAL): if the user's own app was moved here, closing the
 * display returns its task to the main screen behind whatever is in front, with its back stack intact.
 *
 * Battery: exists only between [create] and [close]; its HandlerThread lives exactly as long.
 */
internal class HiddenDisplay private constructor(
    val info: DisplayInfo,
    private val display: VirtualDisplay,
    private val reader: ImageReader,
    private val thread: HandlerThread,
    val frames: FrameStore,
) {
    val handler: Handler get() = frames.handler

    val preview: StateFlow<Bitmap?> get() = frames.preview

    /** Newest frame as a software bitmap (blocks briefly on the frame thread). */
    fun snapshot(timeoutMs: Long = 1_000): Bitmap? {
        var out: Bitmap? = null
        val done = CountDownLatch(1)
        if (!handler.post { out = frames.snapshot(); done.countDown() }) return null
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    fun addViewer(fps: Int) = handler.post { frames.addViewer(fps) }
    fun removeViewer() = handler.post { frames.removeViewer() }

    fun close() {
        runCatching { display.release() }
        handler.post {
            frames.close()
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    companion object {
        private const val NAME = "Atom background"

        // Public flag values (DisplayManager.VIRTUAL_DISPLAY_FLAG_*).
        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3

        // Hidden flags allowed for apps without extra permission.
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13

        fun create(context: Context, spec: DisplaySpec): HiddenDisplay {
            val thread = HandlerThread("atom-vdisplay").apply { start() }
            val handler = Handler(thread.looper)
            val frames = FrameStore(handler)
            val reader = try {
                ImageReader.newInstance(
                    spec.width, spec.height, PixelFormat.RGBA_8888, FrameStore.MAX_IMAGES,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
                )
            } catch (t: Throwable) {
                thread.quitSafely()
                throw DisplayUnavailable("could not allocate frames for the background display (${t.message})")
            }
            reader.setOnImageAvailableListener(frames, handler)
            val dm = context.getSystemService(DisplayManager::class.java)
            // Public (without PRESENTATION, so media apps never pick it as a second screen): accessibility only
            // tracks public displays. Private is the fallback if the ROM refuses.
            val flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_TOUCH_FEEDBACK_DISABLED
            val vd = try {
                dm.createVirtualDisplay(NAME, spec.width, spec.height, spec.densityDpi, reader.surface, flags, null, handler)
            } catch (t: Throwable) {
                null
            } ?: try {
                // Some ROMs reject the hidden flags; the public one alone is always allowed.
                dm.createVirtualDisplay(NAME, spec.width, spec.height, spec.densityDpi, reader.surface, FLAG_OWN_CONTENT_ONLY)
            } catch (t: Throwable) {
                reader.close()
                thread.quitSafely()
                throw DisplayUnavailable("the system refused to create the background display (${t.message})")
            }
            val id = vd.display?.displayId ?: run {
                vd.release()
                reader.close()
                thread.quitSafely()
                throw DisplayUnavailable("the background display did not come up")
            }
            return HiddenDisplay(DisplayInfo(id, spec.width, spec.height, spec.densityDpi), vd, reader, thread, frames)
        }

        /** The phone's real (logical) size and density, for [DisplaySpec.forPhone]. */
        @Suppress("DEPRECATION")
        fun phoneSpec(context: Context): DisplaySpec {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val m = android.util.DisplayMetrics()
            display.getRealMetrics(m)
            // Portrait regardless of the current rotation: apps are started portrait-first.
            val w = minOf(m.widthPixels, m.heightPixels)
            val h = maxOf(m.widthPixels, m.heightPixels)
            return DisplaySpec.forPhone(w, h, m.densityDpi)
        }
    }
}
