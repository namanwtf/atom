package atom.voice.speech

import kotlinx.serialization.Serializable

/** Speech recognition services. Exactly one is active. */
@Serializable
enum class AsrServiceId(
    /** Shows a live preview while the user talks (otherwise only progress states). */
    val streaming: Boolean,
    val cloud: Boolean,
    /** Longest single recording, in milliseconds. */
    val maxRecordingMs: Long = 60_000,
) {
    /** Android's on-device recognizer: free, private, power-cheap. Default where the device supports it. */
    AndroidOnDevice(streaming = true, cloud = false),
    /** The device's default recognizer (may use the network, e.g. Google). */
    AndroidDefault(streaming = true, cloud = false),
    QwenRealtime(streaming = true, cloud = true),
    QwenShort(streaming = false, cloud = true),
    QwenFile(streaming = false, cloud = true),
    DoubaoStreaming(streaming = true, cloud = true),
    Mimo(streaming = false, cloud = true),
    DeepSeek(streaming = false, cloud = true, maxRecordingMs = 120_000),
}

/** Spoken-answer services. [Off] still lets the user tap "Read aloud" (it then uses the system voice). */
@Serializable
enum class TtsServiceId { Off, System, Qwen, Doubao, Gemini, MiniMax, Mimo, DeepSeek }

@Serializable
enum class QwenRegion(val host: String) {
    Beijing("dashscope.aliyuncs.com"),
    Singapore("dashscope-intl.aliyuncs.com"),
}

@Serializable
enum class MimoLanguage(val code: String) { Auto("auto"), Chinese("zh"), English("en") }

@Serializable
enum class MiniMaxSite(val baseUrl: String) {
    Overseas("https://api.minimax.io"),
    China("https://api.minimax.cn"),
}

/** How long a pause in speech must last before a streaming recognition sends by itself. */
@Serializable
enum class AutoSendPause(val millis: Long?) {
    OneSecond(1_000),
    OneAndHalf(1_500),
    Two(2_000),
    Three(3_000),
    Never(null),
}

/**
 * Every non-secret speech setting. Secrets (API keys, OSS AccessKey) never live here: they go through
 * [VoiceCredentials], which encrypts them and keeps them out of backups.
 */
