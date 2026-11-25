package atom.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class Role { System, User, Assistant, Tool }

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class Image(val mimeType: String, val base64: String) : ContentPart
}

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/**
 * Search suggestions a provider attaches to a grounded answer (Gemini "Google Search suggestions"): the queries
 * the model searched for and, when the provider sends one, the ready-made HTML/CSS chip ([renderedContent]) that
 * the provider's terms ask apps to show under the answer, unmodified. Each query opens a web search when tapped.
 */
@Serializable
data class SearchSuggestions(
    val queries: List<String> = emptyList(),
    val renderedContent: String? = null,
) {
    val isEmpty: Boolean get() = queries.isEmpty() && renderedContent.isNullOrBlank()

    /** The web search link for [query], for UIs that draw their own chips. */
    fun searchUrl(query: String): String = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
}

data class ChatMessage(
    val role: Role,
    val parts: List<ContentPart>,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
) {
    val text: String get() = parts.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    companion object {
        fun system(text: String) = ChatMessage(Role.System, listOf(ContentPart.Text(text)))
        fun user(text: String) = ChatMessage(Role.User, listOf(ContentPart.Text(text)))
        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()) =
            ChatMessage(Role.Assistant, if (text.isEmpty()) emptyList() else listOf(ContentPart.Text(text)), toolCalls)
        fun toolResult(call: ToolCall, parts: List<ContentPart>) =
            ChatMessage(Role.Tool, parts, toolCallId = call.id, toolName = call.name)
    }
}

/** [parameters] is a JSON Schema object describing the tool's arguments. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** Requested thinking depth. A null effort means "model default"; [Off] asks the model not to think. */
enum class ReasoningEffort { Off, Low, Medium, High, XHigh, Max }

data class LlmRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxOutputTokens: Int = 2048,
    val temperature: Double? = null,
    val reasoning: ReasoningEffort? = null,
)

enum class FinishReason { Stop, ToolCalls, Length, Cancelled }

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ReasoningDelta(val text: String) : LlmEvent
    data class ToolCallReady(val call: ToolCall) : LlmEvent
    /** Cache figures are 0 when the provider does not report them. */
    data class Usage(
        val inputTokens: Int,
        val outputTokens: Int,
        val cacheReadTokens: Int = 0,
        val cacheWriteTokens: Int = 0,
    ) : LlmEvent
    data class Finished(val reason: FinishReason) : LlmEvent

    /** Grounding search suggestions for this response (at most one per response, before [Finished]). */
    data class Suggestions(val suggestions: SearchSuggestions) : LlmEvent
}

data class EngineCapabilities(
    val nativeToolCalls: Boolean,
    val vision: Boolean,
    val contextWindow: Int,
    val onDevice: Boolean,
)

/**
 * One model backend. [stream] must be cold, cancellable, and end with exactly one [LlmEvent.Finished]
 * on success; failures surface as [LlmException].
 */
interface LlmEngine {
    val id: String
    val capabilities: EngineCapabilities
    fun stream(request: LlmRequest): Flow<LlmEvent>
}

/** Engines holding large native resources (on-device models) implement this so the runtime can free them. */
interface Releasable {
    suspend fun release()
}

class LlmException(
    message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
