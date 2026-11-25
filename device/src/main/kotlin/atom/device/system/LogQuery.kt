package atom.device.system

import atom.device.control.shQuote

/** Filters for reading the system log; [command] turns them into one logcat pipeline. */
data class LogQuery(
    val pid: Int? = null,
    val tag: String? = null,
    val level: Char? = null,
    val sinceEpochMs: Long? = null,
    val text: String? = null,
    val maxLines: Int = 200,
) {
    fun command(): String = buildString {
        append("logcat -d -v threadtime")
        sinceEpochMs?.let { append(" -T ").append(shQuote(String.format(java.util.Locale.US, "%d.%03d", it / 1000, it % 1000))) }
        pid?.let { append(" --pid=").append(it) }
        val lvl = level?.uppercaseChar()?.takeIf { it in LEVELS } ?: 'V'
        if (tag != null) {
            append(' ').append(shQuote("$tag:$lvl")).append(" '*:S'")
        } else if (lvl != 'V') {
            append(' ').append(shQuote("*:$lvl"))
        }
        if (!text.isNullOrBlank()) append(" | grep -i -F -e ").append(shQuote(text))
        append(" | tail -n ").append(maxLines.coerceIn(1, 500))
    }

    companion object {
        const val LEVELS = "VDIWEF"

        fun level(name: String?): Char? = when (name?.trim()?.lowercase()) {
            null, "" -> null
            "verbose", "v" -> 'V'
            "debug", "d" -> 'D'
            "info", "i" -> 'I'
            "warn", "warning", "w" -> 'W'
            "error", "e" -> 'E'
            "fatal", "assert", "f" -> 'F'
            else -> null
        }
    }
}
