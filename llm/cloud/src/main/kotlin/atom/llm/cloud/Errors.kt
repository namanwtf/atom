package atom.llm.cloud

import atom.core.llm.LlmException
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Response

internal fun isRetryableStatus(code: Int): Boolean =
    code == 408 || code == 409 || code == 425 || code == 429 || code >= 500

/**
 * A Retry-After header in milliseconds: delay-seconds ("20") or an HTTP date. Null when absent, unreadable or in the
 * past. The agent bounds what it actually waits.
 */
internal fun retryAfterMs(header: String?, now: Long = System.currentTimeMillis()): Long? {
    val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { return if (it > 0) it * 1_000 else null }
    value.toDoubleOrNull()?.let { return if (it > 0) (it * 1_000).toLong() else null }
    val at = runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrNull() ?: return null
    return (at - now).takeIf { it > 0 }
}

/** Builds [LlmException]s for one provider, scrubbing the API key from anything echoed back. */
internal class ErrorMapper(private val providerName: String, private val secret: String) {

    fun http(code: Int, body: String, retryAfter: String? = null): LlmException {
        val detail = errorMessage(body) ?: body.trim().take(MAX_DETAIL).ifEmpty { "no details" }
        return LlmException(
            message = scrub("$providerName request failed (HTTP $code): $detail"),
            retryable = isRetryableStatus(code),
            statusCode = code,
            retryAfterMs = if (code == 429 || code == 503) retryAfterMs(retryAfter) else null,
        )
    }

    fun network(e: IOException): LlmException =
        LlmException(
            message = scrub("$providerName network error: ${e.message ?: e::class.simpleName}"),
            retryable = true,
            cause = e,
        )

    fun stream(detail: String, retryable: Boolean): LlmException =
        LlmException(scrub("$providerName: $detail"), retryable)

    /**
     * Maps an event-source failure. Runs on OkHttp's thread, so it must never throw: once a stream has opened,
     * OkHttp passes the response with its body replaced by one that throws IllegalStateException on read, and
     * that is exactly what Stop (or a dropped connection) produces mid-answer.
     */
    fun failure(t: Throwable?, response: Response?): LlmException {
        if (response != null) {
            val body = try {
                response.body.string()
            } catch (e: Exception) {
                null
            }
            if (!response.isSuccessful) return http(response.code, body.orEmpty(), response.header("Retry-After"))
            // A readable body on a 2xx means the server answered something other than a stream.
            if (body != null && t !is IOException) {
                val detail = errorMessage(body) ?: "unexpected response type ${response.header("Content-Type")}"
                return LlmException(scrub("$providerName: $detail"), retryable = false, statusCode = response.code)
            }
        }
        return when (t) {
            is LlmException -> t
            is IOException -> network(t)
            else -> LlmException(scrub("$providerName: ${t?.message ?: "request failed"}"), retryable = false, cause = t)
        }
    }

    fun scrub(text: String): String =
        if (secret.length >= MIN_SECRET) text.replace(secret, "***") else text

    private companion object {
        const val MAX_DETAIL = 300
        const val MIN_SECRET = 6
    }
}

/** Pulls the human-readable message out of the error envelopes used by OpenAI, Anthropic, Gemini and most compatibles. */
internal fun errorMessage(body: String): String? {
    val root = parseJsonOrNull(body) ?: return null
    return messageIn(if (root is JsonArray) root.firstOrNull() ?: return null else root)
}

internal fun messageIn(element: JsonElement): String? = when (element) {
    is JsonPrimitive -> element.takeIf { it.isString }?.content
    is JsonObject -> element["error"]?.let(::messageIn)
        ?: element.str("message")
        ?: element.str("detail")
        ?: element.str("msg")
    else -> null
}
