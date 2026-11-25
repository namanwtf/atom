package atom.voice.cloud

import atom.voice.asr.AsrEngine
import atom.voice.asr.AsrPhase
import atom.voice.asr.AsrRequest
import atom.voice.audio.Wav
import atom.voice.speech.AsrServiceId
import atom.voice.speech.JsonObjectSplitter
import atom.voice.speech.SecretResolver
import atom.voice.speech.SpeechConfig
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import atom.voice.speech.SpeechSecret
import atom.voice.speech.TtsServiceId
import atom.voice.speech.int
import atom.voice.speech.parseObject
import atom.voice.speech.str
import atom.voice.tts.PcmSink
import atom.voice.tts.TtsEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Volcengine Doubao speech wire formats, including the binary streaming-ASR frame protocol. Pure. */
object DoubaoProtocol {
    const val ASR_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

    const val TYPE_FULL_CLIENT = 0b0001
    const val TYPE_AUDIO_ONLY = 0b0010
    const val TYPE_FULL_SERVER = 0b1001
    const val TYPE_ERROR = 0b1111
    const val FLAG_NONE = 0b0000
    const val FLAG_LAST = 0b0010
    const val SERIAL_NONE = 0b0000
    const val SERIAL_JSON = 0b0001
    const val COMPRESS_NONE = 0b0000
    const val COMPRESS_GZIP = 0b0001

    fun header(type: Int, flags: Int, serialization: Int, compression: Int): ByteArray = byteArrayOf(
        0x11, // protocol version 1, header size 1 x 4 bytes
        ((type shl 4) or flags).toByte(),
        ((serialization shl 4) or compression).toByte(),
        0x00,
    )

