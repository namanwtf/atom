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

/** On-demand, downscaled JPEG screenshots. Only taken when a tool explicitly asks: images are costly. */
internal object Screenshots {
    private const val MAX_SIDE = 1080
    private const val QUALITY = 60

    class Shot(val image: ContentPart.Image, val note: String)

    private sealed interface Raw {
        class Ok(val result: AccessibilityService.ScreenshotResult) : Raw
        class Failed(val code: Int) : Raw
    }

    suspend fun capture(svc: AccessibilityService): Shot {
        var raw = take(svc)
        if (raw is Raw.Failed && raw.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            delay(1_050) // the system rate-limits screenshots per service
            raw = take(svc)
        }
        val ok = when (raw) {
            is Raw.Ok -> raw.result
            is Raw.Failed -> throw UiError("Screenshot failed: ${reason(raw.code)}")
        }
        return withContext(Dispatchers.Default) { encode(ok) }
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

    private fun encode(result: AccessibilityService.ScreenshotResult): Shot {
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
        val w = software.width
        val h = software.height
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(w, h))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(software, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
                .also { software.recycle() }
        } else {
            software
        }
        val bytes = ByteArrayOutputStream(64 * 1024).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
        val note = buildString {
            append("Screenshot ${scaled.width}x${scaled.height} attached")
            if (scale < 1f) {
                append(" (scaled ${String.format(java.util.Locale.US, "%.3f", scale)} of the ${w}x$h screen; divide image pixels by that to get tap x,y)")
            }
            append(". Protected screens (banking, DRM) appear black.")
        }
        scaled.recycle()
        return Shot(ContentPart.Image("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP)), note)
    }

    private fun reason(code: Int) = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "the service lacks screenshot access."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "requested too quickly; try again in a second."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display."
        6 /* ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34) */ -> "the screen is protected (secure window)."
        else -> "internal error ($code). Rely on the text tree instead."
    }
}
