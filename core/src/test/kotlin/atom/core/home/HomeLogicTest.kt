package atom.core.home

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelReadinessTest {
    private val deepseek = ProviderFacts("deepseek", enabled = true, keyOptional = false, accountAuth = false, modelIds = listOf("deepseek-flash"))
    private val ollama = ProviderFacts("ollama", enabled = true, keyOptional = true, accountAuth = false)
    // Account sign-in providers report keyOptional (no API key), but still need the sign-in.
    private val openai = ProviderFacts("openai", enabled = true, keyOptional = true, accountAuth = true)
    private val off = ProviderFacts("mistral", enabled = false, keyOptional = false, accountAuth = false)
    private val all = listOf(deepseek, ollama, openai, off)

    @Test
    fun freshInstallSelectionWithoutKeyIsNotReady() {
        assertFalse(ModelReadiness.cloudReady("deepseek", "deepseek-flash", all, savedKeys = emptySet(), signedIn = emptySet()))
    }

    @Test
    fun savedKeyMakesTheSelectionReady() {
        assertTrue(ModelReadiness.cloudReady("deepseek", "deepseek-flash", all, savedKeys = setOf("deepseek"), signedIn = emptySet()))
    }

    @Test
    fun unknownModelOrDisabledProviderIsNotReady() {
        assertFalse(ModelReadiness.cloudReady("deepseek", "gone", all, setOf("deepseek"), emptySet()))
        assertFalse(ModelReadiness.cloudReady("mistral", "any", all, setOf("mistral"), emptySet()))
        assertFalse(ModelReadiness.cloudReady("nowhere", "any", all, setOf("nowhere"), emptySet()))
    }

    @Test
    fun keylessAndAccountProviders() {
        assertTrue(ModelReadiness.cloudReady("ollama", "llama3", all, emptySet(), emptySet()))
        // Account providers need a sign-in; a stray saved key does not count.
        assertFalse(ModelReadiness.cloudReady("openai", "gpt", all, setOf("openai"), emptySet()))
        assertTrue(ModelReadiness.cloudReady("openai", "gpt", all, emptySet(), setOf("openai")))
    }

    @Test
    fun adHocCustomEndpointNeedsABaseUrl() {
        assertTrue(ModelReadiness.cloudReady("custom", "m", all, emptySet(), emptySet(), customBaseUrl = "http://10.0.0.2:8080/v1"))
        assertFalse(ModelReadiness.cloudReady("custom", "m", all, emptySet(), emptySet(), customBaseUrl = " "))
    }

    @Test
    fun localAndAnyUsable() {
        assertTrue(ModelReadiness.localReady("gemma", listOf("gemma", "qwen")))
        assertFalse(ModelReadiness.localReady("gemma", emptyList()))
        assertFalse(ModelReadiness.anyUsable(listOf(deepseek, off), emptySet(), emptySet(), installedCount = 0))
        assertTrue(ModelReadiness.anyUsable(listOf(deepseek, off), emptySet(), emptySet(), installedCount = 1))
        assertTrue(ModelReadiness.anyUsable(listOf(deepseek), setOf("deepseek"), emptySet(), installedCount = 0))
        assertFalse(ModelReadiness.anyUsable(listOf(off), setOf("mistral"), emptySet(), installedCount = 0))
    }
}

class ConversationGroupsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    private data class Row(val id: String, val pinned: Boolean, val running: Boolean, val updatedAt: Long)

    @Test
    fun groupsInDisplayOrderAndKeepsOrderInside() {
        val now = at(2026, 11, 25, 10)
        val bounds = ConversationGroups.bounds(now, zone)
        val rows = listOf(
            Row("old", pinned = false, running = false, updatedAt = at(2026, 11, 20, 9)),
            Row("today2", pinned = false, running = false, updatedAt = at(2026, 11, 25, 1)),
            Row("pin", pinned = true, running = false, updatedAt = at(2026, 1, 1, 1)),
            Row("yday", pinned = false, running = false, updatedAt = at(2026, 11, 24, 23)),
            Row("live", pinned = false, running = true, updatedAt = at(2026, 11, 1, 1)),
            Row("today1", pinned = false, running = false, updatedAt = at(2026, 11, 25, 9)),
        )
        val groups = ConversationGroups.group(rows, bounds, { it.pinned }, { it.running }, { it.updatedAt })
        assertEquals(
            listOf(ConversationGroup.Pinned, ConversationGroup.Today, ConversationGroup.Yesterday, ConversationGroup.Recent),
            groups.map { it.first },
        )
        assertEquals(listOf("today2", "live", "today1"), groups[1].second.map { it.id })
        assertEquals(listOf("old"), groups[3].second.map { it.id })
    }

    @Test
    fun emptyGroupsAreLeftOut() {
        val now = at(2026, 11, 25, 10)
        val groups = ConversationGroups.group(listOf(Row("a", false, false, now)), ConversationGroups.bounds(now, zone), { it.pinned }, { it.running }, { it.updatedAt })
        assertEquals(listOf(ConversationGroup.Today), groups.map { it.first })
    }

    @Test
    fun yesterdayFollowsTheCalendarAcrossAClockChange() {
        // Berlin leaves summer time on 2026-10-25: that day has 25 hours.
        val now = at(2026, 10, 26, 0) + 30 * 60_000L
        val bounds = ConversationGroups.bounds(now, zone)
        assertEquals(at(2026, 10, 26, 0), bounds.startOfToday)
        assertEquals(at(2026, 10, 25, 0), bounds.startOfYesterday)
        assertEquals(ConversationGroup.Yesterday, ConversationGroups.groupOf(false, false, at(2026, 10, 25, 0) + 1, bounds))
        assertEquals(ConversationGroup.Recent, ConversationGroups.groupOf(false, false, at(2026, 10, 25, 0) - 1, bounds))
    }
}
