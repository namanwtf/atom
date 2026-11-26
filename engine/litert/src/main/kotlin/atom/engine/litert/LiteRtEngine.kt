package atom.engine.litert

import android.content.Context
import android.util.Base64
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.Releasable
import atom.core.llm.Role
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.BackendType
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Hardware LiteRT-LM runs on. [Auto] tries NPU (only if the device provides Qualcomm's runtime), then GPU,
 * then CPU: lowest energy per token first.
 */
enum class LiteRtBackend { Auto, Npu, Gpu, Cpu }

/**
 * On-device engine for `.litertlm` models via Google's LiteRT-LM runtime.
 *
 * The model loads lazily on the first [stream] call. Each request builds a fresh LiteRT-LM
 * [Conversation] from the full message list (the model's own chat template is applied by the
 * runtime), streams the reply, and closes the conversation again, so the engine is stateless
 * between requests just like a cloud engine.
 */
class LiteRtEngine(
    context: Context,
    private val modelPath: String,
    private val backend: LiteRtBackend = LiteRtBackend.Auto,
    private val contextWindow: Int = 4096,
) : LlmEngine, Releasable {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    /** All native calls run here, serialized, never on the main thread. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    private var engine: Engine? = null

    @Volatile private var visionLoaded = false

    /** The backend the loaded model actually runs on, or null while not loaded. */
    @Volatile var activeBackend: LiteRtBackend? = null
        private set

    override val id: String = "litert:" + File(modelPath).name

    /**
     * The context the model file was built for. LiteRT-LM models have a fixed KV cache (Qwen3 0.6B: 4096 tokens);
     * asking for more is silently capped by the runtime, so a prompt planned for the larger window does not fit and
     * every token beyond it is lost. Null when the file does not say, or its context is dynamic.
     */
    private val modelContext: Int? by lazy {
        runCatching {
            ModelInfo.from(modelPath).use { info -> info.maxContextTokens().takeIf { it > 0 && !info.isDynamicContext() } }
        }.getOrNull()
    }

    /** The window this engine really has: the configured one, never more than the model's own. */
    private val effectiveContext: Int
        get() = modelContext?.let { minOf(it, contextWindow) } ?: contextWindow

    /** `vision` becomes true once a vision-capable model has been loaded with its vision encoder. */
    override val capabilities: EngineCapabilities
        get() = EngineCapabilities(
            nativeToolCalls = false,
            vision = visionLoaded,
            contextWindow = effectiveContext,
            onDevice = true,
        )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        mutex.withLock {
            val eng = withContext(worker) { ensureLoaded() }
            val plan = toConversationPlan(request.messages, visionLoaded)
            val maxOut = request.maxOutputTokens.coerceAtLeast(1)
            val thinking = request.reasoning != ReasoningEffort.Off

            val conversation = withContext(worker) {
                try {
                    eng.createConversation(
                        ConversationConfig(
                            systemInstruction = plan.system,
                            initialMessages = plan.history,
                            samplerConfig = samplerFor(request.temperature, thinking),
                            automaticToolCalling = false,
                            maxOutputToken = maxOut,
                            // Null keeps the model's own default (Qwen3 thinks unless told not to).
                            thinkingConfig = if (thinking) null else ThinkingConfig(enableThinking = false),
                        ),
                    )
                } catch (t: Throwable) {
                    throw LlmException("LiteRT-LM could not start a conversation: ${t.message}", retryable = true, cause = t)
                }
            }

            val chunks = AtomicInteger(0)
            val cancelRequested = AtomicBoolean(false)
            val contextFull = AtomicBoolean(false)
            val done = CompletableDeferred<Unit>()
            val cancelNative = {
                if (cancelRequested.compareAndSet(false, true)) {
                    // Off the callback thread: never call back into the runtime from inside its callback.
                    launch(Dispatchers.Default) { runCatching { conversation.cancelProcess() } }
                }
            }

            try {
                withContext(worker) {
                    conversation.sendMessageAsync(
                        plan.last,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                // Thinking models (Qwen3) stream their reasoning in the "thought" channel.
                                val thought = message.channels[THOUGHT_CHANNEL].orEmpty()
                                val text = message.contents.contents
                                    .filterIsInstance<Content.Text>()
                                    .joinToString("") { it.text }
                                if (text.isEmpty() && thought.isEmpty()) return
                                val n = chunks.incrementAndGet()
                                if (thought.isNotEmpty() && trySend(LlmEvent.ReasoningDelta(thought)).isFailure) cancelNative()
                                if (text.isNotEmpty() && trySend(LlmEvent.TextDelta(text)).isFailure) cancelNative()
                                if (n >= maxOut) cancelNative()
                            }

                            override fun onDone() {
                                done.complete(Unit)
                            }

                            override fun onError(throwable: Throwable) {
                                when {
                                    cancelRequested.get() -> done.complete(Unit)
                                    // The KV cache is full: the answer ends here, like reaching the output limit.
                                    isContextFull(throwable) -> {
                                        contextFull.set(true)
                                        done.complete(Unit)
                                    }
                                    else -> done.completeExceptionally(throwable)
                                }
                            }
                        },
                    )
                }
                try {
                    done.await()
                } catch (e: LlmException) {
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // A prompt longer than the model's context fails the same way on every retry.
                    if (isPromptTooLong(t)) {
                        throw LlmException(
                            "The prompt does not fit this model's context window of $effectiveContext tokens: ${t.message}",
                            retryable = false,
                            cause = t,
                        )
                    }
                    throw LlmException("LiteRT-LM generation failed: ${t.message}", retryable = true, cause = t)
                }

                val generated = chunks.get()
                // LiteRT-LM streams one chunk per decode step, so chunks approximate generated tokens.
                val total = withContext(worker) { runCatching { conversation.getTokenCount() }.getOrDefault(0) }
                send(LlmEvent.Usage(inputTokens = (total - generated).coerceAtLeast(0), outputTokens = generated))
                send(LlmEvent.Finished(if (generated >= maxOut || contextFull.get()) FinishReason.Length else FinishReason.Stop))
            } finally {
                withContext(NonCancellable) {
                    if (!done.isCompleted) {
                        cancelRequested.set(true)
                        runCatching { conversation.cancelProcess() }
                        // Wait for the native decode loop to wind down before the conversation is freed.
                        withTimeoutOrNull(10_000) { runCatching { done.await() } }
                    }
                    // Freeing a conversation the native loop still decodes into aborts the process; when Stop's
                    // cancel was not honoured in time, leave it to the engine's own release instead.
                    if (done.isCompleted) withContext(worker) { runCatching { conversation.close() } }
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    override suspend fun release() {
        withContext(NonCancellable) {
            mutex.withLock {
                withContext(worker) {
                    engine?.let { runCatching { it.close() } }
                    engine = null
                    activeBackend = null
                    visionLoaded = false
                }
            }
        }
    }

    // ---- loading -------------------------------------------------------------------------------

    private fun ensureLoaded(): Engine {
        engine?.let { return it }
        val file = File(modelPath)
        if (!file.isFile) throw LlmException("Model file not found: $modelPath", retryable = false)
        if (!file.canRead()) throw LlmException("Model file is not readable: $modelPath", retryable = false)
        if (file.length() == 0L) throw LlmException("Model file is empty: $modelPath", retryable = false)

        quietNativeLogs()

        // Header probe: which accelerators the file was compiled for and whether it has a vision encoder.
        // A file LiteRT-LM cannot even parse is reported as corrupt right away.
        var parseError: Throwable? = null
        val info = try {
            ModelInfo.from(modelPath)
        } catch (t: Throwable) {
            parseError = t
            null
        }
        val (supported, hasVision) = try {
            val backends = info?.let { runCatching { it.supportedBackends(Modality.TEXT) }.getOrNull() }.orEmpty()
            val vision = info?.let { runCatching { it.inputModalities().vision }.getOrNull() } == true
            backends to vision
        } finally {
            info?.let { runCatching { it.close() } }
        }

        val candidates = candidateBackends(supported)
        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            // Try with the vision encoder first (if any), then text-only, before moving down the chain.
            val visionOptions = if (hasVision) listOf(true, false) else listOf(false)
            for (withVision in visionOptions) {
                var created: Engine? = null
                try {
                    created = Engine(engineConfig(candidate, withVision))
                    created.initialize()
                    engine = created
                    activeBackend = candidate
                    visionLoaded = withVision
                    if (backend == LiteRtBackend.Auto) rememberBackend(file, candidate)
                    return created
                } catch (t: Throwable) {
                    runCatching { created?.close() }
                    errors += "${candidate.name}${if (withVision) "+vision" else ""}: ${t.message ?: t.javaClass.simpleName}"
                }
            }
            if (backend == LiteRtBackend.Auto) forgetBackend(file, candidate)
        }
        val detail = errors.joinToString("; ")
        val message = if (parseError != null) {
            "Model file looks corrupt or is not a LiteRT-LM model (${file.name}): ${parseError.message}. $detail"
        } else {
            "Could not load ${file.name} on ${candidates.joinToString("/") { it.name }}: $detail"
        }
        throw LlmException(message.trim(), retryable = false, cause = parseError)
    }

    private fun candidateBackends(supported: List<BackendType>): List<LiteRtBackend> {
        if (backend != LiteRtBackend.Auto) return listOf(backend)
        // A failed NPU init still costs a model load, so only try it when the runtime is really there.
        var chain = listOfNotNull(
            LiteRtBackend.Npu.takeIf { npuLibraryDir != null },
            LiteRtBackend.Gpu,
            LiteRtBackend.Cpu,
        )
        if (supported.isNotEmpty()) {
            val allowed = supported.map {
                when (it) {
                    BackendType.NPU -> LiteRtBackend.Npu
                    BackendType.GPU -> LiteRtBackend.Gpu
                    BackendType.CPU -> LiteRtBackend.Cpu
                }
            }.toSet()
            val filtered = chain.filter { it in allowed }
            if (filtered.isNotEmpty()) chain = filtered
        }
        val remembered = recalledBackend(File(modelPath))
        return if (remembered != null && remembered in chain) listOf(remembered) + (chain - remembered) else chain
    }

    private fun engineConfig(target: LiteRtBackend, withVision: Boolean): EngineConfig {
        val main = toBackend(target)
        // The vision encoder runs best on the GPU when the decoder sits on an accelerator.
        val vision = if (!withVision) null else when (target) {
            LiteRtBackend.Cpu -> toBackend(LiteRtBackend.Cpu)
            else -> Backend.GPU()
        }
        return EngineConfig(
            modelPath = modelPath,
            backend = main,
            visionBackend = vision,
            audioBackend = null,
            maxNumTokens = effectiveContext,
            cacheDir = File(appContext.cacheDir, "litertlm").apply { mkdirs() }.path,
        )
    }

    /**
     * Where Qualcomm's QNN HTP runtime lives, if anywhere. Atom never bundles it (proprietary, and the app is
     * GPL-3.0); it is used only when the phone's own firmware or the user provides it.
     */
    private val npuLibraryDir: String? by lazy {
        listOf(appContext.applicationInfo.nativeLibraryDir, "/vendor/lib64", "/odm/lib64", "/system/lib64")
            .firstOrNull { dir -> runCatching { File(dir, "libQnnHtp.so").isFile }.getOrDefault(false) }
    }

    private fun toBackend(target: LiteRtBackend): Backend = when (target) {
        LiteRtBackend.Npu -> Backend.NPU(nativeLibraryDir = npuLibraryDir ?: appContext.applicationInfo.nativeLibraryDir)
        LiteRtBackend.Gpu -> Backend.GPU()
        LiteRtBackend.Cpu, LiteRtBackend.Auto -> Backend.CPU(threadCount = performanceCoreCount())
    }

    // ---- remembered backend (per model file, survives restarts) -------------------------------

    private val prefs by lazy { appContext.getSharedPreferences("atom4_litert_backend", Context.MODE_PRIVATE) }

    private fun key(file: File) = "${file.absolutePath}|${file.length()}|${file.lastModified()}"

    private fun recalledBackend(file: File): LiteRtBackend? =
        prefs.getString(key(file), null)?.let { name -> LiteRtBackend.entries.firstOrNull { it.name == name } }

    private fun rememberBackend(file: File, b: LiteRtBackend) {
        prefs.edit().putString(key(file), b.name).apply()
    }

    private fun forgetBackend(file: File, b: LiteRtBackend) {
        if (recalledBackend(file) == b) prefs.edit().remove(key(file)).apply()
    }

    private companion object {
        @Volatile private var logsConfigured = false

        fun quietNativeLogs() {
            if (logsConfigured) return
            logsConfigured = true
            runCatching { Engine.setNativeMinLogSeverity(LogSeverity.ERROR) }
        }

        const val THOUGHT_CHANNEL = "thought"

        /**
         * No temperature: the model's own defaults (for Qwen3 its thinking-mode settings: top-k 20, top-p 0.95,
         * temperature 0.6), or with thinking off Qwen's non-thinking settings (top-k 20, top-p 0.8, temperature 0.7).
         */
        fun samplerFor(temperature: Double?, thinking: Boolean): SamplerConfig? = when {
            temperature == null && thinking -> null
            temperature == null -> SamplerConfig(topK = 20, topP = 0.8, temperature = 0.7, seed = Random.nextInt(1, Int.MAX_VALUE))
            temperature <= 0.0 -> SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0) // greedy
            else -> SamplerConfig(topK = 40, topP = 0.9, temperature = temperature, seed = Random.nextInt(1, Int.MAX_VALUE))
        }

        private fun messages(t: Throwable) = generateSequence(t) { it.cause }.take(4).mapNotNull { it.message }.joinToString(" ")

        /** LiteRT-LM: "Input token ids are too long. Exceeding the maximum number of tokens allowed: 4568 >= 4096". */
        fun isPromptTooLong(t: Throwable) = messages(t).let {
            it.contains("token ids are too long", ignoreCase = true) || it.contains("exceeding the maximum number of tokens", ignoreCase = true)
        }

        /** LiteRT-LM: "Max number of tokens reached" while decoding. */
        fun isContextFull(t: Throwable) = messages(t).contains("max number of tokens reached", ignoreCase = true)
    }
}

