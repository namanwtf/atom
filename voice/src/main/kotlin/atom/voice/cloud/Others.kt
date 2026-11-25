package atom.voice.cloud

import atom.voice.asr.AsrEngine
import atom.voice.asr.AsrPhase
import atom.voice.asr.AsrRequest
import atom.voice.asr.collectPcm
import atom.voice.audio.Wav
import atom.voice.speech.AsrServiceId
import atom.voice.speech.MimoLanguage
import atom.voice.speech.SecretResolver
import atom.voice.speech.SpeechConfig
import atom.voice.speech.SpeechError
import atom.voice.speech.SpeechException
import atom.voice.speech.SpeechSecret
import atom.voice.speech.TtsServiceId
import atom.voice.speech.at
import atom.voice.speech.contentText
import atom.voice.speech.int
import atom.voice.speech.parseObject
import atom.voice.speech.str
import atom.voice.tts.PcmSink
import atom.voice.tts.TtsEngine
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// ------------------------------------------------------------------ MiniMax

object MiniMaxProtocol {
    fun url(config: SpeechConfig) = "${config.miniMaxSite.baseUrl}/v1/t2a_v2"

    fun body(text: String, config: SpeechConfig): String = buildJsonObject {
        put("model", config.miniMaxModel.ifBlank { "speech-2.8-turbo" })
        put("text", text)
        put("stream", false)
        putJsonObject("voice_setting") {
            put("voice_id", config.miniMaxVoice.ifBlank { "female-chengshu" })
            put("speed", config.miniMaxSpeed.coerceIn(0.5f, 2f).toDouble())
            put("vol", 1)
            put("pitch", 0)
        }
        putJsonObject("audio_setting") {
            put("sample_rate", 24_000)
            put("format", "pcm")
            put("channel", 1)
        }
        put("language_boost", "auto")
        if (config.miniMaxNormalizeNumbers) put("text_normalization_mode", "basic")
        put("output_format", "hex")
    }.toString()

    /** PCM bytes, or throws with the service's status. */
    fun parse(raw: String): ByteArray {
        val o = parseObject(raw) ?: throw SpeechException(SpeechError.Server, "Bad response")
        val status = o.int("base_resp.status_code") ?: 0
        if (status != 0) {
            val kind = when (status) {
                1004 -> SpeechError.Auth
                1002, 1039, 1008 -> SpeechError.Quota
                1001 -> SpeechError.Timeout
                else -> SpeechError.Server
            }
            throw SpeechException(kind, "${o.str("base_resp.status_msg") ?: "error"} ($status)")
        }
        val hex = o.str("data.audio")?.takeIf { it.isNotEmpty() } ?: throw SpeechException(SpeechError.Server, "No audio")
        return hexToBytes(hex)
    }
}

class MiniMaxTts(private val http: OkHttpClient) : TtsEngine {
    override val id = TtsServiceId.MiniMax
    override val maxChars = 1_500

    override suspend fun synthesize(text: String, config: SpeechConfig, secrets: SecretResolver, sink: PcmSink) {
        val key = secrets.require(SpeechSecret.MiniMaxApiKey)
        val raw = http.text(
            Request.Builder().url(MiniMaxProtocol.url(config)).header("Authorization", "Bearer $key")
                .post(MiniMaxProtocol.body(text, config).jsonBody()).build(),
        )
        sink.format(24_000)
        sink.write(MiniMaxProtocol.parse(raw))
    }
}

// ------------------------------------------------------------------ Gemini

object GeminiProtocol {
    const val URL = "https://generativelanguage.googleapis.com/v1beta/interactions"

