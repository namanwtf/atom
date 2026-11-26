package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.llm.ChatMessage
import atom.core.llm.FinishReason
import atom.core.llm.LlmEvent
import atom.core.llm.Role
import atom.core.llm.ToolSpec
import atom.core.tool.Tool
import atom.core.tool.ToolLoadout
import atom.core.tool.ToolRegistry
import atom.core.tool.schema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreloadExchangeTest {
    private val note = "Websites: use the agent browser, never the user's own browser."

    private fun tool(name: String, desc: String = "Does $name.") =
        object : Tool by EchoTool() { override val spec = ToolSpec(name, desc, schema { string("x", "y") }) }

    private val tools = listOf(tool("read_screen"), tool("browser_open"), tool("browser_extract"), tool("set_volume"), tool("set_toggle"))
    private val groupOf = mapOf("browser_open" to "browser", "browser_extract" to "browser", "set_volume" to "settings", "set_toggle" to "settings")

    private fun loadout() = ToolLoadout(tools, { t -> setOfNotNull(groupOf[t.spec.name]) }, notes = mapOf("browser" to listOf(note)))

    private fun answer() = ScriptedEngine(listOf(listOf(LlmEvent.TextDelta("done"), LlmEvent.Finished(FinishReason.Stop))))

    @Test
    fun preloadGoesIntoTheConversationNotTheSystemPrompt() = runTest {
        val loadout = loadout()
        val exchange = assertNotNull(loadout.preloadGroups(listOf("browser")).asExchange(loadout))
        assertEquals(ToolLoadout.LOADER, exchange.first.name)
        assertTrue(note in exchange.second.text && "browser_open" in exchange.second.text, exchange.second.text)

        val engine = answer()
        val config = AgentConfig("m", "the same system prompt every turn")
        val events = Agent(engine, ToolRegistry(loadout.all), config, loadout)
            .run(listOf(ChatMessage.user("open the website")), FixedContext(), preloaded = exchange).toList()
        val sent = engine.requests.single()
        assertEquals("the same system prompt every turn", sent.messages.first().text)
        assertTrue(sent.messages.any { it.role == Role.Tool && note in it.text })
        assertTrue(sent.tools.any { it.name == "browser_open" })
        // It is kept with the conversation like any tool call, but marked internal and not reported as a step.
        val kept = events.filterIsInstance<AgentEvent.Done>().single().newMessages
        assertEquals(ToolLoadout.LOADER, kept.first().toolCalls.single().name)
        val appended = events.filterIsInstance<AgentEvent.Appended>()
        assertTrue(appended.take(2).all { it.info.internal }, appended.take(2).toString())
        assertTrue(!appended.last().info.internal)
        assertTrue(events.none { it is AgentEvent.ToolStarted || it is AgentEvent.ToolFinished })
    }

    @Test
    fun theNextTurnKeepsTheGroupsAndDoesNotRepeatTheNotes() = runTest {
        val first = loadout()
        val exchange = assertNotNull(first.preloadGroups(listOf("browser")).asExchange(first))
        val history = listOf(
            ChatMessage.user("open the website"),
            ChatMessage.assistant("", listOf(exchange.first)),
            ChatMessage.toolResult(exchange.first, exchange.second.toParts()),
            ChatMessage.assistant("done"),
            ChatMessage.user("and the next page"),
        )
        val next = loadout()
        next.preload(history)
        assertEquals(setOf("browser"), next.loadedGroups())
        assertTrue(next.specs().any { it.name == "browser_extract" })
        // The note is in the history already: neither a reload nor another preload sends it again.
        assertTrue(note !in next.load(listOf("browser")).text)
        assertEquals(emptyList(), next.preloadGroups(listOf("browser")).notes)
    }

    @Test
    fun nothingPreloadedMeansNoExchange() {
        val loadout = loadout()
        assertNull(loadout.preloadGroups(emptyList()).asExchange(loadout))
    }

    @Test
    fun anOverfullSmallWindowDropsOldGroupsAndShrinksTheHistory() = runTest {
        // Two big groups loaded from history plus a long page: far more than a 4096-token window holds.
        val big = (1..12).map { tool("set_thing_$it", "Sets thing $it. " + "d".repeat(500)) } +
            (1..12).map { tool("browser_step_$it", "Browser step $it. " + "d".repeat(500)) }
        val groups = big.associate { it.spec.name to if (it.spec.name.startsWith("set_")) "settings" else "browser" }
        val loadout = ToolLoadout(listOf(tool("read_screen")) + big, { t -> setOfNotNull(groups[t.spec.name]) })
        val earlier = atom.core.llm.ToolCall("c0", "set_thing_1", "{}")
        val call = atom.core.llm.ToolCall("c1", "browser_step_1", "{}")
        val history = listOf(
            ChatMessage.user("turn it on"),
            ChatMessage.assistant("", listOf(earlier)),
            ChatMessage.toolResult(earlier, listOf(atom.core.llm.ContentPart.Text("on"))),
            ChatMessage.assistant("Done."),
            ChatMessage.user("summarize the page"),
            ChatMessage.assistant("", listOf(call)),
            ChatMessage.toolResult(call, listOf(atom.core.llm.ContentPart.Text("p".repeat(12_000)))),
        )
        // Both groups come back from the history, as on the next turn of a real conversation.
        loadout.preload(history)
        val config = AgentConfig(
            "m", "s", maxOutputTokens = 512, contextWindow = 4_096,
            context = ContextPolicy(budgetTokens = 3_200, sharedWindow = 4_096, maxToolResultChars = 1_500),
        )
        val engine = answer()
        val events = Agent(engine, ToolRegistry(loadout.all), config, loadout).run(history, FixedContext()).toList()
        assertTrue(events.none { it is AgentEvent.Failed })
        val sent = engine.requests.single()
        val total = config.toolTokens(sent.tools) + sent.messages.sumOf(ContextPacker::estimate) + config.maxOutputTokens
        assertTrue(total <= 4_096, "tools + messages + answer = $total tokens")
        // The oldest group went; the core tools and the loader stay, and the task is still there.
        assertTrue(sent.tools.none { it.name.startsWith("set_thing") })
        assertTrue(sent.tools.any { it.name == "read_screen" } && sent.tools.any { it.name == ToolLoadout.LOADER })
        assertEquals("turn it on", sent.messages[1].text)
    }

    @Test
    fun theExchangeIdSuitsEveryProvider() {
        val taken = setOf("abc123XYZ")
        repeat(50) {
            val l = loadout()
            val id = assertNotNull(l.preloadGroups(listOf("browser")).asExchange(l, takenIds = taken)).first.id
            assertTrue(atom.core.llm.ToolCallIds.isStrict(id), id)
            assertTrue(id !in taken)
        }
    }
}
