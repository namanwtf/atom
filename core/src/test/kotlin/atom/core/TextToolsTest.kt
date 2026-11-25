package atom.core

import atom.core.agent.HistoryEntry
import atom.core.agent.HistorySearch
import atom.core.agent.TitleGenerator
import atom.core.llm.ChatMessage
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmErrors
import atom.core.llm.LlmEvent
import atom.core.llm.LlmException
import atom.core.llm.LlmFailure
import atom.core.llm.LlmRequest
import atom.core.llm.ReasoningEffort
import atom.core.llm.ReasoningMode
import atom.core.llm.ReasoningSupport
import atom.core.llm.Role
import atom.core.tool.ToolResult
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextToolsTest {
    @Test
    fun titleRequestCarriesBothSidesAndTheLanguageRule() = runTest {
        var seen: LlmRequest? = null
        val engine = object : LlmEngine {
            override val id = "t"
            override val capabilities = EngineCapabilities(true, false, 8000, false)
            override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
                seen = request
                emit(LlmEvent.TextDelta("Title: \"Réserver un vol pour Lyon.\""))
                emit(LlmEvent.Finished(FinishReason.Stop))
            }
        }
        val request = TitleGenerator.request("model-x", "Réserve-moi un vol pour Lyon vendredi", "Voici les vols…", ReasoningEffort.Off)
        val title = TitleGenerator.generate(engine, request)
        assertEquals("Réserver un vol pour Lyon", title)
        val sent = seen!!
        assertEquals("model-x", sent.model)
        assertTrue(sent.tools.isEmpty())
        assertEquals(ReasoningEffort.Off, sent.reasoning)
        assertTrue(sent.messages.first().text.contains("same language"))
        assertTrue(sent.messages.last().text.contains("vol pour Lyon vendredi"))
    }

    @Test
    fun titleCleaningHandlesThinkingQuotesAndLength() {
        assertEquals("Battery check", TitleGenerator.clean("<think>hmm</think>\n**Battery check**"))
        assertNull(TitleGenerator.clean("   \n  "))
        val long = TitleGenerator.clean("A very long title that keeps going on and on about many different things forever")!!
        assertTrue(long.length <= TitleGenerator.MAX_CHARS + 1 && long.endsWith("…"))
        assertEquals("天气预报", TitleGenerator.clean("标题：天气预报。"))
    }

    @Test
    fun historySearchRanksAndReadsByIndex() = runTest {
        val entries = listOf(
            HistoryEntry(0, Role.User, "Book a table at Luigi's for Friday at 8pm"),
            HistoryEntry(1, Role.Assistant, "I found Luigi's Trattoria, phone 555-0199"),
            HistoryEntry(2, Role.Tool, "screen text: Luigi's menu", "read_screen"),
            HistoryEntry(3, Role.User, "what was the phone number?"),
        )
        val hits = HistorySearch.search(entries, "Luigi phone")
        assertEquals(1, hits.first().entry.index)
        val tool = HistorySearch.tool { entries }
        val read = tool.run(JsonObject(mapOf("index" to JsonPrimitive(1))), FixedContext())
        assertTrue(read.text.contains("555-0199"))
        val none = tool.run(JsonObject(mapOf("query" to JsonPrimitive("zebra"))), FixedContext())
        assertTrue(none.text.startsWith("No matches"))
        val missing: ToolResult = tool.run(JsonObject(emptyMap()), FixedContext())
        assertTrue(missing.isError)
    }

    @Test
    fun errorsAreExplainedInPlainWords() {
        fun kind(msg: String, status: Int? = null) = LlmErrors.classify(LlmException(msg, retryable = false, statusCode = status))
        assertEquals(LlmFailure.BadKey, kind("OpenAI request failed (HTTP 401): Incorrect API key", 401))
        assertEquals(LlmFailure.Forbidden, kind("x (HTTP 403): no", 403))
        assertEquals(LlmFailure.NotFound, kind("x (HTTP 404): The model `gpt-9` does not exist", 404))
        assertEquals(LlmFailure.RateLimited, kind("x (HTTP 429): Rate limit reached", 429))
        assertEquals(LlmFailure.Quota, kind("x (HTTP 429): You exceeded your current quota", 429))
        assertEquals(LlmFailure.ContextOverflow, kind("x (HTTP 400): This model's maximum context length is 128000 tokens", 400))
        assertEquals(LlmFailure.ContentFilter, kind("Gemini: response blocked by safety system (SAFETY)"))
        assertEquals(LlmFailure.Timeout, LlmErrors.classify(LlmException("x", true, cause = SocketTimeoutException("read"))))
        assertEquals(LlmFailure.ConnectionLost, LlmErrors.classify(LlmException("Anthropic network error: unexpected end of stream", true, cause = IOException("eof"))))
        assertEquals(LlmFailure.Server, kind("x (HTTP 503): overloaded", 503))
        assertTrue(LlmErrors.explain(LlmException("bad", false, 401)).contains("API key"))
    }

    @Test
    fun reasoningSnapsToTheNearestSupportedLevel() {
        val limited = ReasoningSupport(ReasoningMode.Optional, listOf(ReasoningEffort.Low, ReasoningEffort.High))
        assertEquals(ReasoningEffort.High, limited.snap(ReasoningEffort.Max))
        assertEquals(ReasoningEffort.Low, limited.snap(ReasoningEffort.Low))
        assertEquals(ReasoningEffort.High, limited.snap(ReasoningEffort.Medium))
        assertNull(limited.snap(null))
        assertEquals(ReasoningEffort.Off, limited.request(enabled = false, effort = ReasoningEffort.High))
        assertNull(ReasoningSupport(ReasoningMode.Always, emptyList()).request(enabled = false, effort = ReasoningEffort.Max))
        assertEquals(ReasoningEffort.High, ReasoningSupport(ReasoningMode.Always, listOf(ReasoningEffort.Low, ReasoningEffort.High)).request(false, ReasoningEffort.Max))
        assertNull(ReasoningSupport.None.request(enabled = true, effort = ReasoningEffort.Max))
        assertEquals(ReasoningEffort.XHigh, ReasoningSupport.Default.request(true, ReasoningEffort.XHigh))
        val fromLevels = ReasoningSupport.of(true, listOf(ReasoningEffort.High, ReasoningEffort.Off, ReasoningEffort.Low))
        assertEquals(ReasoningMode.Optional, fromLevels.mode)
        assertEquals(listOf(ReasoningEffort.Low, ReasoningEffort.High), fromLevels.levels)
        assertEquals(ReasoningMode.Always, ReasoningSupport.of(true, emptyList()).mode)
        assertEquals(ReasoningMode.Unsupported, ReasoningSupport.of(false, listOf(ReasoningEffort.Low)).mode)
    }
}