    /** Interactions API (the documented path for the 3.8 TTS models). The reading style rides as speech metadata. */
    fun body(text: String, config: SpeechConfig): String = buildJsonObject {
        put("model", config.geminiModel.ifBlank { atom.voice.speech.GeminiTtsModels.FLASH })
        putJsonArray("input") {
            addJsonObject {
                put("type", "user_input")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                        val style = config.geminiStyle.trim().take(SpeechConfig.STYLE_MAX)
                        if (style.isNotEmpty()) {
                            putJsonArray("annotations") {
                                addJsonObject {
                                    put("type", "speech_metadata")
                                    put("style", style)
                                }
                            }
                        }
                    }
                }
            }
        }
        putJsonObject("response_format") { put("type", "audio") }
        putJsonObject("generation_config") {
            putJsonArray("speech_config") { addJsonObject { put("voice", config.geminiVoice.ifBlank { "Kore" }) } }
        }
    }.toString()

    /** Concatenates every audio block's base64 data (the unary default is a 24 kHz WAV). */
    fun audio(raw: String): ByteArray {
        val root = parseObject(raw) ?: throw SpeechException(SpeechError.Server, "Bad response")
        root.str("error.message")?.let { throw SpeechException(SpeechError.Server, it.take(160)) }
        val parts = mutableListOf<ByteArray>()
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonObject -> {
                    val data = e.str("data")
                    if (e.str("type") == "audio" && !data.isNullOrEmpty()) parts += Base64.getDecoder().decode(data)
                    else e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(root.at("steps") ?: root.at("outputs") ?: root)
        if (parts.isEmpty()) throw SpeechException(SpeechError.Server, "No audio")
        return parts.reduce { a, b -> a + b }
    }
}

class GeminiTts(private val http: OkHttpClient) : TtsEngine {
    override val id = TtsServiceId.Gemini
    override val maxChars = 1_500

    override suspend fun synthesize(text: String, config: SpeechConfig, secrets: SecretResolver, sink: PcmSink) {
        val key = secrets.require(SpeechSecret.GeminiApiKey)
        val raw = http.text(
            Request.Builder().url(GeminiProtocol.URL).header("x-goog-api-key", key)
                .post(GeminiProtocol.body(text, config).jsonBody()).build(),
        )
        writeWavOrPcm(GeminiProtocol.audio(raw), 24_000, sink)
    }
}

internal suspend fun writeWavOrPcm(bytes: ByteArray, fallbackRate: Int, sink: PcmSink) {
    val decoder = Wav.StreamDecoder(fallbackRate)
    val pcm = decoder.feed(bytes)
    sink.format(decoder.sampleRate.takeIf { it > 0 } ?: fallbackRate, decoder.channels)
    sink.write(pcm)
}

// ------------------------------------------------------------------ Xiaomi MiMo

object MimoProtocol {
    const val URL = "https://api.xiaomimimo.com/v1/chat/completions"
    const val ASR_MODEL = "mimo-v2.5-asr"
    const val TTS_MODEL = "mimo-v2.5-tts"

    fun asrBody(wav: ByteArray, language: MimoLanguage): String = buildJsonObject {
        put("model", ASR_MODEL)
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
        putJsonObject("asr_options") { put("language", language.code) }
    }.toString()

    /** Text to speak goes in an assistant message; the optional style instruction in a user message. */
    fun ttsBody(text: String, voice: String, style: String): String = buildJsonObject {
        put("model", TTS_MODEL)
        putJsonArray("messages") {
            val s = style.trim().take(SpeechConfig.STYLE_MAX)
            if (s.isNotEmpty()) addJsonObject {
                put("role", "user")
                put("content", s)
            }
            addJsonObject {
                put("role", "assistant")
                put("content", text)
            }
        }
        putJsonObject("audio") {
            put("format", "wav")
            put("voice", voice.ifBlank { "mimo_default" })
        }
    }.toString()

    fun ttsAudio(raw: String): ByteArray {
        val o = parseObject(raw) ?: throw SpeechException(SpeechError.Server, "Bad response")
        o.str("error.message")?.let { throw SpeechException(SpeechError.Server, it.take(160)) }
        val data = o.str("choices[0].message.audio.data")?.takeIf { it.isNotEmpty() } ?: throw SpeechException(SpeechError.Server, "No audio")
        return Base64.getDecoder().decode(data)
    }
}

class MimoAsr(private val http: OkHttpClient) : AsrEngine {
    override val id = AsrServiceId.Mimo

