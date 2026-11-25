package atom.voice.cloud

import atom.voice.asr.AsrEngine
import atom.voice.asr.AsrPhase
import atom.voice.asr.AsrRequest
import atom.voice.asr.collectPcm
import atom.voice.audio.Wav
import atom.voice.speech.AsrServiceId
import atom.voice.speech.SecretResolver
import atom.voice.speech.SpeechConfig
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import atom.voice.speech.SpeechSecret
import atom.voice.speech.TtsServiceId
import atom.voice.speech.at
import atom.voice.speech.contentText
import atom.voice.speech.parseObject
import atom.voice.speech.str
import atom.voice.tts.PcmSink
import atom.voice.tts.TtsEngine
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Alibaba Cloud Model Studio (DashScope) wire formats. Pure: builds and parses JSON only. */
object DashScopeProtocol {
    const val REALTIME_MODEL = "qwen3-asr-flash-realtime"
    const val SHORT_MODEL = "qwen3-asr-flash"
    const val FILE_MODEL = "qwen3-asr-flash-filetrans"
    const val TTS_MODEL = "qwen3-tts-flash"

    fun realtimeUrl(config: SpeechConfig) = "${config.dashScopeWsBase}/api-ws/v1/realtime?model=$REALTIME_MODEL"

    /** Manual turn mode (we decide when the user is done), 16 kHz PCM. */
    fun sessionUpdate(eventId: String, language: String): String = buildJsonObject {
        put("event_id", eventId)
        put("type", "session.update")
        putJsonObject("session") {
            putJsonArray("modalities") { add("text") }
            put("input_audio_format", "pcm")
            put("sample_rate", 16_000)
            if (language.isNotBlank()) putJsonObject("input_audio_transcription") { put("language", language.trim()) }
            put("turn_detection", JsonNull)
        }
    }.toString()

    fun append(eventId: String, pcm: ByteArray): String = buildJsonObject {
        put("event_id", eventId)
        put("type", "input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm))
    }.toString()

    fun commit(eventId: String): String = event(eventId, "input_audio_buffer.commit")
    fun finish(eventId: String): String = event(eventId, "session.finish")

    private fun event(id: String, type: String) = buildJsonObject {
        put("event_id", id)
        put("type", type)
    }.toString()

    sealed interface RealtimeEvent {
        data class Partial(val itemId: String, val text: String) : RealtimeEvent
        data class Completed(val itemId: String, val transcript: String) : RealtimeEvent
        data class Failed(val message: String) : RealtimeEvent
        data object Finished : RealtimeEvent
        data object Other : RealtimeEvent
    }

    fun parseRealtime(raw: String): RealtimeEvent {
        val o = parseObject(raw) ?: return RealtimeEvent.Other
        return when (o.str("type")) {
            "conversation.item.input_audio_transcription.text" ->
                RealtimeEvent.Partial(o.str("item_id").orEmpty(), o.str("text").orEmpty() + o.str("stash").orEmpty())
            "conversation.item.input_audio_transcription.completed" ->
                RealtimeEvent.Completed(o.str("item_id").orEmpty(), o.str("transcript").orEmpty())
            "conversation.item.input_audio_transcription.failed", "error" ->
                RealtimeEvent.Failed(o.str("error.message") ?: o.str("error.code") ?: "Recognition failed")
            "session.finished" -> RealtimeEvent.Finished
            else -> RealtimeEvent.Other
        }
    }

    /** OpenAI-compatible short-audio request; `asr_options` sits at the top level of the body. */
    fun shortAudioBody(wav: ByteArray, language: String): String = buildJsonObject {
        put("model", SHORT_MODEL)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "input_audio")
                        putJsonObject("input_audio") { put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)) }
                    }
                }
            }
        }
        put("stream", false)
        putJsonObject("asr_options") {
            if (language.isNotBlank()) put("language", language.trim())
            put("enable_itn", false)
        }
    }.toString()

    fun parseChatText(raw: String): String? = parseObject(raw)?.at("choices[0].message.content").contentText()

    fun fileTransBody(fileUrl: String, language: String): String = buildJsonObject {
        put("model", FILE_MODEL)
        putJsonObject("input") { put("file_url", fileUrl) }
        putJsonObject("parameters") {
            putJsonArray("channel_id") { add(0) }
            if (language.isNotBlank()) put("language", language.trim())
            put("enable_itn", false)
        }
    }.toString()

    data class TaskStatus(val status: String, val resultUrl: String?, val error: String?)

    fun parseTaskId(raw: String): String? = parseObject(raw)?.str("output.task_id")

    fun parseTask(raw: String): TaskStatus {
        val o = parseObject(raw)
        val output = o?.at("output")
        val url = output.str("result.transcription_url")
            ?: (output.at("results") as? JsonArray)?.firstNotNullOfOrNull { it.str("transcription_url") }
        val error = output.str("message") ?: output.str("code") ?: o.str("message")
        return TaskStatus(output.str("task_status") ?: "UNKNOWN", url, error)
    }

    /** Joins `transcripts[].text` of a transcription result file. */
    fun parseTranscription(raw: String): String {
        val list = parseObject(raw)?.at("transcripts") as? JsonArray ?: return ""
        return list.mapNotNull { it.str("text")?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun ttsBody(text: String, voice: String): String = buildJsonObject {
        put("model", TTS_MODEL)
        putJsonObject("input") {
            put("text", text)
            put("voice", voice.ifBlank { "Cherry" })
            put("language_type", "Auto")
        }
    }.toString()

    data class TtsChunk(val pcm: ByteArray?, val url: String?, val error: String?)

    fun parseTtsEvent(raw: String): TtsChunk {
        val o = parseObject(raw) ?: return TtsChunk(null, null, null)
        val code = o.str("code")
        if (!code.isNullOrBlank() && o.at("output") == null) return TtsChunk(null, null, o.str("message") ?: code)
        val data = o.str("output.audio.data")?.takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) }
        return TtsChunk(data, o.str("output.audio.url")?.takeIf { it.isNotBlank() }, null)
    }

    fun errorMessage(raw: String): String? = (parseObject(raw) as? JsonObject)?.let { it.str("message") ?: it.str("code") }
}