@Serializable
data class SpeechConfig(
    // ---- Recognition
    val recognitionEnabled: Boolean = true,
    /** Null means "automatic": on-device where available, otherwise the default Android recognizer. */
    val asr: AsrServiceId? = null,
    /** BCP-47 / service language code; blank means auto-detect. */
    val language: String = "",
    val qwenRegion: QwenRegion = QwenRegion.Singapore,
    /** Optional override of the DashScope base, e.g. a workspace domain. Blank uses the region's host. */
    val qwenBaseUrl: String = "",
    val doubaoResourceId: String = DoubaoDefaults.ASR_RESOURCE_ID,
    /** Legacy console App ID; when set, the legacy App ID + Access Token pair is used instead of the API key. */
    val doubaoAppId: String = "",
    val mimoLanguage: MimoLanguage = MimoLanguage.Auto,
    val smartCorrection: Boolean = false,

    // ---- Recording storage (Qwen file transcription)
    val ossRegion: String = "cn-hangzhou",
    /** Optional custom endpoint host, e.g. oss-cn-hangzhou-internal.aliyuncs.com. Blank derives it from the region. */
    val ossEndpoint: String = "",
    val ossBucket: String = "",
    val ossPrefix: String = "atom-voice/",

    // ---- Spoken answers
    val tts: TtsServiceId = TtsServiceId.Off,
    val qwenVoice: String = "Cherry",
    val doubaoVoice: String = "zh_female_vv_uranus_bigtts",
    val doubaoTtsResourceId: String = DoubaoDefaults.TTS_RESOURCE_ID,
    val geminiModel: String = GeminiTtsModels.FLASH,
    val geminiVoice: String = "Kore",
    val geminiStyle: String = "",
    val miniMaxModel: String = "speech-2.8-turbo",
    val miniMaxVoice: String = "female-chengshu",
    val miniMaxSpeed: Float = 1.0f,
    val miniMaxNormalizeNumbers: Boolean = true,
    val miniMaxSite: MiniMaxSite = MiniMaxSite.Overseas,
    val mimoVoice: String = "mimo_default",
    val mimoStyle: String = "",
    val deepSeekVoice: String = "stella",
    val filterMarkdown: Boolean = true,
    val previewText: String = DEFAULT_PREVIEW,

    // ---- Assistant & chat
    /** Chat "More" menu: read each completed turn's final answer aloud. */
    val autoReadAloud: Boolean = false,
    /** Assistant overlay: speak final answers to voice questions while the overlay is visible. */
    val overlaySpeakAnswers: Boolean = false,
    val autoSendPause: AutoSendPause = AutoSendPause.Two,

    // ---- Audio chat
    val audioChatHandsFree: Boolean = true,
    val audioChatSpeakAnswers: Boolean = true,
    val audioChatBargeIn: Boolean = true,

    // ---- Hey Atom (used only when the app does not provide its own WakePreferences)
    val wakeEnabled: Boolean = false,
    val wakeScreenOff: Boolean = false,
    val wakeWhileCharging: Boolean = true,
) {
    /** True when a spoken-answer service is configured (the auto read-aloud toggle is hidden otherwise). */
    val ttsConfigured: Boolean get() = tts != TtsServiceId.Off

    fun voiceFor(service: TtsServiceId): String = when (service) {
        TtsServiceId.Qwen -> qwenVoice
        TtsServiceId.Doubao -> doubaoVoice
        TtsServiceId.Gemini -> geminiVoice
        TtsServiceId.MiniMax -> miniMaxVoice
        TtsServiceId.Mimo -> mimoVoice
        TtsServiceId.DeepSeek -> deepSeekVoice
        TtsServiceId.Off, TtsServiceId.System -> ""
    }

    fun withVoice(service: TtsServiceId, voice: String): SpeechConfig = when (service) {
        TtsServiceId.Qwen -> copy(qwenVoice = voice)
        TtsServiceId.Doubao -> copy(doubaoVoice = voice)
        TtsServiceId.Gemini -> copy(geminiVoice = voice)
        TtsServiceId.MiniMax -> copy(miniMaxVoice = voice)
        TtsServiceId.Mimo -> copy(mimoVoice = voice)
        TtsServiceId.DeepSeek -> copy(deepSeekVoice = voice)
        TtsServiceId.Off, TtsServiceId.System -> this
    }

    /** Base URL for DashScope HTTP calls, without a trailing slash. */
    val dashScopeBase: String
        get() = qwenBaseUrl.trim().trimEnd('/').ifEmpty { "https://${qwenRegion.host}" }

    /** WebSocket base for DashScope realtime, derived from [dashScopeBase]. */
    val dashScopeWsBase: String
        get() = dashScopeBase.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

    companion object {
        /** A short self-introduction, then a mock reminder, so a voice can be heard on a typical answer. */
        const val DEFAULT_PREVIEW = "Hi, I'm Atom, the assistant on your phone. Here is a reminder: your project review starts " +
            "at 3:30 this afternoon in meeting room 2. The slides are in your inbox, and I can read the agenda to you."
        const val PREVIEW_MAX = 500
        const val STYLE_MAX = 500
    }
}

object DoubaoDefaults {
    /** Streaming recognition 2.0, billed by duration. */
    const val ASR_RESOURCE_ID = "volc.seedasr.sauc.duration"
    /** Speech synthesis 2.0 voices (the "uranus" family). */
    const val TTS_RESOURCE_ID = "seed-tts-2.0"
}

object GeminiTtsModels {
    const val FLASH = "gemini-3.8-flash-tts"
    const val FLASH_LITE = "gemini-3.8-flash-lite-tts"
}
