package atom.core.agent

import atom.core.llm.ChatMessage
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort

/** Writes a short conversation title after the first turn, in the user's language. */
object TitleGenerator {
    const val MAX_CHARS = 60

    /**
     * [thinks]: the model will think before it writes (forced reasoning, or a level was chosen); it then gets room for
     * that, or the title never comes.
     */
    fun request(
        model: String,
        userText: String,
        answerText: String,
        reasoning: ReasoningEffort? = null,
        thinks: Boolean = reasoning != null && reasoning != ReasoningEffort.Off,
    ): LlmRequest =
        LlmRequest(
            model = model,
            messages = listOf(
                ChatMessage.system(INSTRUCTIONS),
                ChatMessage.user(
                    "User message:\n" + userText.take(1_500) +
                        "\n\nAssistant reply:\n" + answerText.take(800) +
                        "\n\nTitle:",
                ),
            ),
            maxOutputTokens = if (thinks && reasoning != ReasoningEffort.Off) 400 else 48,
            temperature = 0.3,
            reasoning = reasoning,
        )

    suspend fun generate(engine: LlmEngine, request: LlmRequest): String? {
        val text = StringBuilder()
        engine.stream(request).collect { if (it is LlmEvent.TextDelta) text.append(it.text) }
        return clean(text.toString())
    }

    fun clean(raw: String): String? {
        val line = Compactor.stripThinking(raw)
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        var title = line
            .replace(PREFIX, "")
            .trim()
            .trim('"', '\'', '`', '*', '#', '“', '”', '「', '」', '«', '»', ' ')
            .replace(SPACES, " ")
            .trimEnd('.', '。', '!', ':', ';', ',')
            .trim()
        if (title.length > MAX_CHARS) {
            val cut = title.take(MAX_CHARS)
            val space = cut.lastIndexOf(' ')
            title = (if (space > MAX_CHARS / 2) cut.take(space) else cut).trimEnd() + "…"
        }
        return title.ifEmpty { null }
    }

    private val PREFIX = Regex("^(title|titre|título|titel|标题|題名|タイトル)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")

    private val INSTRUCTIONS = """
        Write a short title for this conversation: 2 to 6 words that name the topic or task.
        Use the same language as the user's message. No quotes, no emoji, no final punctuation.
        Reply with the title only.
    """.trimIndent()
}
