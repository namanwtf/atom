package atom.core.llm

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class LlmFailure {
    BadKey,
    Forbidden,
    NotFound,
    RateLimited,
    Quota,
    Timeout,
    ConnectionLost,
    ContentFilter,
    OutputLimit,
    ContextOverflow,
    /** The model's output turned into a loop of repeated symbols ([DegenerateOutputGuard]). */
    Unreadable,
    Server,
    Unknown,
}

/** Turns provider and engine failures into plain words. Provider detail is kept only as a short suffix. */
object LlmErrors {
    fun classify(error: Throwable): LlmFailure {
        val status = (error as? LlmException)?.statusCode
        val text = generateSequence(error) { it.cause }.take(4).mapNotNull { it.message }.joinToString(" ").lowercase()
        val causes = generateSequence(error) { it.cause }.take(4).toList()
        return when {
            DegenerateOutputGuard.UNREADABLE_MESSAGE.lowercase() in text -> LlmFailure.Unreadable
            CONTEXT.any { it in text } || status == 413 -> LlmFailure.ContextOverflow
            QUOTA.any { it in text } || status == 402 -> LlmFailure.Quota
            status == 429 || RATE.any { it in text } -> LlmFailure.RateLimited
            status == 401 || AUTH.any { it in text } -> LlmFailure.BadKey
            status == 403 || FORBIDDEN.any { it in text } -> LlmFailure.Forbidden
            status == 404 || NOT_FOUND.any { it in text } -> LlmFailure.NotFound
            FILTER.any { it in text } -> LlmFailure.ContentFilter
            OUTPUT.any { it in text } -> LlmFailure.OutputLimit
            status == 408 || status == 504 || causes.any { it is SocketTimeoutException } || TIMEOUT.any { it in text } ->
                LlmFailure.Timeout
            causes.any { it is UnknownHostException || (it is IOException && it !is SocketTimeoutException) } ||
                CONNECTION.any { it in text } -> LlmFailure.ConnectionLost
            status != null && status >= 500 -> LlmFailure.Server
            else -> LlmFailure.Unknown
        }
    }

    fun explain(error: Throwable): String {
        val failure = classify(error)
        val status = (error as? LlmException)?.statusCode
        return explain(failure, status, error.message)
    }

    fun explain(failure: LlmFailure, status: Int? = null, detail: String? = null): String = when (failure) {
        LlmFailure.BadKey -> "The API key was rejected (401). Check the key for this provider in Models."
        LlmFailure.Forbidden -> "The provider refused access (403). The key may not be allowed to use this model, or your region is not supported."
        LlmFailure.NotFound -> "The model or address was not found (404). Check the model name and the base URL."
        LlmFailure.RateLimited -> "The provider is rate limiting requests (429). Wait a moment, then try again."
        LlmFailure.Quota -> "Your quota or credit with this provider is used up. Check billing on the provider's site."
        LlmFailure.Timeout -> "The model took too long to respond. Try again, or pick a faster model."
        LlmFailure.ConnectionLost -> "The connection to the model was lost. Check the network, then try again."
        LlmFailure.ContentFilter -> "The provider's content filter blocked this response. Rephrase the request and try again."
        LlmFailure.OutputLimit -> "The answer hit the model's output limit before it finished."
        LlmFailure.ContextOverflow -> "This conversation no longer fits the model's context window. Compact the context or start a new conversation."
        LlmFailure.Unreadable -> DegenerateOutputGuard.UNREADABLE_MESSAGE
        LlmFailure.Server -> "The provider had a server error${status?.let { " (HTTP $it)" } ?: ""}. Try again shortly."
        LlmFailure.Unknown -> detail?.trim()?.take(300)?.ifEmpty { null } ?: "The model request failed."
    }

    private val CONTEXT = listOf(
        "context length", "context_length", "maximum context", "context window", "too many tokens", "prompt is too long",
        "input is too long", "reduce the length", "token limit exceeded", "exceeds the context", "context_window",
    )
    private val QUOTA = listOf("quota", "billing", "insufficient_balance", "insufficient balance", "credit balance", "payment required", "out of credits")
    private val RATE = listOf("rate limit", "rate_limit", "too many requests", "ratelimit")
    private val AUTH = listOf("invalid api key", "invalid_api_key", "incorrect api key", "unauthorized", "authentication", "invalid x-api-key", "api key not valid")
    private val FORBIDDEN = listOf("forbidden", "permission denied", "permission_denied", "not allowed to access")
    private val NOT_FOUND = listOf("model not found", "model_not_found", "does not exist", "no such model", "not_found_error", "unknown model")
    private val FILTER = listOf("content filter", "content_filter", "content policy", "content_policy", "safety system", "blocked by safety", "moderation", "flagged", "recitation", "prohibited_content")
    private val OUTPUT = listOf("max_tokens", "max output tokens", "output limit", "maximum output")
    private val TIMEOUT = listOf("timeout", "timed out", "deadline exceeded")
    private val CONNECTION = listOf(
        "network error", "connection reset", "connection refused", "connection closed", "failed to connect",
        "unable to resolve host", "broken pipe", "unexpected end of stream", "stream was reset", "connection abort", "software caused connection abort",
    )
}