    private fun frame(header: ByteArray, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(header.size + 4 + payload.size).put(header).putInt(payload.size).put(payload).array()

    fun fullClientRequestJson(uid: String, language: String): String = buildJsonObject {
        putJsonObject("user") { put("uid", uid) }
        putJsonObject("audio") {
            put("format", "pcm")
            put("codec", "raw")
            put("rate", 16_000)
            put("bits", 16)
            put("channel", 1)
            if (language.isNotBlank()) put("language", language.trim())
        }
        putJsonObject("request") {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("show_utterances", true)
            put("result_type", "full")
        }
    }.toString()

    fun fullClientRequest(json: String): ByteArray =
        frame(header(TYPE_FULL_CLIENT, FLAG_NONE, SERIAL_JSON, COMPRESS_NONE), json.toByteArray(Charsets.UTF_8))

    fun audio(pcm: ByteArray, last: Boolean): ByteArray =
        frame(header(TYPE_AUDIO_ONLY, if (last) FLAG_LAST else FLAG_NONE, SERIAL_NONE, COMPRESS_NONE), pcm)

    sealed interface ServerFrame {
        data class Result(val text: String, val last: Boolean) : ServerFrame
        data class Error(val code: Int, val message: String) : ServerFrame
        data object Unknown : ServerFrame
    }

    fun parse(bytes: ByteArray): ServerFrame {
        if (bytes.size < 4) return ServerFrame.Unknown
        val headerSize = (bytes[0].toInt() and 0x0F) * 4
        val type = (bytes[1].toInt() shr 4) and 0x0F
        val flags = bytes[1].toInt() and 0x0F
        val compression = bytes[2].toInt() and 0x0F
        val buf = ByteBuffer.wrap(bytes)
        buf.position(headerSize.coerceAtMost(bytes.size))
        return when (type) {
            TYPE_FULL_SERVER -> {
                if (flags and 0b0001 != 0 && buf.remaining() >= 4) buf.int // sequence
                if (buf.remaining() < 4) return ServerFrame.Unknown
                val size = buf.int
                if (size < 0 || size > buf.remaining()) return ServerFrame.Unknown
                val payload = ByteArray(size).also { buf.get(it) }
                val json = String(if (compression == COMPRESS_GZIP) gunzip(payload) else payload, Charsets.UTF_8)
                val text = parseObject(json).str("result.text").orEmpty()
                ServerFrame.Result(text, last = flags and FLAG_LAST != 0)
            }
            TYPE_ERROR -> {
                if (buf.remaining() < 8) return ServerFrame.Error(-1, "error")
                val code = buf.int
                val size = buf.int.coerceIn(0, buf.remaining())
                val msg = ByteArray(size).also { buf.get(it) }
                ServerFrame.Error(code, String(if (compression == COMPRESS_GZIP) gunzip(msg) else msg, Charsets.UTF_8))
            }
            else -> ServerFrame.Unknown
        }
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    /** Handshake headers: the new console API key, or the legacy App ID + Access Token when an App ID is set. */
    fun asrHeaders(apiKeyOrToken: String, appId: String, resourceId: String, connectId: String): Map<String, String> = buildMap {
        if (appId.isNotBlank()) {
            put("X-Api-App-Key", appId.trim())
            put("X-Api-Access-Key", apiKeyOrToken)
        } else {
            put("X-Api-Key", apiKeyOrToken)
        }
        put("X-Api-Resource-Id", resourceId.ifBlank { atom.voice.speech.DoubaoDefaults.ASR_RESOURCE_ID })
        put("X-Api-Request-Id", connectId)
        put("X-Api-Connect-Id", connectId)
        put("X-Api-Sequence", "-1")
    }

    fun ttsHeaders(apiKeyOrToken: String, appId: String, resourceId: String): Map<String, String> = buildMap {
        if (appId.isNotBlank()) {
            put("X-Api-App-Id", appId.trim())
            put("X-Api-Access-Key", apiKeyOrToken)
        } else {
            put("X-Api-Key", apiKeyOrToken)
        }
        put("X-Api-Resource-Id", resourceId.ifBlank { atom.voice.speech.DoubaoDefaults.TTS_RESOURCE_ID })
        put("X-Api-Request-Id", UUID.randomUUID().toString())
    }

    fun ttsBody(uid: String, text: String, speaker: String): String = buildJsonObject {
        putJsonObject("user") { put("uid", uid) }
        putJsonObject("req_params") {
            put("text", text)
            put("speaker", speaker)
            putJsonObject("audio_params") {
                put("format", "pcm")
                put("sample_rate", 24_000)
            }
        }
    }.toString()

    data class TtsChunk(val pcm: ByteArray?, val done: Boolean, val error: String?)

    fun parseTtsChunk(raw: String): TtsChunk {
        val o = parseObject(raw) ?: return TtsChunk(null, false, null)
        val code = o.int("code") ?: 0
        val data = o.str("data")?.takeIf { it.isNotEmpty() }?.let { Base64.getDecoder().decode(it) }
        return when {
            code == 20_000_000 -> TtsChunk(data, done = true, error = null)
            code != 0 -> TtsChunk(null, done = true, error = "${o.str("message") ?: "error"} ($code)")
            else -> TtsChunk(data, done = false, error = null)
        }
    }

    fun errorKind(code: Int): SpeechError = when (code) {
        45_000_002 -> SpeechError.NoSpeech
        45_000_081 -> SpeechError.Timeout
        55_000_031 -> SpeechError.Busy
        45_000_000 -> SpeechError.Auth
        else -> SpeechError.Server
    }
}

/** The user's Doubao credential: the API key, or the legacy Access Token when an App ID is configured. */
private suspend fun doubaoSecret(config: SpeechConfig, secrets: SecretResolver): String =
    if (config.doubaoAppId.isNotBlank()) secrets.require(SpeechSecret.DoubaoAccessToken) else secrets.require(SpeechSecret.DoubaoApiKey)

/** Doubao streaming recognition 2.0 (binary WebSocket protocol), with live text. */
class DoubaoStreamingAsr(private val http: OkHttpClient, private val uid: String) : AsrEngine {
    override val id = AsrServiceId.DoubaoStreaming

    override suspend fun recognize(request: AsrRequest): String = coroutineScope {
        val secret = doubaoSecret(request.config, request.secrets)
        val audio = checkNotNull(request.audio)
        request.onPhase(AsrPhase.Connecting)
        val connectId = UUID.randomUUID().toString()
        val frames = Channel<DoubaoProtocol.ServerFrame>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()
        val builder = Request.Builder().url(DoubaoProtocol.ASR_URL.replaceFirst("wss://", "https://"))
        DoubaoProtocol.asrHeaders(secret, request.config.doubaoAppId, request.config.doubaoResourceId, connectId)
            .forEach { (k, v) -> builder.header(k, v) }
        val ws = http.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                frames.trySend(DoubaoProtocol.parse(bytes.toByteArray()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = response?.code?.let { SpeechException(atom.voice.speech.HttpErrors.kind(it), "HTTP $it") }
                    ?: SpeechException(SpeechError.Network, t.javaClass.simpleName, t)
                opened.completeExceptionally(error)
                frames.close(error)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                frames.close()
            }
        })
        try {
            withTimeout(10_000) { opened.await() }
            ws.send(DoubaoProtocol.fullClientRequest(DoubaoProtocol.fullClientRequestJson(uid, "")).toByteString())
            request.onPhase(AsrPhase.Listening)
            val sender = launch {
                // ~160 ms per packet (two 80 ms frames); the service recommends 100-200 ms.
                val pending = ByteArrayOutputStream()
                for (frame in audio) {
                    pending.write(Wav.shortsToBytes(frame))
                    if (pending.size() >= PACKET_BYTES) {
                        ws.send(DoubaoProtocol.audio(pending.toByteArray(), last = false).toByteString())
                        pending.reset()
                    }
                }
                ws.send(DoubaoProtocol.audio(pending.toByteArray(), last = true).toByteString())
                request.onPhase(AsrPhase.Recognizing)
            }
            var text = ""
            withTimeout(AsrServiceId.DoubaoStreaming.maxRecordingMs + 15_000) {
                for (frame in frames) {
                    when (frame) {
                        is DoubaoProtocol.ServerFrame.Result -> {
                            if (frame.text.isNotEmpty() || frame.last) text = frame.text.ifEmpty { text }
                            request.onPartial(text)
                            if (frame.last && sender.isCompleted) break
                        }
                        is DoubaoProtocol.ServerFrame.Error ->
                            if (frame.code == 45_000_002) break else throw SpeechException(DoubaoProtocol.errorKind(frame.code), frame.message.take(160))
                        DoubaoProtocol.ServerFrame.Unknown -> Unit
                    }
                }
            }
            sender.cancel()
            text.trim()
        } finally {
            if (!ws.close(1000, null)) ws.cancel()
        }
    }

    private companion object {
        const val PACKET_BYTES = 5_120
    }
}

/** Doubao spoken answers (V3 HTTP chunked, 24 kHz PCM). */
class DoubaoTts(private val http: OkHttpClient, private val uid: String) : TtsEngine {
    override val id = TtsServiceId.Doubao

    override suspend fun synthesize(text: String, config: SpeechConfig, secrets: SecretResolver, sink: PcmSink) {
        val secret = doubaoSecret(config, secrets)
        val builder = Request.Builder().url(DoubaoProtocol.TTS_URL)
            .post(DoubaoProtocol.ttsBody(uid, text, config.doubaoVoice).jsonBody())
        DoubaoProtocol.ttsHeaders(secret, config.doubaoAppId, config.doubaoTtsResourceId).forEach { (k, v) -> builder.header(k, v) }
        val splitter = JsonObjectSplitter()
        var formatted = false
        var finished = false
        http.send(builder.build()).streamBytes { bytes ->
            for (obj in splitter.feed(String(bytes, Charsets.UTF_8))) {
                val chunk = DoubaoProtocol.parseTtsChunk(obj)
                if (chunk.error != null) throw SpeechException(SpeechError.Server, chunk.error)
                chunk.pcm?.let {
                    if (!formatted) sink.format(24_000)
                    formatted = true
                    sink.write(it)
                }
                if (chunk.done) finished = true
            }
        }
        if (!formatted) throw SpeechException(SpeechError.Server, if (finished) "No audio" else "Stream ended early")
    }
}
