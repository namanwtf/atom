package atom.voice.speech

/** What went wrong, in user terms. The UI maps each kind to a short message plus a link to voice settings. */
enum class SpeechError {
    NoPermission,
    Disabled,
    MissingKey,
    CredentialUnreadable,
    MissingStorage,
    Auth,
    Quota,
    Network,
    Timeout,
    NoSpeech,
    TooLong,
    Busy,
    Unsupported,
    Server,
}

class SpeechException(val kind: SpeechError, detail: String? = null, cause: Throwable? = null) :
    Exception(detail ?: kind.name, cause) {
    /** Short, safe detail (never contains keys or audio). */
    val detail: String? = detail
}

internal object HttpErrors {
    /** Maps an HTTP status from a speech service to an error kind. */
    fun kind(code: Int): SpeechError = when (code) {
        401, 403 -> SpeechError.Auth
        402, 429 -> SpeechError.Quota
        408, 504 -> SpeechError.Timeout
        413 -> SpeechError.TooLong
        503 -> SpeechError.Busy
        else -> SpeechError.Server
    }

    /** First 160 chars of a service error body, stripped of newlines. Bodies never echo the key back. */
    fun snippet(body: String?): String? = body?.replace('\n', ' ')?.trim()?.take(160)?.takeIf { it.isNotEmpty() }
}
