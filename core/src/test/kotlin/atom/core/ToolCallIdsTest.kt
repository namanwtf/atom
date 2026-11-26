package atom.core

import atom.core.llm.ToolCallIds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ToolCallIdsTest {
    @Test
    fun freshIdsAreNineLettersAndDigits() {
        repeat(200) { assertTrue(Regex("[A-Za-z0-9]{9}").matches(ToolCallIds.fresh())) }
    }

    @Test
    fun freshIdsAvoidTakenOnes() {
        val first = ToolCallIds.fresh(random = Random(7))
        val second = ToolCallIds.fresh(taken = setOf(first), random = Random(7))
        assertNotEquals(first, second)
    }

    @Test
    fun strictMapsAnyIdDeterministically() {
        assertEquals("abc123XYZ", ToolCallIds.strict("abc123XYZ"))
        for (id in listOf("preload_settings_browser", "call_0123456789abcdefghijklmn", "toolu_01ABC", "")) {
            val s = ToolCallIds.strict(id)
            assertTrue(ToolCallIds.isStrict(s), "$id -> $s")
            assertEquals(s, ToolCallIds.strict(id))
        }
        assertNotEquals(ToolCallIds.strict("call_a"), ToolCallIds.strict("call_b"))
    }

    @Test
    fun safeKeepsOrdinaryProviderIds() {
        assertEquals("call_0123456789abcdefghijklmn", ToolCallIds.safe("call_0123456789abcdefghijklmn"))
        assertEquals("preload_settings_browser", ToolCallIds.safe("preload_settings_browser"))
        val long = "preload_settings_clock_messages_automation"
        assertTrue(ToolCallIds.isStrict(ToolCallIds.safe(long)), "over 40 characters")
        assertTrue(ToolCallIds.isStrict(ToolCallIds.safe("has space")))
    }
}
