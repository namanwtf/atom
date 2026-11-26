package atom.core.ondevice

import atom.core.llm.LlmEngine

/**
 * The seam to the optional "on-device AI pack": a separate APK (module `:ondevice-pack`) that carries the on-device
 * LLM engines (LiteRT-LM, llama.cpp), the wake-word runtime and the wake-word models. The app itself contains none
 * of them. The app downloads the pack, checks it and loads it with a class loader; [ENTRY_CLASS] implements this
 * interface, and every call into the pack goes through it.
 *
 * The pack is built against these exact types: they (and the Kotlin and kotlinx libraries they expose) are kept
 * unrenamed in the app's release build (app/proguard-rules.pro). Change them only together with the pack, and
 * only with a new app version (an installed pack must have the app's versionCode to load).
 */
interface OnDeviceEngines {
    /** Display names of the engines the pack carries, e.g. "LiteRT-LM". */
    val engineNames: List<String>

    /**
     * A LiteRT-LM engine for a `.litertlm` file. [context] is an `android.content.Context` (core is plain JVM).
     * Loading happens on first use, off the main thread.
     */
    fun liteRt(context: Any, modelPath: String, backend: OnDeviceBackend, contextWindow: Int): LlmEngine

    /** A llama.cpp engine for a `.gguf` file. [gpuLayers] 0 keeps everything on the CPU. */
    fun llamaCpp(modelPath: String, contextWindow: Int, gpuLayers: Int): LlmEngine

    /** Loads the wake-word models from the pack file at [packPath]. Call off the main thread. */
    fun wakeModels(packPath: String): WakeModelsLoad

    /**
     * Loads each native library of the pack and returns one line per library ("name: ok" or the error). For the
     * pack screen's diagnostics and CI; it costs a little memory, so it never runs on its own.
     */
    fun probeNative(): List<String>

    companion object {
        /** Class name of the pack's implementation; it has a public no-argument constructor. */
        const val ENTRY_CLASS = "atom.ondevice.pack.PackEntry"

        /** Package name the pack APK carries. Never installed; it only names the signed container. */
        const val PACK_PACKAGE = "io.github.namanwtf.atom.ondevice"
    }
}

/** Hardware an on-device model runs on. [Auto] lets the engine pick the fastest one that works. */
enum class OnDeviceBackend { Auto, Npu, Gpu, Cpu }

/**
 * The three openWakeWord-style models of "Hey Atom". All buffers are caller-owned and reused.
 * Shapes: [WakeShapes].
 */
interface WakeModels : AutoCloseable {
    /** Number of embeddings the classifier looks at (16 for openWakeWord-trained models). */
    val classifierFrames: Int

    /** [audio] = [WakeShapes.MEL_INPUT_SAMPLES] raw PCM16 values as floats -> [out] = 8 x 32 mel frames. */
    fun melspectrogram(audio: FloatArray, out: FloatArray)

    /** [melWindow] = 76 x 32 (transformed) mel frames -> [out] = 96-dim embedding. */
    fun embedding(melWindow: FloatArray, out: FloatArray)

    /** [embeddings] = [classifierFrames] x 96 -> score in 0..1. */
    fun classify(embeddings: FloatArray): Float
}

sealed interface WakeModelsLoad {
    class Ok(val models: WakeModels) : WakeModelsLoad
    class Failed(val reason: String) : WakeModelsLoad
}

/** Tensor sizes of openWakeWord's front-end, shared by the pipeline (voice) and the models (pack). */
object WakeShapes {
    const val FRAME_SAMPLES = 1280
    const val CONTEXT_SAMPLES = 160 * 3
    const val MEL_INPUT_SAMPLES = FRAME_SAMPLES + CONTEXT_SAMPLES
    const val MEL_FRAMES_PER_CHUNK = 8
    const val MEL_BINS = 32
    const val MEL_WINDOW_FRAMES = 76
    const val EMBEDDING_DIM = 96

    /** Asset folder inside the pack and the model file names. */
    const val ASSET_DIR = "wakeword"
    const val MELSPEC_MODEL = "melspectrogram.tflite"
    const val EMBEDDING_MODEL = "embedding_model.tflite"
    const val CLASSIFIER_MODEL = "hey_atom.tflite"
}
