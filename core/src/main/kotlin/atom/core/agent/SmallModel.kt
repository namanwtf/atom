package atom.core.agent

import java.util.Locale

/**
 * Very small on-device models (under about 3 billion parameters) chat well but usually cannot plan and carry out a
 * multi-step agent task: open the browser, search, read a page, then answer. They tend to answer from memory or
 * lose the thread instead. [shouldWarn] decides when to tell the user so, once, without blocking anything.
 */
object SmallModel {
    /** Below this many billion parameters a model counts as small. */
    const val SMALL_BILLIONS = 3.0

    /** Without a size in the name: a 4-bit file of a 3B model is about 1.9 GB. */
    private const val SMALL_FILE_BYTES = 1_800_000_000L

    private val SIZE_IN_NAME = Regex("""(?i)(?<![a-z0-9.])e?(\d+(?:\.\d+)?)\s?b(?![a-z])""")

    /** Parameters in billions from a model name ("Qwen3 0.6B" → 0.6, "Gemma 4 E2B" → 2.0), or null when it has none. */
    fun billionsFromName(name: String): Double? =
        SIZE_IN_NAME.findAll(name).mapNotNull { it.groupValues[1].toDoubleOrNull() }.lastOrNull()

    /** True for a model under [SMALL_BILLIONS], judged by its name, else by its file size (0 = unknown, not small). */
    fun isSmall(name: String, sizeBytes: Long): Boolean {
        billionsFromName(name)?.let { return it < SMALL_BILLIONS }
        return sizeBytes in 1 until SMALL_FILE_BYTES
    }

    private val AGENT_WORDS = listOf(
        // the agent browser and the web
        "browser", "browse", "website", "web page", "webpage", "go to", "search for", "search the", "look up",
        "google", "news", "http", "https", "www",
        // operating the phone
        "open", "tap", "click", "scroll", "swipe", "turn on", "turn off", "switch on", "switch off",
        "enable", "disable", "settings", "set an alarm", "set a timer", "remind me", "send",
        "install", "screen", "app", "apps",
    ).map { " $it " }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Whether [prompt] asks for something done on the phone or the web rather than a plain chat answer. */
    fun looksLikeAgentTask(prompt: String): Boolean {
        val text = " " + prompt.lowercase(Locale.ROOT).replace(NON_WORD, " ") + " "
        return AGENT_WORDS.any { it in text }
    }

    /** Warn when a small on-device model gets an agent task and this conversation has not been told yet. */
    fun shouldWarn(onDevice: Boolean, small: Boolean, prompt: String, alreadyWarned: Boolean): Boolean =
        onDevice && small && !alreadyWarned && looksLikeAgentTask(prompt)
}