    override suspend fun recognize(request: AsrRequest): String {
        val key = request.secrets.require(SpeechSecret.MimoApiKey)
        request.onPhase(AsrPhase.Listening)
        val pcm = checkNotNull(request.audio).collectPcm(16_000 * 2 * 62)
        if (pcm.isEmpty()) return ""
        request.onPhase(AsrPhase.Recognizing)
        val raw = http.text(
            Request.Builder().url(MimoProtocol.URL).header("api-key", key)
                .post(MimoProtocol.asrBody(Wav.encode(pcm, 16_000), request.config.mimoLanguage).jsonBody()).build(),
        )
        // OpenAI-compatible response; the transcript is the assistant message content.
        return parseObject(raw)?.at("choices[0].message.content").contentText()?.trim()
            ?: throw SpeechException(SpeechError.Server, DashScopeProtocol.errorMessage(raw) ?: "No transcript")
    }
}

class MimoTts(private val http: OkHttpClient) : TtsEngine {
    override val id = TtsServiceId.Mimo
    override val maxChars = 800

    override suspend fun synthesize(text: String, config: SpeechConfig, secrets: SecretResolver, sink: PcmSink) {
        val key = secrets.require(SpeechSecret.MimoApiKey)
        val raw = http.text(
            Request.Builder().url(MimoProtocol.URL).header("api-key", key)
                .post(MimoProtocol.ttsBody(text, config.mimoVoice, config.mimoStyle).jsonBody()).build(),
        )
        writeWavOrPcm(MimoProtocol.ttsAudio(raw), 24_000, sink)
    }
}

// ------------------------------------------------------------------ DeepSeek

object DeepSeekProtocol {
    const val ASR_URL = "https://api.deepseek.com/v1/tools/asr"
    const val TTS_URL = "https://api.deepseek.com/v1/tools/tts"
    const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024

    fun ttsBody(text: String, voice: String): String = buildJsonObject {
        put("text", text)
        put("voice_id", voice.ifBlank { "stella" })
        put("format", "pcm")
        // Atom filters Markdown itself (respecting the user's toggle).
        put("filter_markdown", false)
    }.toString()

    fun asrText(raw: String): String? = parseObject(raw)?.str("text")
}

class DeepSeekAsr(private val http: OkHttpClient) : AsrEngine {
    override val id = AsrServiceId.DeepSeek

    override suspend fun recognize(request: AsrRequest): String {
        val key = request.secrets.require(SpeechSecret.DeepSeekApiKey)
        request.onPhase(AsrPhase.Listening)
        val pcm = checkNotNull(request.audio).collectPcm(DeepSeekProtocol.MAX_UPLOAD_BYTES - 64)
        if (pcm.isEmpty()) return ""
        request.onPhase(AsrPhase.Uploading)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", Wav.encode(pcm, 16_000).toRequestBody("audio/wav".toMediaType()))
            .build()
        val raw = http.text(Request.Builder().url(DeepSeekProtocol.ASR_URL).header("Authorization", "Bearer $key").post(body).build())
        return DeepSeekProtocol.asrText(raw)?.trim() ?: throw SpeechException(SpeechError.Server, "No transcript")
    }
}

class DeepSeekTts(private val http: OkHttpClient) : TtsEngine {
    override val id = TtsServiceId.DeepSeek
    override val maxChars = 1_500

    override suspend fun synthesize(text: String, config: SpeechConfig, secrets: SecretResolver, sink: PcmSink) {
        val key = secrets.require(SpeechSecret.DeepSeekApiKey)
        val response = http.send(
            Request.Builder().url(DeepSeekProtocol.TTS_URL).header("Authorization", "Bearer $key")
                .post(DeepSeekProtocol.ttsBody(text, config.deepSeekVoice).jsonBody()).build(),
        )
        var wrote = false
        var odd: Byte? = null
        response.streamBytes { bytes ->
            if (!wrote) sink.format(24_000)
            wrote = true
            // Keep 16-bit samples aligned across network chunks.
            var data = if (odd != null) byteArrayOf(odd!!) + bytes else bytes
            odd = null
            if (data.size % 2 == 1) {
                odd = data.last()
                data = data.copyOf(data.size - 1)
            }
            if (data.isNotEmpty()) sink.write(data)
        }
        if (!wrote) throw SpeechException(SpeechError.Server, "No audio")
    }
}