// ---- message mapping ---------------------------------------------------------------------------

private class ConversationPlan(val system: Contents?, val history: List<Message>, val last: Message)

private fun toConversationPlan(messages: List<ChatMessage>, vision: Boolean): ConversationPlan {
    val systemText = messages.filter { it.role == Role.System }.joinToString("\n\n") { it.text }.trim()
    val turns = messages.filter { it.role != Role.System }
    val lastTurn = turns.lastOrNull()
        ?: throw LlmException("Request has no user message", retryable = false)
    if (lastTurn.role == Role.Assistant) {
        throw LlmException("The last message must come from the user or a tool, not the assistant", retryable = false)
    }
    return ConversationPlan(
        system = systemText.takeIf { it.isNotEmpty() }?.let { Contents.of(it) },
        history = turns.dropLast(1).map { it.toLiteRt(vision) },
        last = lastTurn.toLiteRt(vision),
    )
}

private fun ChatMessage.toLiteRt(vision: Boolean): Message = when (role) {
    Role.Assistant -> {
        val calls = toolCalls.joinToString("") { "\n<tool_call>{\"name\": \"${it.name}\", \"arguments\": ${it.argumentsJson.ifBlank { "{}" }}}</tool_call>" }
        Message.model(Contents.of(text + calls))
    }
    Role.Tool -> Message.user(
        contentsOf(
            listOf(ContentPart.Text("<tool_result name=\"$toolName\">\n$text\n</tool_result>")) +
                parts.filterIsInstance<ContentPart.Image>(),
            vision,
        ),
    )
    Role.User, Role.System -> Message.user(contentsOf(parts, vision))
}

private fun contentsOf(parts: List<ContentPart>, vision: Boolean): Contents {
    val out = mutableListOf<Content>()
    for (part in parts) {
        when (part) {
            is ContentPart.Text -> if (part.text.isNotEmpty()) out += Content.Text(part.text)
            is ContentPart.Image -> if (vision) {
                val bytes = runCatching { Base64.decode(part.base64, Base64.DEFAULT) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) out += Content.ImageBytes(bytes)
            }
        }
    }
    if (out.none { it is Content.Text }) out += Content.Text("")
    return Contents.of(out)
}

/** Cores within ~10% of the fastest core's max frequency, capped to [2, 6]. */
internal fun performanceCoreCount(): Int {
    val freqs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
        ?.mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }
        .orEmpty()
    val count = if (freqs.isEmpty()) {
        Runtime.getRuntime().availableProcessors() / 2
    } else {
        val max = freqs.max()
        freqs.count { it >= max * 0.9 }
    }
    return count.coerceIn(2, 6)
}
