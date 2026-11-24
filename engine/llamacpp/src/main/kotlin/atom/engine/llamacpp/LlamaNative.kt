package atom.engine.llamacpp

/** Receives UTF-8-complete pieces of generated text. Return false to stop generation. */
internal fun interface TokenSink {
    fun onToken(utf8: ByteArray): Boolean
}

/** The whole JNI surface of `libatom_llama.so`. All text crosses as UTF-8 bytes. */
internal object LlamaNative {
    init {
        System.loadLibrary("atom_llama")
    }

    /** Returns a session handle; throws IllegalStateException on failure. */
    @JvmStatic external fun nativeLoad(path: ByteArray, contextWindow: Int, threads: Int, gpuLayers: Int): Long

    @JvmStatic external fun nativeContextSize(handle: Long): Int

    /** Applies the model's embedded chat template (llama.cpp built-ins, chatml fallback) with the assistant prefix. */
    @JvmStatic external fun nativeFormatChat(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>): ByteArray

    /** Blocks until done. Returns [promptTokens, generatedTokens, finish] where finish is 0 stop, 1 length, 2 cancelled. */
    @JvmStatic external fun nativeGenerate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, sink: TokenSink): IntArray

    /** Thread-safe; aborts the running prompt decode or token loop as soon as possible. */
    @JvmStatic external fun nativeCancel(handle: Long)

    @JvmStatic external fun nativeFree(handle: Long)
}