private fun bearer(key: String) = "Bearer $key"

/** Qwen real-time recognition over WebSocket, with live text. */
class QwenRealtimeAsr(private val http: OkHttpClient) : AsrEngine {
    override val id = AsrServiceId.QwenRealtime

    override suspend fun recognize(request: AsrRequest): String = coroutineScope {
        val key = request.secrets.require(SpeechSecret.QwenApiKey)
        val audio = checkNotNull(request.audio)
        request.onPhase(AsrPhase.Connecting)
        val ids = AtomicInteger()
        fun nextId() = "e" + ids.incrementAndGet()
        val events = Channel<DashScopeProtocol.RealtimeEvent>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val ws = http.newWebSocket(
            Request.Builder()
                .url(DashScopeProtocol.realtimeUrl(request.config).replaceFirst("wss://", "https://").replaceFirst("ws://", "http://"))
                .header("Authorization", bearer(key))
                .header("OpenAI-Beta", "realtime=v1")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    events.trySend(DashScopeProtocol.parseRealtime(text))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val error = when (val code = response?.code) {
                        null -> SpeechException(SpeechError.Network, t.javaClass.simpleName, t)
                        else -> SpeechException(atom.voice.speech.HttpErrors.kind(code), "HTTP $code")
                    }
                    opened.completeExceptionally(error)
                    events.close(error)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    events.close()
                }
            },
        )
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { opened.await() }
            ws.send(DashScopeProtocol.sessionUpdate(nextId(), request.config.language))
            request.onPhase(AsrPhase.Listening)
            val sender = launch {
                for (frame in audio) ws.send(DashScopeProtocol.append(nextId(), Wav.shortsToBytes(frame)))
                request.onPhase(AsrPhase.Recognizing)
                ws.send(DashScopeProtocol.commit(nextId()))
                ws.send(DashScopeProtocol.finish(nextId()))
            }
            val finals = LinkedHashMap<String, String>()
            var current = ""
            fun joined() = (finals.values + current).filter { it.isNotBlank() }.joinToString(" ")
            withTimeout(request.config.let { 15_000L + AsrServiceId.QwenRealtime.maxRecordingMs }) {
                for (event in events) {
                    when (event) {
                        is DashScopeProtocol.RealtimeEvent.Partial -> {
                            current = event.text
                            request.onPartial(joined())
                        }
                        is DashScopeProtocol.RealtimeEvent.Completed -> {
                            finals[event.itemId.ifEmpty { "i${finals.size}" }] = event.transcript
                            current = ""
                            request.onPartial(joined())
                        }
                        is DashScopeProtocol.RealtimeEvent.Failed -> throw SpeechException(SpeechError.Server, event.message)
                        DashScopeProtocol.RealtimeEvent.Finished -> break
                        DashScopeProtocol.RealtimeEvent.Other -> Unit
                    }
                }
            }
            sender.join()
            joined().trim()
        } finally {
            if (!ws.close(1000, null)) ws.cancel()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

/** Qwen short-audio recognition: record, then one HTTP request. */
class QwenShortAsr(private val http: OkHttpClient) : AsrEngine {
    override val id = AsrServiceId.QwenShort

    override suspend fun recognize(request: AsrRequest): String {
        val key = request.secrets.require(SpeechSecret.QwenApiKey)
        request.onPhase(AsrPhase.Listening)
        val pcm = checkNotNull(request.audio).collectPcm(MAX_BYTES)
        if (pcm.isEmpty()) return ""
        request.onPhase(AsrPhase.Recognizing)
        val body = DashScopeProtocol.shortAudioBody(Wav.encode(pcm, 16_000), request.config.language)
        val raw = http.text(
            Request.Builder()
                .url("${request.config.dashScopeBase}/compatible-mode/v1/chat/completions")
                .header("Authorization", bearer(key))
                .post(body.jsonBody())
                .build(),
        )
        return DashScopeProtocol.parseChatText(raw)?.trim()
            ?: throw SpeechException(SpeechError.Server, DashScopeProtocol.errorMessage(raw) ?: "No transcript")
    }

    private companion object {
        /** 60 s of 16 kHz mono PCM16 is 1.92 MB, well under the 10 MB (base64) limit. */
        const val MAX_BYTES = 16_000 * 2 * 62
    }
}

/** Qwen file transcription: upload to the user's own OSS bucket, transcribe, delete. */
class QwenFileAsr(private val http: OkHttpClient, private val cleanup: OssCleanup) : AsrEngine {
    override val id = AsrServiceId.QwenFile

    override suspend fun recognize(request: AsrRequest): String {
        val key = request.secrets.require(SpeechSecret.QwenApiKey)
        val oss = OssTarget.from(request.config, request.secrets)
        cleanup.retryPending(http, oss.credentials)
        request.onPhase(AsrPhase.Listening)
        val pcm = checkNotNull(request.audio).collectPcm(16_000 * 2 * 62)
        if (pcm.isEmpty()) return ""
        request.onPhase(AsrPhase.Uploading)
        val objectKey = oss.newObjectKey()
        cleanup.remember(oss, objectKey)
        try {
            OssClient.put(http, oss, objectKey, Wav.encode(pcm, 16_000), "audio/wav")
            val url = OssSigner.presignGet(oss, objectKey, expiresSeconds = 3_600, now = System.currentTimeMillis())
            request.onPhase(AsrPhase.Recognizing)
            val base = request.config.dashScopeBase
            val submitted = http.text(
                Request.Builder()
                    .url("$base/api/v1/services/audio/asr/transcription")
                    .header("Authorization", bearer(key))
                    .header("X-DashScope-Async", "enable")
                    .post(DashScopeProtocol.fileTransBody(url, request.config.language).jsonBody())
                    .build(),
            )
            val taskId = DashScopeProtocol.parseTaskId(submitted)
                ?: throw SpeechException(SpeechError.Server, DashScopeProtocol.errorMessage(submitted) ?: "No task id")
            // The service is asynchronous: wait with a gentle backoff only while this recording is being
            // transcribed (never in the background).
            val result = withTimeout(TASK_TIMEOUT_MS) {
                var wait = 500L
                while (true) {
                    delay(wait)
                    wait = (wait + 500).coerceAtMost(3_000)
                    val status = DashScopeProtocol.parseTask(
                        http.text(Request.Builder().url("$base/api/v1/tasks/$taskId").header("Authorization", bearer(key)).build()),
                    )
                    when (status.status) {
                        "SUCCEEDED" -> return@withTimeout status
                        "FAILED", "UNKNOWN", "CANCELED" -> throw SpeechException(SpeechError.Server, status.error ?: status.status)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
            val resultUrl = result.resultUrl ?: throw SpeechException(SpeechError.Server, "No transcription result")
            return DashScopeProtocol.parseTranscription(http.text(Request.Builder().url(resultUrl).build()))
        } finally {
            cleanup.deleteNow(http, oss, objectKey)
        }
    }

    private companion object {
        const val TASK_TIMEOUT_MS = 120_000L
    }
}

/** Qwen spoken answers, streamed over SSE as 24 kHz PCM (falls back to the result WAV URL). */
class QwenTts(private val http: OkHttpClient) : TtsEngine {
    override val id = TtsServiceId.Qwen
    override val maxChars = 500 // the service caps one request at 600 characters

    override suspend fun synthesize(text: String, config: SpeechConfig, secrets: SecretResolver, sink: PcmSink) {
        val key = secrets.require(SpeechSecret.QwenApiKey)
        val response = http.send(
            Request.Builder()
                .url("${config.dashScopeBase}/api/v1/services/aigc/multimodal-generation/generation")
                .header("Authorization", bearer(key))
                .header("X-DashScope-SSE", "enable")
                .post(DashScopeProtocol.ttsBody(text, config.qwenVoice).jsonBody())
                .build(),
        )
        var wrote = false
        var url: String? = null
        response.streamSse { data ->
            val chunk = DashScopeProtocol.parseTtsEvent(data)
            if (chunk.error != null) throw SpeechException(SpeechError.Server, chunk.error)
            chunk.pcm?.let {
                if (!wrote) sink.format(24_000)
                wrote = true
                sink.write(it)
            }
            chunk.url?.let { url = it }
        }
        if (!wrote) {
            val wavUrl = url ?: throw SpeechException(SpeechError.Server, "No audio")
            val decoder = Wav.StreamDecoder(24_000)
            val pcm = decoder.feed(http.bytes(Request.Builder().url(wavUrl).build()))
            sink.format(decoder.sampleRate, decoder.channels)
            sink.write(pcm)
        }
    }
}
