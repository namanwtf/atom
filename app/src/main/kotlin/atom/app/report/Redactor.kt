package atom.app.report

/**
 * Cleans text before it goes into a problem report (pure, unit-tested). [secrets] removes anything that looks like a
 * key, token or password; [personal] also removes email addresses, phone numbers, web paths and file paths, for text
 * Atom wrote on its own (errors, its log, crash details) rather than text the person chose to include.
 */
object Redactor {
    const val REDACTED = "[redacted]"

    /** A value after a key-like name: `api_key=...`, `"token": "..."`, `Authorization: Bearer ...`. */
    private val NAMED = Regex(
        "(?i)\\b(api[_-]?key|apikey|x-api-key|x-goog-api-key|access[_-]?token|refresh[_-]?token|id[_-]?token|auth[_-]?token|" +
            "token|secret|client[_-]?secret|password|passwd|pwd|authorization|proxy-authorization|cookie|set-cookie|" +
            "session[_-]?id|private[_-]?key)\\b(\\s*[\"']?\\s*[:=]\\s*[\"']?\\s*)(?:(?:bearer|basic|token)\\s+)?([^\\s\"'&,;}<>]+)",
    )
    private val SCHEME = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val QUERY = Regex("(?i)([?&](?:key|api_key|apikey|token|access_token|auth|sig|signature|code|secret|password)=)[^&#\\s\"']+")
    private val PEM = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)")

    /** Well-known key and token shapes. */
    private val SHAPES = listOf(
        Regex("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}"),
        Regex("\\bsk-[A-Za-z0-9_-]{12,}"),
        Regex("\\b(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{12,}"),
        Regex("\\bAIza[0-9A-Za-z_-]{20,}"),
        Regex("\\bya29\\.[A-Za-z0-9_-]{12,}"),
        Regex("\\b1//[A-Za-z0-9_-]{20,}"),
        Regex("\\b(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{16,}"),
        Regex("\\bglpat-[A-Za-z0-9_-]{16,}"),
        Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}"),
        Regex("\\bhf_[A-Za-z0-9]{16,}"),
        Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
    )

    /** Any long run of letters and digits mixing both: unknown key formats, hashes, ids. */
    private val LONG_TOKEN = Regex("(?<![A-Za-z0-9_+/=-])(?=[A-Za-z0-9_+/=-]*[0-9])(?=[A-Za-z0-9_+/=-]*[A-Za-z])[A-Za-z0-9_+/=-]{28,}")

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val PHONE = Regex("(?<![\\w/:.#])\\+?\\d[\\d ()-]{6,}\\d(?![\\w/:.])")
    private val URL = Regex("(?i)\\b(https?|wss?)://([^\\s/?#\"'<>]+)([^\\s\"'<>]*)")
    private val USER_FILES = Regex("(/(?:storage/emulated/\\d+|sdcard|storage/[0-9A-F]{4}-[0-9A-F]{4}|data/user/\\d+/[^/\\s]+/files/workspace))/[^\\s\"'<>]+")

    /** Removes only [known] values: the person's saved keys and tokens, matched exactly. */
    fun exact(text: String, known: Collection<String>): String {
        var t = text
        known.asSequence().map { it.trim() }.filter { it.length >= MIN_KNOWN }.distinct().sortedByDescending { it.length }
            .forEach { t = t.replace(it, REDACTED) }
        return t
    }

    /** Removes [known] values (the person's saved keys and tokens, matched exactly) and every key-like string. */
    fun secrets(text: String, known: Collection<String> = emptyList()): String {
        var t = exact(text, known)
        t = PEM.replace(t, REDACTED)
        t = NAMED.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + REDACTED }
        t = SCHEME.replace(t) { m -> m.groupValues[1] + " " + REDACTED }
        t = QUERY.replace(t) { m -> m.groupValues[1] + REDACTED }
        SHAPES.forEach { t = it.replace(t, REDACTED) }
        t = LONG_TOKEN.replace(t, REDACTED)
        return t
    }

    /** [secrets] plus personal data, for text Atom produced itself. */
    fun personal(text: String, known: Collection<String> = emptyList()): String {
        var t = secrets(text, known)
        t = EMAIL.replace(t, "[email]")
        t = URL.replace(t) { m -> m.groupValues[1] + "://" + m.groupValues[2] + if (m.groupValues[3].length > 1) "/[path]" else m.groupValues[3] }
        t = USER_FILES.replace(t) { m -> m.groupValues[1] + "/[path]" }
        t = PHONE.replace(t) { m -> if (m.value.count(Char::isDigit) in 9..15) "[number]" else m.value }
        return t
    }

    /** Saved values shorter than this are not matched exactly: they would hit ordinary words. */
    const val MIN_KNOWN = 8
}
