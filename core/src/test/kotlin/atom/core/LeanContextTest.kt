package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.ContextMeter
import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.agent.RequestSize
import atom.core.llm.ChatMessage
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolLoadout
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import atom.core.tool.schema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The context meter, the window-based compaction trigger and lazily loaded tools. */
class LeanContextTest {
    private class Engine(val script: (LlmRequest, Int) -> List<LlmEvent>) : LlmEngine {
        val requests = mutableListOf<LlmRequest>()
        override val id = "lean"
        override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 0, onDevice = false)
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            script(request, requests.size - 1).forEach { emit(it) }
        }
    }

    private class Named(name: String, description: String = "Does $name.") : Tool {
        override val spec = ToolSpec(name, description, schema())
        override val risk = Risk.Read
        var calls = 0
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            calls++
            return ToolResult.ok("ok")
        }
    }

    private fun isSummaryRequest(r: LlmRequest) = r.tools.isEmpty() && r.messages.last().text.endsWith("Write the summary now.")

    private fun call(name: String, args: String = "{}", id: String = "c") = LlmEvent.ToolCallReady(ToolCall(id, name, args))

    private fun answer(text: String) = listOf(LlmEvent.TextDelta(text), LlmEvent.Finished(FinishReason.Stop))

    // ------------------------------------------------------------------ Window and compaction

    @Test
    fun aMillionTokenModelWithASixtyThousandTokenRequestNeverCompacts() = runTest {
        val engine = Engine { _, n ->
            if (n < 4) {
                listOf(call("echo", id = "c$n"), LlmEvent.Usage(60_000 + n * 1_000, 40), LlmEvent.Finished(FinishReason.ToolCalls))
            } else {
                listOf(LlmEvent.TextDelta("done"), LlmEvent.Usage(65_000, 10), LlmEvent.Finished(FinishReason.Stop))
            }
        }
        val config = AgentConfig("m", "sys", contextWindow = 1_048_576)
        val events = Agent(engine, ToolRegistry(listOf(Named("echo"))), config).run(listOf(ChatMessage.user("hi")), FixedContext()).toList()

        assertTrue(events.none { it is AgentEvent.Compacting || it is AgentEvent.Compacted })
        val usage = events.filterIsInstance<AgentEvent.ContextUsage>()
        assertTrue(usage.all { it.windowTokens == 1_048_576 - config.maxOutputTokens })
        val last = usage.last()
        assertTrue(ContextMeter.fraction(last.usedTokens, last.windowTokens)!! < 0.1f)
        assertTrue(engine.requests.none(::isSummaryRequest))
    }

    @Test
    fun theDefaultPolicyFollowsTheWindowInsteadOfASmallCap() {
        val config = AgentConfig("m", "sys", contextWindow = 1_048_576)
        assertEquals(1_048_576 - 2_048, config.usableWindow)
        assertEquals(config.usableWindow, config.packing.budgetTokens)
        // A deliberate cap (on-device, a user limit) still wins when it is smaller.
        assertEquals(6_000, AgentConfig("m", "sys", contextWindow = 8_192, context = ContextPolicy(budgetTokens = 6_000)).usableWindow)
        // Unknown windows pack against a generous default, not a tiny one, and report no window.
        val unknown = AgentConfig("m", "sys", contextWindow = null)
        assertNull(unknown.usableWindow)
        assertEquals(ContextPolicy.UNKNOWN_WINDOW_BUDGET, unknown.packing.budgetTokens)
        assertTrue(ContextPolicy.UNKNOWN_WINDOW_BUDGET >= 32_000)
    }

    @Test
    fun reasoningTokensDoNotInflateTheMeter() = runTest {
        val engine = Engine { _, _ -> listOf(LlmEvent.TextDelta("hello"), LlmEvent.Usage(1_000, 5_000), LlmEvent.Finished(FinishReason.Stop)) }
        val events = Agent(engine, ToolRegistry(emptyList()), AgentConfig("m", "sys", contextWindow = 128_000))
            .run(listOf(ChatMessage.user("hi")), FixedContext()).toList()
        val used = events.filterIsInstance<AgentEvent.ContextUsage>().last().usedTokens
        assertTrue(used in 1_000..1_100, "used=$used")
    }

    @Test
    fun neverCompactsTwiceInARowWithoutNewContent() = runTest {
        // The fixed part (system prompt) alone stays above the trigger after compacting: the old code compacted again
        // on every following step.
        val system = "s".repeat(4 * 5_000)
        val history = buildList {
            repeat(12) {
                add(ChatMessage.user("q$it " + "x".repeat(600)))
                add(ChatMessage.assistant("a$it " + "y".repeat(600)))
            }
            add(ChatMessage.user("latest"))
        }
        val engine = Engine { request, _ ->
            when {
                isSummaryRequest(request) -> answer("SUMMARY")
                request.messages.count { it.role == atom.core.llm.Role.Tool } < 3 -> listOf(call("echo", id = "e${request.messages.size}"), LlmEvent.Finished(FinishReason.ToolCalls))
                else -> answer("done")
            }
        }
        val config = AgentConfig("m", system, contextWindow = 8_000, context = ContextPolicy(budgetTokens = 8_000))
        val events = Agent(engine, ToolRegistry(listOf(Named("echo"))), config).run(history, FixedContext()).toList()

        assertEquals(1, events.count { it is AgentEvent.Compacted })
        assertTrue(events.last() is AgentEvent.Done)
    }

    // ------------------------------------------------------------------ Ring math

    @Test
    fun ringMath() {
        assertEquals(0.5f, ContextMeter.fraction(500, 1_000))
        assertEquals(1f, ContextMeter.fraction(2_000, 1_000))
        assertNull(ContextMeter.fraction(10, null))
        assertNull(ContextMeter.fraction(10, 0))
        // "hi" with a lean request on a 1M model is a sliver, not 97%.
        assertTrue(ContextMeter.fraction(4_000, 1_048_576 - 2_048)!! < 0.01f)
        assertEquals(0.85f, ContextMeter.compactMark(1_000_000, autoCompact = true))
        assertNull(ContextMeter.compactMark(1_000_000, autoCompact = false))
        assertNull(ContextMeter.compactMark(null, autoCompact = true))
    }

    @Test
    fun savedConversationsWithTheOldCapUseTheModelsWindow() {
        assertEquals(1_046_528, ContextMeter.displayWindow(ContextMeter.LEGACY_CLOUD_CAP, 1_046_528))
        assertEquals(ContextMeter.LEGACY_CLOUD_CAP, ContextMeter.displayWindow(ContextMeter.LEGACY_CLOUD_CAP, null))
        assertEquals(6_000, ContextMeter.displayWindow(6_000, 1_046_528))
        assertEquals(200_000, ContextMeter.displayWindow(null, 200_000))
        assertNull(ContextMeter.displayWindow(null, null))
    }

    // ------------------------------------------------------------------ Lazy tools

    private fun loadout(vararg extra: Tool): Pair<ToolLoadout, List<Named>> {
        val tools = listOf(Named("read_screen"), Named("tap"), Named("browser_open"), Named("browser_click"), Named("set_alarm"), Named("calendar_read"))
        val groups = mapOf("browser_open" to setOf("browser"), "browser_click" to setOf("browser"), "set_alarm" to setOf("clock"), "calendar_read" to setOf("calendar", "clock"))
        val l = ToolLoadout(
            tools + extra,
            { groups[it.spec.name].orEmpty() },
            summaries = mapOf("browser" to "a real browser"),
            notes = mapOf("browser" to listOf("BROWSER NOTE")),
        )
        return l to tools
    }

    @Test
    fun coreToolsAndTheLoaderComeFirstThenGroupsOnDemand() {
        val (l, _) = loadout()
        assertEquals(listOf("read_screen", "tap", ToolLoadout.LOADER), l.specs().map { it.name })
        val loader = assertNotNull(l.loader)
        assertTrue("browser: a real browser [browser_open, browser_click]" in loader.spec.description)
        assertTrue("clock [set_alarm, calendar_read]" in loader.spec.description)

        val text = l.load(listOf("browser")).text
        assertTrue("browser_open" in text && "BROWSER NOTE" in text)
        // Original order is kept, so the prefix is the same however groups were loaded.
        assertEquals(listOf("read_screen", "tap", "browser_open", "browser_click", ToolLoadout.LOADER), l.specs().map { it.name })
        // The note goes out once.
        assertFalse("BROWSER NOTE" in l.load(listOf("browser")).text)
    }

    @Test
    fun aToolInSeveralGroupsShowsWithAnyOfThem() {
        val (l, _) = loadout()
        l.load(listOf("calendar"))
        assertTrue("calendar_read" in l.specs().map { it.name })
        assertFalse("set_alarm" in l.specs().map { it.name })
        l.load(listOf("set_alarm"))
        assertTrue("set_alarm" in l.specs().map { it.name })
    }

    @Test
    fun unknownGroupsAreRefusedWithTheList() {
        val (l, _) = loadout()
        val r = l.load(listOf("rocket"))
        assertTrue(r.isError)
        assertTrue("browser" in r.text && "clock" in r.text)
        assertTrue(l.load(listOf("tap")).text.contains("Already available"))
    }

    @Test
    fun noLoaderWhenEveryToolIsCore() {
        val l = ToolLoadout(listOf(Named("a"), Named("b")), { emptySet() })
        assertNull(l.loader)
        assertEquals(listOf("a", "b"), l.specs().map { it.name })
    }

    @Test
    fun historyPreloadsTheGroupsItUsed() {
        val (l, _) = loadout()
        l.preload(listOf(ChatMessage.assistant("", listOf(ToolCall("1", "browser_click", "{}")))))
        assertTrue("browser_open" in l.specs().map { it.name })
        // Its note went out with that earlier load.
        assertFalse("BROWSER NOTE" in l.load(listOf("browser")).text)
    }

    @Test
    fun theAgentOffersMoreToolsAfterTheModelLoadsThem() = runTest {
        val (l, tools) = loadout()
        val engine = Engine { _, n ->
            when (n) {
                0 -> listOf(call(ToolLoadout.LOADER, """{"groups":"browser"}"""), LlmEvent.Finished(FinishReason.ToolCalls))
                1 -> listOf(call("browser_open", id = "b"), LlmEvent.Finished(FinishReason.ToolCalls))
                2 -> listOf(call("set_alarm", id = "s"), LlmEvent.Finished(FinishReason.ToolCalls))
                else -> answer("done")
            }
        }
        Agent(engine, ToolRegistry(l.all), AgentConfig("m", "sys"), l).run(listOf(ChatMessage.user("open a page")), FixedContext()).toList()

        assertEquals(listOf("read_screen", "tap", ToolLoadout.LOADER), engine.requests[0].tools.map { it.name })
        assertTrue("browser_open" in engine.requests[1].tools.map { it.name })
        assertEquals(1, tools.first { it.spec.name == "browser_open" }.calls)
        // A hidden tool called directly still runs, and its group is offered from then on.
        assertEquals(1, tools.first { it.spec.name == "set_alarm" }.calls)
        assertTrue("set_alarm" in engine.requests[3].tools.map { it.name })
    }

    // ------------------------------------------------------------------ Request size

    @Test
    fun requestSizeSplitsSystemToolsAndMessages() {
        val specs = listOf(ToolSpec("a", "x".repeat(100), schema()), ToolSpec("b", "y".repeat(50), schema()))
        val size = RequestSize.of(
            listOf(ChatMessage.system("s".repeat(400)), ChatMessage.user("hi")),
            specs,
        ) { if (it.name == "a") "one" else "two" }
        assertEquals(400, size.systemChars)
        assertEquals(2, size.messageChars)
        assertEquals(setOf("one", "two"), size.toolChars.keys)
        assertEquals(specs.sumOf { RequestSize.chars(it) }, size.toolTotalChars)
        assertTrue(size.describe().startsWith("request ≈"))
        assertTrue(ContextPacker.estimateTools(specs) > 0)
    }
}
