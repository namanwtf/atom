package atom.voice.hotword

import android.content.Context

/**
 * Seam for tier 3: running "Hey Atom" on the audio DSP (Qualcomm Hexagon via the SoundTrigger HAL), so the main
 * CPU can stay asleep while listening. NOT part of v0.1. Loading a model into SoundTrigger needs privileged
 * permissions (MANAGE_SOUND_TRIGGER / CAPTURE_AUDIO_HOTWORD) and a vendor model format, i.e. a privileged
 * (root/system) install; nothing here does that yet.
 */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING, message = "DSP hotword is experimental and not implemented in v0.1")
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalDspHotword

sealed interface DspCapability {
    /** No backend exists in this build. */
    data object NotImplemented : DspCapability
    data class Unsupported(val reason: String) : DspCapability
    data object Supported : DspCapability
}

/** A wake-word backend that runs off the main CPU. */
@ExperimentalDspHotword
interface DspHotwordBackend {
    fun capability(context: Context): DspCapability
    /** Returns false if the DSP model could not be armed. */
    fun start(context: Context, onDetected: () -> Unit): Boolean
    fun stop()
}

@ExperimentalDspHotword
object DspHotword {
    /** Always [DspCapability.NotImplemented] in v0.1; the CPU path ([atom.voice.Wake]) is the only one. */
    fun capability(@Suppress("UNUSED_PARAMETER") context: Context): DspCapability = DspCapability.NotImplemented

    /** No backend in v0.1. */
    val backend: DspHotwordBackend? = null
}
