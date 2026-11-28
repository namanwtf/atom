package atom.overlay.hey

import atom.overlay.ScreenCapture
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The screen context a "Hey ATOM" command takes along: shown to the model with its first turn, never stored. */
internal class HeyScreen(
    /** The app's name for the panel's chip (null when unknown). */
    val app: String?,
    /** The block that follows the user's words in the model's view. */
    val text: String,
    /** The screenshot, only when the model can see images. */
    val imageJpeg: ByteArray?,
    /** What the conversation keeps instead: a one-line notice. */
    val note: String,
)

/** Why no screen context goes with a command. */
internal enum class HeyScreenSkip { Off, NothingCaptured, Secure, Password, OwnApp, Empty }

internal sealed interface HeyScreenResult {
    class Used(val screen: HeyScreen) : HeyScreenResult
    data class Skipped(val reason: HeyScreenSkip) : HeyScreenResult
}

/**
 * Builds the screen context from one [ScreenCapture]. Nothing is used when the user turned it off, the window is
 * protected (FLAG_SECURE) or a password field is visible; the text is cut to [MAX_TEXT_CHARS] (about a thousand
 * tokens) and the screenshot goes only to a model that can see images. Pure, so it is unit-tested.
 */
internal object HeyContext {
    const val MAX_TEXT_CHARS = 4_000
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun build(
        capture: ScreenCapture?,
        enabled: Boolean,
        seesImages: Boolean,
        ownPackage: String,
        capturedAt: Long,
        note: (app: String?) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): HeyScreenResult {
        if (!enabled) return HeyScreenResult.Skipped(HeyScreenSkip.Off)
        capture ?: return HeyScreenResult.Skipped(HeyScreenSkip.NothingCaptured)
        if (capture.secure) return HeyScreenResult.Skipped(HeyScreenSkip.Secure)
        if (capture.passwordShown) return HeyScreenResult.Skipped(HeyScreenSkip.Password)
        if (capture.packageName == ownPackage) return HeyScreenResult.Skipped(HeyScreenSkip.OwnApp)
        val image = capture.screenshotJpeg?.takeIf { seesImages && it.isNotEmpty() }
        val body = truncate(capture.text.trim(), MAX_TEXT_CHARS)
        if (body.isEmpty() && image == null) return HeyScreenResult.Skipped(HeyScreenSkip.Empty)
        val app = capture.appLabel?.takeIf { it.isNotBlank() }
        val at = clock.format(Instant.ofEpochMilli(capturedAt).atZone(zone))
        val text = buildString {
            append("[What was on screen when the user said \"Hey ATOM\" (captured at ").append(at).append("). ")
            append("When the request says \"this\", \"here\" or \"it\", it most likely means this screen. ")
            append("The element ids below are not valid for actions: call read_screen before tapping or typing.]")
            val name = listOfNotNull(app, capture.packageName?.let { "($it)" }).joinToString(" ")
            if (name.isNotEmpty()) append("\nApp: ").append(name)
            if (body.isNotEmpty()) append("\nScreen:\n").append(body)
        }
        return HeyScreenResult.Used(HeyScreen(app, text, image, note(app)))
    }

    /** At most [max] chars, cut at a line end, with a closing line that says more was on screen. */
    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        val marker = "\n… (more on screen; read_screen shows all of it)"
        val room = (max - marker.length).coerceAtLeast(0)
        val cut = text.take(room)
        val line = cut.lastIndexOf('\n')
        return (if (line > room / 2) cut.take(line) else cut).trimEnd() + marker
    }
}
