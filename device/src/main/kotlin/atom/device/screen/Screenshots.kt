package atom.device.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import android.view.Display
import atom.core.llm.ContentPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-demand JPEG screenshots, downscaled by default. Only taken when a tool explicitly asks: images are costly.
 * With full resolution the frame keeps the screen's own size (and a higher JPEG quality), so image pixels are
 * screen pixels.
 */
internal object Screenshots {
    private const val MAX_SIDE = 1080
    private const val QUALITY = 60
    private const val FULL_QUALITY = 85

    /** Longest side and JPEG quality for a capture. */
    fun encoding(fullResolution: Boolean): Pair<Int, Int> = if (fullResolution) Int.MAX_VALUE to FULL_QUALITY else MAX_SIDE to QUALITY

    class Shot(val image: ContentPart.Image, val note: String)

    private sealed interface Raw {
        class Ok(val result: AccessibilityService.ScreenshotResult) : Raw
        class Failed(val code: Int) : Raw
    }

    suspend fun capture(svc: AccessibilityService, fullResolution: Boolean = false): Shot {
        var raw = take(svc)
        if (raw is Raw.Failed && raw.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            delay(1_050) // the system rate-limits screenshots per service
            raw = take(svc)
        }
        val ok = when (raw) {
            is Raw.Ok -> raw.result
            is Raw.Failed -> throw UiError("Screenshot failed: ${reason(raw.code)}")
        }
        return withContext(Dispatchers.Default) { encode(ok, fullResolution) }
    }

    private suspend fun take(svc: AccessibilityService): Raw = suspendCancellableCoroutine { cont ->
        svc.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            svc.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    if (cont.isActive) cont.resume(Raw.Ok(result)) else result.hardwareBuffer.close()
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(Raw.Failed(errorCode))
                }
            },
        )
    }

    private fun encode(result: AccessibilityService.ScreenshotResult, fullResolution: Boolean): Shot {
        val buffer = result.hardwareBuffer
        val software = try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: throw UiError("Screenshot failed: the frame could not be decoded.")
            try {
                hw.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hw.recycle()
            }
        } finally {
            buffer.close()
        }
        return encodeBitmap(software, fullResolution)
    }

    /** Downscales (unless [fullResolution]) and JPEG-encodes [software]; recycles it. */
    fun encodeBitmap(software: Bitmap, fullResolution: Boolean = false): Shot {
        val (maxSide, quality) = encoding(fullResolution)
        val w = software.width
        val h = software.height
        val scale = minOf(1f, maxSide.toFloat() / max(w, h))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(software, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
                .also { software.recycle() }
        } else {
            software
        }
        val bytes = ByteArrayOutputStream(64 * 1024).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        val note = buildString {
            append("Screenshot ${scaled.width}x${scaled.height} attached")
            if (fullResolution) append(" at the screen's original resolution")
            if (scale < 1f) {
                append(" (scaled ${String.format(java.util.Locale.US, "%.3f", scale)} of the ${w}x$h screen; divide image pixels by that to get tap x,y)")
            }
            append(". Protected screens (banking, DRM) appear black.")
        }
        scaled.recycle()
        return Shot(ContentPart.Image("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP)), note)
    }

    /** One frame for "Hey ATOM": a small JPEG, or none; [secure] when the app in front protects its window. */
    class Glimpse(val jpeg: ByteArray?, val secure: Boolean)

    /**
     * One screenshot, no retry. A secure window fails with its own error from Android 14; before that it comes
     * back black, which counts as secure too.
     */
    suspend fun glimpse(svc: AccessibilityService, maxSide: Int, quality: Int): Glimpse {
        val ok = when (val raw = take(svc)) {
            is Raw.Ok -> raw.result
            is Raw.Failed -> return Glimpse(null, secure = raw.code == SECURE_WINDOW)
        }
        return withContext(Dispatchers.Default) {
            val software = runCatching { softwareCopy(ok) }.getOrNull() ?: return@withContext Glimpse(null, secure = false)
            if (BlankFrame.isBlank(software)) {
                software.recycle()
                return@withContext Glimpse(null, secure = true)
            }
            val w = software.width
            val h = software.height
            val scale = minOf(1f, maxSide.toFloat() / max(w, h))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(software, (w * scale).roundToInt(), (h * scale).roundToInt(), true).also { software.recycle() }
            } else {
                software
            }
            val bytes = ByteArrayOutputStream(64 * 1024).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            scaled.recycle()
            Glimpse(bytes, secure = false)
        }
    }

    private fun softwareCopy(result: AccessibilityService.ScreenshotResult): Bitmap {
        val buffer = result.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: throw UiError("Screenshot failed: the frame could not be decoded.")
            try {
                return hw.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hw.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    /** AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34). */
    private const val SECURE_WINDOW = 6

    private fun reason(code: Int) = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "the service lacks screenshot access."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "requested too quickly; try again in a second."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display."
        6 /* ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34) */ -> "the screen is protected (secure window)."
        else -> "internal error ($code). Rely on the text tree instead."
    }
}

/** A frame that is black everywhere below the status bar: what a protected (FLAG_SECURE) window gives before Android 14. */
internal object BlankFrame {
    private const val GRID = 24
    private const val DARK = 10

    fun isBlank(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true
        val samples = IntArray(GRID * GRID)
        var k = 0
        for (row in 0 until GRID) {
            val y = (h * (0.1f + 0.8f * row / (GRID - 1))).toInt().coerceIn(0, h - 1)
            for (col in 0 until GRID) {
                val x = (w * (0.05f + 0.9f * col / (GRID - 1))).toInt().coerceIn(0, w - 1)
                samples[k++] = bitmap.getPixel(x, y)
            }
        }
        return allDark(samples)
    }

    /** True when every ARGB sample is (almost) black. */
    fun allDark(samples: IntArray): Boolean = samples.all { c ->
        val r = (c shr 16) and 0xff
        val g = (c shr 8) and 0xff
        val b = c and 0xff
        maxOf(r, g, b) <= DARK
    }
}
