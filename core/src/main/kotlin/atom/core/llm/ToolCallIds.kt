package atom.core.llm

import java.security.MessageDigest
import kotlin.random.Random

/**
 * Tool-call ids every provider accepts. The strictest is Mistral: exactly nine characters from `[A-Za-z0-9]`.
 * OpenAI-compatible APIs take up to 40 characters, Anthropic `[A-Za-z0-9_-]`; nine letters and digits pass them all.
 */
object ToolCallIds {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    const val LENGTH = 9
    private val STRICT = Regex("[A-Za-z0-9]{$LENGTH}")
    private val PERMISSIVE = Regex("[A-Za-z0-9_-]{1,40}")

    /** A new id the app makes up itself (no provider made the call), different from every id in [taken]. */
    fun fresh(taken: Set<String> = emptySet(), random: Random = Random.Default): String {
        while (true) {
            val id = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
            if (id !in taken) return id
        }
    }

    /** True for an id in the strict nine-character form. */
    fun isStrict(id: String): Boolean = STRICT.matches(id)

    /**
     * [id] in the strict nine-character form: unchanged when it already is, else derived from it (the same input
     * always gives the same output, so a call and its result still match). For providers that insist on the form.
     */
    fun strict(id: String): String = if (isStrict(id)) id else derive(id)

    /** [id] unchanged when it is 1-40 of `[A-Za-z0-9_-]` (what OpenAI-style APIs take), else [strict]. */
    fun safe(id: String): String = if (PERMISSIVE.matches(id)) id else derive(id)

    private fun derive(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return String(CharArray(LENGTH) { i -> ALPHABET[(digest[i].toInt() and 0xFF) % ALPHABET.length] })
    }
}
