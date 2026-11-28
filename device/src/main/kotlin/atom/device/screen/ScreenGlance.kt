package atom.device.screen

import atom.device.Accessibility
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * One look at the app in front when the user says "Hey ATOM", through Atom's accessibility service: its outline
 * (read_screen's walk) and one small screenshot. Taken once per wake word, on request only; nothing is kept here.
 */
object ScreenGlance {
    /**
     * [secure]: the window is protected (FLAG_SECURE), so nothing of it may be used. [passwordShown]: a password field
     * is on screen. [screenshotJpeg] is null when the frame could not be taken (or is protected).
     */
    class Glance(
        val packageName: String?,
        val text: String,
        val passwordShown: Boolean,
        val secure: Boolean,
        val screenshotJpeg: ByteArray?,
    )

    private const val MAX_SIDE = 1024
    private const val QUALITY = 70

    /** Null when the service is not connected or the screen is off or locked. */
    suspend fun take(maxChars: Int = 6_000): Glance? {
        val svc = Accessibility.service ?: return null
        if (!ScreenGate.usable(svc)) return null
        return coroutineScope {
            // The frame is asked for first: it is the moment the user spoke, before anything of Atom is drawn.
            val shot = async { runCatching { Screenshots.glimpse(svc, MAX_SIDE, QUALITY) }.getOrNull() }
            val text = runCatching { UiController.glance(svc, maxChars) }.getOrNull()
            val frame = shot.await()
            Glance(
                packageName = text?.packageName,
                text = text?.text.orEmpty(),
                passwordShown = text?.passwordShown == true,
                secure = frame?.secure == true,
                screenshotJpeg = frame?.jpeg,
            )
        }
    }
}
