package atom.voice.speech

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.speech.SpeechRecognizer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Process-wide speech state: the saved [SpeechConfig], the app-provided credential store and proofreader, and
 * one shared HTTP client (connections are pooled and closed by OkHttp when idle; nothing runs in the background).
 *
 * The app calls [install] once from `Application.onCreate`; [atom.voice.ui.VoiceFeature.onAppCreate] calls [init].
 */
object Speech {
    private const val PREFS = "atom_speech"
    private const val KEY_CONFIG = "config.v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Volatile
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    @Volatile
    var credentials: VoiceCredentials? = null
        private set

    @Volatile
    var proofreader: SpeechProofreader? = null
        private set

    @Volatile
    var wakePreferences: WakePreferences? = null
        private set

    private val mutableConfig = MutableStateFlow(SpeechConfig())
    val config: StateFlow<SpeechConfig> = mutableConfig.asStateFlow()

    /** The master "Enable speech recognition" switch. When false, hide every mic and open text input instead. */
    val recognitionEnabled = config.map { it.recognitionEnabled }

    /** Wires the app's encrypted credential store, the chat-model proofreader and (optionally) Hey Atom preferences. */
    fun install(credentials: VoiceCredentials, proofreader: SpeechProofreader? = null, wakePreferences: WakePreferences? = null) {
        this.credentials = credentials
        this.proofreader = proofreader
        this.wakePreferences = wakePreferences
    }

    /** Loads the saved settings. Cheap (one small SharedPreferences read); safe to call repeatedly. */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.getString(KEY_CONFIG, null)?.let { raw ->
            runCatching { json.decodeFromString(SpeechConfig.serializer(), raw) }.getOrNull()?.let { mutableConfig.value = it }
        }
    }

    internal val context: Context
        get() = checkNotNull(appContext) { "Speech.init(context) was not called" }

    internal val isInitialized: Boolean get() = appContext != null

    fun save(config: SpeechConfig) {
        mutableConfig.value = config
        prefs?.edit()?.putString(KEY_CONFIG, json.encodeToString(SpeechConfig.serializer(), config))?.apply()
    }

    fun update(transform: (SpeechConfig) -> SpeechConfig) = save(transform(config.value))

    /** The recognition service actually used for [config] (resolves "automatic"). */
    fun effectiveAsr(context: Context, config: SpeechConfig = this.config.value): AsrServiceId =
        config.asr ?: if (onDeviceRecognitionAvailable(context)) AsrServiceId.AndroidOnDevice else AsrServiceId.AndroidDefault

    fun onDeviceRecognitionAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    internal val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Realtime sockets: no pings from our side; the session is short and ends explicitly.
            .build()
    }

    internal fun ossCleanupPrefs(): SharedPreferences? = prefs
}
