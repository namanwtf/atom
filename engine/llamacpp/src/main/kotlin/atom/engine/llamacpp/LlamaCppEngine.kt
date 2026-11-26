package atom.engine.llamacpp

import atom.core.llm.ChatMessage
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import atom.core.llm.Releasable
import atom.core.llm.Role
import atom.core.llm.ThinkTagSplitter
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * @param threads 0 = auto: the number of performance cores (see [performanceCoreCount]).
 * @param gpuLayers layers to offload to a GPU backend. This build compiles only the CPU backend,
 *   so the value is passed through to llama.cpp but currently has no effect.
 */
data class LlamaConfig(val contextWindow: Int = 4096, val threads: Int = 0, val gpuLayers: Int = 0)

/**
 * On-device engine for GGUF models via llama.cpp (CPU, ARMv8.2+dotprod baseline).
 *
 * The model is memory-mapped lazily on the first [stream]. The KV cache is kept between requests and
 * the longest common token prefix is reused, so an agent loop only pays for the new tokens of each turn.
 */
class LlamaCppEngine(
    private val modelPath: String,
    private val config: LlamaConfig = LlamaConfig(),
) : LlmEngine, Releasable {

    override val id: String = "llamacpp:" + File(modelPath).name

    override val capabilities = EngineCapabilities(
        nativeToolCalls = false,
        vision = false,
        contextWindow = config.contextWindow,
        onDevice = true,
    )

    private val mutex = Mutex()

    /** One worker thread at a time owns the native session; never the main thread. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    private var handle = 0L

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        mutex.withLock {
            val h = withContext(worker) { ensureLoaded() }
            val prompt = withContext(worker) { formatPrompt(h, request.messages) }
            val temperature = (request.temperature ?: DEFAULT_TEMPERATURE).toFloat()
            val maxTokens = request.maxOutputTokens.coerceAtLeast(1)

            // Thinking models (Qwen3) write <think>…</think> as text: that part goes out as reasoning, and stray
            // chat-format markers (<|im_end|>…) never reach the answer.
            val splitter = ThinkTagSplitter()
            val generation = async(worker) {
                val job = coroutineContext.job
                nativeCall("generation failed", retryable = true) {
                    LlamaNative.nativeGenerate(h, prompt, maxTokens, temperature) { utf8 ->
                        splitter.feed(String(utf8, Charsets.UTF_8)).all { trySend(it).isSuccess } && job.isActive
                    }
                }
            }
            val result = try {
                generation.await()
            } finally {
                if (!generation.isCompleted) {
                    // Collector went away: stop the native loop, and keep the lock until it has returned.
                    LlamaNative.nativeCancel(h)
                    withContext(NonCancellable) { generation.join() }
                }
            }
            val reason = when (result[2]) {
                FINISH_LENGTH -> FinishReason.Length
                FINISH_CANCELLED -> FinishReason.Cancelled
                else -> FinishReason.Stop
            }
            splitter.finish().forEach { send(it) }
            send(LlmEvent.Usage(inputTokens = result[0], outputTokens = result[1]))
            send(LlmEvent.Finished(reason))
        }
    }.buffer(Channel.UNLIMITED)

    override suspend fun release() {
        withContext(NonCancellable) {
            mutex.withLock {
                withContext(worker) {
                    if (handle != 0L) {
                        LlamaNative.nativeFree(handle)
                        handle = 0L
                    }
                }
            }
        }
    }

    private fun ensureLoaded(): Long {
        if (handle != 0L) return handle
        val file = File(modelPath)
        if (!file.isFile) throw LlmException("Model file not found: $modelPath", retryable = false)
        if (!file.canRead()) throw LlmException("Model file is not readable: $modelPath", retryable = false)
        if (!hasGgufMagic(file)) {
            throw LlmException("${file.name} is not a GGUF model file (corrupt or wrong format)", retryable = false)
        }
        val threads = if (config.threads > 0) config.threads else performanceCoreCount()
        handle = nativeCall("could not load ${file.name}", retryable = false) {
            LlamaNative.nativeLoad(
                modelPath.toByteArray(Charsets.UTF_8),
                config.contextWindow,
                threads,
                config.gpuLayers,
            )
        }
        return handle
    }

    private fun formatPrompt(h: Long, messages: List<ChatMessage>): ByteArray {
        if (messages.none { it.role != Role.System }) {
            throw LlmException("Request has no user message", retryable = false)
        }
        val roles = ArrayList<ByteArray>(messages.size)
        val contents = ArrayList<ByteArray>(messages.size)
        for (m in messages) {
            val (role, text) = when (m.role) {
                Role.System -> "system" to m.text
                Role.User -> "user" to m.text
                Role.Assistant -> "assistant" to (m.text + m.toolCalls.joinToString("") {
                    "\n<tool_call>{\"name\": \"${it.name}\", \"arguments\": ${it.argumentsJson.ifBlank { "{}" }}}</tool_call>"
                })
                // Built-in llama.cpp templates have no tool role; present results as a user turn.
                Role.Tool -> "user" to "<tool_result name=\"${m.toolName}\">\n${m.text}\n</tool_result>"
            }
            roles += role.toByteArray(Charsets.UTF_8)
            contents += text.toByteArray(Charsets.UTF_8)
        }
        return nativeCall("could not apply the chat template", retryable = false) {
            LlamaNative.nativeFormatChat(h, roles.toTypedArray(), contents.toTypedArray())
        }
    }

    private inline fun <T> nativeCall(what: String, retryable: Boolean, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmException) {
        throw e
    } catch (e: IllegalStateException) {
        val msg = e.message.orEmpty()
        // Context overflow can't be fixed by retrying the same request.
        val retry = retryable && !msg.contains("context window")
        throw LlmException("llama.cpp: $what: $msg", retryable = retry, cause = e)
    } catch (e: UnsatisfiedLinkError) {
        throw LlmException("llama.cpp native library is missing for this device: ${e.message}", retryable = false, cause = e)
    }

    private companion object {
        const val DEFAULT_TEMPERATURE = 0.7
        const val FINISH_LENGTH = 1
        const val FINISH_CANCELLED = 2

        fun hasGgufMagic(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))
            }
        }.getOrDefault(false)
    }
}

/**
 * Number of performance cores: cores clocked at >= 70% of the fastest core, capped to [2, 6].
 * That splits prime+performance clusters from efficiency cores (typically ~60% or lower), which
 * make llama.cpp slower and hotter. All-big-core chips like SM8850 (prime 4.6 GHz, perf ~3.6 GHz)
 * correctly count every core instead of only the two prime cores.
 */
internal fun performanceCoreCount(): Int {
    val freqs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
        ?.mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }
        .orEmpty()
    val count = if (freqs.isEmpty()) {
        Runtime.getRuntime().availableProcessors() / 2
    } else {
        val max = freqs.max()
        freqs.count { it >= max * 0.7 }
    }
    return count.coerceIn(2, 6)
}
