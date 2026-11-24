package atom.voice.engine

/**
 * The three openWakeWord-style models, behind an interface so the windowing logic is pure and testable.
 * All buffers are caller-owned and reused.
 */
internal interface WakeModels : AutoCloseable {
    /** Number of embeddings the classifier looks at (16 for openWakeWord-trained models). */
    val classifierFrames: Int

    /** [audio] = [WakePipeline.MEL_INPUT_SAMPLES] raw PCM16 values as floats -> [out] = 8 x 32 mel frames. */
    fun melspectrogram(audio: FloatArray, out: FloatArray)

    /** [melWindow] = 76 x 32 (transformed) mel frames -> [out] = 96-dim embedding. */
    fun embedding(melWindow: FloatArray, out: FloatArray)

    /** [embeddings] = [classifierFrames] x 96 -> score in 0..1. */
    fun classify(embeddings: FloatArray): Float
}

/**
 * Streaming feature pipeline matching openWakeWord's `AudioFeatures._streaming_features`:
 * each 1280-sample chunk plus the previous 480 samples (160 x 3) goes through the melspectrogram model, which
 * yields 8 new 32-bin frames; they are transformed with `x / 10 + 2` and appended to a 76-frame window (seeded
 * with ones); the window goes through the embedding model, giving one 96-dim embedding per 80 ms; the last
 * [WakeModels.classifierFrames] embeddings go through the classifier.
 */
internal class WakePipeline(private val models: WakeModels) {
    companion object {
        const val FRAME_SAMPLES = 1280
        const val CONTEXT_SAMPLES = 160 * 3
        const val MEL_INPUT_SAMPLES = FRAME_SAMPLES + CONTEXT_SAMPLES
        const val MEL_FRAMES_PER_CHUNK = 8
        const val MEL_BINS = 32
        const val MEL_WINDOW_FRAMES = 76
        const val EMBEDDING_DIM = 96
        const val NOT_READY = -1f
    }

    private val window = models.classifierFrames
    private val audio = FloatArray(MEL_INPUT_SAMPLES)
    private val melChunk = FloatArray(MEL_FRAMES_PER_CHUNK * MEL_BINS)
    private val melWindow = FloatArray(MEL_WINDOW_FRAMES * MEL_BINS)
    private val embedding = FloatArray(EMBEDDING_DIM)
    private val embeddings = FloatArray(window * EMBEDDING_DIM)
    private var validEmbeddings = 0

    init {
        reset()
    }

    /** Forgets all context (called when the speech gate re-opens after silence). */
    fun reset() {
        audio.fill(0f)
        melWindow.fill(1f)
        embeddings.fill(0f)
        validEmbeddings = 0
    }

    /** Processes one 80 ms frame; returns the classifier score or [NOT_READY] until enough embeddings exist. */
    fun process(frame: ShortArray, offset: Int = 0): Float {
        System.arraycopy(audio, FRAME_SAMPLES, audio, 0, CONTEXT_SAMPLES)
        for (i in 0 until FRAME_SAMPLES) audio[CONTEXT_SAMPLES + i] = frame[offset + i].toFloat()
        models.melspectrogram(audio, melChunk)

        val chunk = melChunk.size
        System.arraycopy(melWindow, chunk, melWindow, 0, melWindow.size - chunk)
        val base = melWindow.size - chunk
        for (i in 0 until chunk) melWindow[base + i] = melChunk[i] / 10f + 2f
        models.embedding(melWindow, embedding)

        System.arraycopy(embeddings, EMBEDDING_DIM, embeddings, 0, embeddings.size - EMBEDDING_DIM)
        System.arraycopy(embedding, 0, embeddings, embeddings.size - EMBEDDING_DIM, EMBEDDING_DIM)
        if (validEmbeddings < window) validEmbeddings++

        return if (validEmbeddings >= window) models.classify(embeddings) else NOT_READY
    }
}
