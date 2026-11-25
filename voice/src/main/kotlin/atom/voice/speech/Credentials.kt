package atom.voice.speech

/** Every secret the speech services need. Stored encrypted by the app, separate from chat keys, never backed up. */
enum class SpeechSecret {
    QwenApiKey,
    DoubaoApiKey,
    DoubaoAccessToken,
    MimoApiKey,
    DeepSeekApiKey,
    GeminiApiKey,
    MiniMaxApiKey,
    OssAccessKeyId,
    OssAccessKeySecret,
    ;

    /** Model-provider id whose chat key is reused when this secret is blank (DeepSeek, Qwen, Gemini, MiniMax). */
    val providerFallback: String?
        get() = when (this) {
            DeepSeekApiKey -> "deepseek"
            QwenApiKey -> "qwen"
            GeminiApiKey -> "gemini"
            MiniMaxApiKey -> "minimax"
            else -> null
        }
}

/** Thrown by [VoiceCredentials.get] when a stored secret exists but can no longer be decrypted. */
class CredentialUnreadableException(val secret: SpeechSecret) : Exception("Stored credential cannot be decrypted")

/**
 * Encrypted credential storage, implemented by the app on top of its Keystore-backed vault. Implementations must
 * encrypt at rest, keep these out of backups, never log values, and throw [CredentialUnreadableException] (instead
 * of returning null) when a stored value cannot be decrypted, so the user is asked to re-enter it.
 */
interface VoiceCredentials {
    suspend fun get(secret: SpeechSecret): String?

    /** Blank or null removes the secret. */
    suspend fun set(secret: SpeechSecret, value: String?)

    /** Presence only (no decryption), for "Saved" badges. */
    suspend fun has(secret: SpeechSecret): Boolean

    /** The chat model-provider key for [providerId] (e.g. "deepseek"), or null. */
    suspend fun providerKey(providerId: String): String?
}

/**
 * One cautious proofreading pass by the current chat model (implemented by the app). Return the corrected text;
 * throw or return null on any failure. The voice module enforces the time cap and keeps the raw text on failure.
 */
fun interface SpeechProofreader {
    suspend fun proofread(instructions: String, text: String): String?
}

/** "Hey Atom" preferences when the app owns them (it re-applies them on start). */
data class WakePrefs(val enabled: Boolean, val screenOff: Boolean, val whileCharging: Boolean)

interface WakePreferences {
    val current: kotlinx.coroutines.flow.Flow<WakePrefs>
    suspend fun set(prefs: WakePrefs)
}

/** Resolves secrets for one request: unsaved form values first (Test / Preview), then the stored ones, then provider keys. */
class SecretResolver(
    private val credentials: VoiceCredentials?,
    private val overrides: Map<SpeechSecret, String> = emptyMap(),
) {
    /** The secret or null when none is configured. Throws [CredentialUnreadableException]. */
    suspend fun find(secret: SpeechSecret): String? {
        overrides[secret]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val stored = credentials?.get(secret)?.trim()?.takeIf { it.isNotEmpty() }
        if (stored != null) return stored
        val provider = secret.providerFallback ?: return null
        return credentials?.providerKey(provider)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The secret, or a [SpeechException] of kind [SpeechError.MissingKey]. */
    suspend fun require(secret: SpeechSecret): String =
        try {
            find(secret) ?: throw SpeechException(SpeechError.MissingKey, secret.name)
        } catch (e: CredentialUnreadableException) {
            throw SpeechException(SpeechError.CredentialUnreadable, secret.name)
        }
}
