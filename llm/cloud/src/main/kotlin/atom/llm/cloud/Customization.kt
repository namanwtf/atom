package atom.llm.cloud

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Request

/** Per-provider and per-model settings applied on top of each wire's request building. */
data class EngineOptions(
    /** User headers; protected (auth) names are dropped. */
    val headers: List<HeaderEntry> = emptyList(),
    /** Headers Atom itself adds for account sign-in; never user-editable, never filtered. */
    val authHeaders: List<HeaderEntry> = emptyList(),
    val extraBody: JsonObject? = null,
    /** Provider system prompt, placed before the agent's own instructions. */
    val systemPrompt: String? = null,
    val hostedWebSearch: Boolean = false,
    val googleSearch: Boolean = false,
    val vertexProject: String? = null,
    val vertexLocation: String? = null,
    /** When known, requests are snapped to these levels; null leaves the request untouched. */
    val reasoning: ReasoningInfo? = null,
    val contextWindow: Int? = null,
    val vision: Boolean? = null,
)

object RequestCustomizer {
    private val PROTECTED_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "x-goog-api-key",
        "api-key",
        "cookie",
        "host",
        "content-length",
        "content-type",
        "transfer-encoding",
        "chatgpt-account-id",
        "anthropic-version",
    )

    /** Body fields Atom owns; extra-body JSON cannot replace the conversation, model or streaming mode. */
    private val PROTECTED_BODY = setOf(
        "model",
        "messages",
        "input",
        "contents",
        "system",
        "systemInstruction",
        "instructions",
        "stream",
        "tools",
    )

    fun isProtectedHeader(name: String): Boolean = name.trim().lowercase() in PROTECTED_HEADERS

    fun isValidHeaderName(name: String): Boolean =
        name.isNotBlank() && name.all { it.code in 0x21..0x7E && it != ':' }

    /** Valid, unprotected user headers; later entries with the same name win. */
    fun userHeaders(headers: List<HeaderEntry>): List<HeaderEntry> =
        headers.filter { isValidHeaderName(it.name) && !isProtectedHeader(it.name) && it.value.none { c -> c == '\n' || c == '\r' } }
            .associateBy { it.name.trim().lowercase() }
            .values
            .map { HeaderEntry(it.name.trim(), it.value) }

    fun applyHeaders(builder: Request.Builder, options: EngineOptions): Request.Builder {
        for (header in userHeaders(options.headers)) builder.header(header.name, header.value)
        for (header in options.authHeaders) builder.header(header.name, header.value)
        return builder
    }

    /**
     * Merges [extra] into [base]. Objects merge recursively; anything else replaces. Protected top-level
     * fields keep Atom's values.
     */
    fun mergeBody(base: JsonObject, extra: JsonObject?): JsonObject {
        if (extra == null || extra.isEmpty()) return base
        val out = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in extra) {
            if (key in PROTECTED_BODY) continue
            out[key] = deepMerge(out[key], value)
        }
        return JsonObject(out)
    }

    private fun deepMerge(current: JsonElement?, incoming: JsonElement): JsonElement {
        if (current is JsonObject && incoming is JsonObject) {
            val out = LinkedHashMap<String, JsonElement>(current)
            for ((k, v) in incoming) out[k] = deepMerge(out[k], v)
            return JsonObject(out)
        }
        return incoming
    }

    /** Parses the extra-body text: null when blank, failure when it is not a JSON object. */
    fun parseExtraBody(text: String): Result<JsonObject?> {
        if (text.isBlank()) return Result.success(null)
        val parsed = parseJsonOrNull(text.trim()) as? JsonObject
            ?: return Result.failure(IllegalArgumentException("Extra body must be a JSON object"))
        return Result.success(parsed)
    }
}
