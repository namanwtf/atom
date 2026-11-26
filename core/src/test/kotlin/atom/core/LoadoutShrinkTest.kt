package atom.core

import atom.core.llm.ChatMessage
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import atom.core.tool.Tool
import atom.core.tool.ToolLoadout
import atom.core.tool.schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shedding tools on a full window must not flap: what the model just loaded or used stays. */
class LoadoutShrinkTest {
    private fun tool(name: String) = object : Tool by EchoTool() { override val spec = ToolSpec(name, "Does $name.", schema()) }

    private val groupOf = mapOf("a1" to "a", "b1" to "b", "c1" to "c", "d1" to "d")

    private fun loadout() = ToolLoadout(listOf(tool("core")) + groupOf.keys.map(::tool), { t -> setOfNotNull(groupOf[t.spec.name]) })

    @Test
    fun groupsFromEarlierTurnsGoBeforeOnesLoadedNow() {
        val l = loadout()
        val old = ToolCall("x", "a1", "{}")
        l.preload(listOf(ChatMessage.assistant("", listOf(old)), ChatMessage.toolResult(old, emptyList())))
        l.onStep(1)
        l.load(listOf("b"))
        l.onStep(2)
        assertTrue(l.shrink())
        assertEquals(setOf("b"), l.loadedGroups())
        // b was loaded in the step before: it stays.
        assertFalse(l.shrink())
        assertEquals(setOf("b"), l.loadedGroups())
    }

    @Test
    fun aGroupUsedThisStepIsNeverDropped() {
        val l = loadout()
        l.onStep(1)
        l.load(listOf("c"))
        l.onStep(5)
        l.onCall("c1")
        assertFalse(l.shrink())
        assertEquals(setOf("c"), l.loadedGroups())
    }

    @Test
    fun longUnusedGroupsGoFirst() {
        val l = loadout()
        l.onStep(1)
        l.load(listOf("c", "d"))
        l.onStep(4)
        l.onCall("d1")
        l.onStep(6)
        assertTrue(l.shrink())
        assertEquals(setOf("d"), l.loadedGroups())
    }

    @Test
    fun aPreloadedGroupSurvivesTheFirstStep() {
        val l = loadout()
        l.preloadGroups(listOf("a"))
        l.onStep(1)
        assertFalse(l.shrink())
        assertEquals(setOf("a"), l.loadedGroups())
    }
}
