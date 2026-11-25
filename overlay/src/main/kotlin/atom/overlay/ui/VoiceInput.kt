package atom.overlay.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import atom.overlay.Overlay
import atom.overlay.VoiceBridge

/** One dictation at a time through [Overlay.voice], exposed as Compose state. Main thread only. */
internal class VoiceInput(private val onHeard: (String) -> Unit) {
    var listening by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var session: VoiceBridge.Session? = null

    val available: Boolean get() = Overlay.voice.available

    fun start(context: Context) {
        if (listening) return
        error = null
        partial = ""
        listening = true
        val started = Overlay.voice.listen(context, object : VoiceBridge.Listener {
            override fun onLevel(level: Float) {
                if (listening) this@VoiceInput.level = level
            }

            override fun onPartial(text: String) {
                if (listening) partial = text
            }

            override fun onFinal(text: String) {
                finish()
                if (text.isNotBlank()) onHeard(text.trim())
            }

            override fun onError(message: String) {
                finish()
                error = message
            }
        })
        if (listening) session = started
    }

    fun toggle(context: Context) = if (listening) stop() else start(context)

    fun stop() {
        session?.stop() ?: finish()
    }

    fun cancel() {
        val s = session
        finish()
        s?.cancel()
    }

    fun clearError() {
        error = null
    }

    private fun finish() {
        listening = false
        level = 0f
        partial = ""
        session = null
    }
}
