package atom.characters

import atom.characters.model.Placement
import atom.characters.model.SecondaryLogic
import atom.characters.model.WorldEntry
import atom.characters.model.Worldbook
import atom.characters.worldbook.EntryIssue
import atom.characters.worldbook.WorldbookMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldbookMatcherTest {
    private fun entry(id: String, vararg keys: String, content: String = "content of $id", block: WorldEntry.() -> WorldEntry = { this }) =
        WorldEntry(id = id, keys = keys.toList(), content = content).block()

    private fun book(vararg entries: WorldEntry, scanDepth: Int = 4, budget: Int = 0, recursive: Boolean = false) =
        Worldbook(scanDepth = scanDepth, tokenBudget = budget, recursiveScanning = recursive, entries = entries.toList())

    private fun ids(b: Worldbook, vararg recent: String, budget: Int = b.tokenBudget) =
        WorldbookMatcher.match(b, recent.toList(), budget) { it.length }.all.map { it.id }

    @Test
    fun matchesKeywordsCaseInsensitiveWholeWords() {
        val b = book(entry("cat", "cat"), entry("dog", "Dog"), entry("case", "Ion") { copy(caseSensitive = true) })
        assertEquals(listOf("cat"), ids(b, "My CAT sleeps"))
        assertEquals(emptyList(), ids(b, "category theory"))
        assertEquals(listOf("dog"), ids(b, "hot-dog!"))
        assertEquals(emptyList(), ids(b, "ion engines"))
        assertEquals(listOf("case"), ids(b, "an Ion drive"))
    }

    @Test
    fun partialMatchingWhenWholeWordsIsOff() {
        val b = book(entry("cat", "cat") { copy(wholeWords = false) })
        assertEquals(listOf("cat"), ids(b, "category"))
    }

    @Test
    fun regexKeys() {
        val b = book(entry("dragon", "/dr(a|e)gons?/i"))
        assertEquals(listOf("dragon"), ids(b, "Here be DREGONS"))
        assertEquals(emptyList(), ids(b, "drgn"))
    }

    @Test
    fun nonLatinKeysMatchAsSubstrings() {
        val b = book(entry("tea", "お茶"))
        assertEquals(listOf("tea"), ids(b, "今日はお茶を飲む"))
    }

    @Test
    fun secondaryLogic() {
        fun b(logic: SecondaryLogic) = book(
            entry("e", "sword") { copy(secondaryKeys = listOf("fire", "ice"), requireSecondary = true, secondaryLogic = logic) },
        )
        val none = "a sword"
        val one = "a sword of fire"
        val both = "a sword of fire and ice"
        assertEquals(listOf(false, true, true), listOf(none, one, both).map { ids(b(SecondaryLogic.AndAny), it).isNotEmpty() })
        assertEquals(listOf(false, false, true), listOf(none, one, both).map { ids(b(SecondaryLogic.AndAll), it).isNotEmpty() })
        assertEquals(listOf(true, false, false), listOf(none, one, both).map { ids(b(SecondaryLogic.NotAny), it).isNotEmpty() })
        assertEquals(listOf(true, true, false), listOf(none, one, both).map { ids(b(SecondaryLogic.NotAll), it).isNotEmpty() })
        val optional = book(entry("e", "sword") { copy(secondaryKeys = listOf("fire"), requireSecondary = false) })
        assertEquals(listOf("e"), ids(optional, none))
    }

    @Test
    fun alwaysActiveDisabledAndEmptyEntries() {
        val b = book(
            entry("always") { copy(alwaysActive = true) },
            entry("off", "moon") { copy(enabled = false) },
            entry("blank", "moon", content = "  "),
            entry("moon", "moon"),
        )
        assertEquals(listOf("always", "moon"), ids(b, "the moon"))
        assertEquals(listOf("always"), ids(b, "nothing here"))
        assertEquals(listOf(EntryIssue.Disabled), WorldbookMatcher.issues(b.entries[1]))
        assertEquals(listOf(EntryIssue.EmptyContent), WorldbookMatcher.issues(b.entries[2]))
        assertEquals(listOf(EntryIssue.NoKeywords), WorldbookMatcher.issues(entry("x")))
    }

    @Test
    fun scanDepthLimitsTheWindow() {
        val b = book(entry("old", "castle"), entry("deep", "castle") { copy(scanDepth = 3) }, scanDepth = 1)
        assertEquals(listOf("deep"), ids(b, "castle", "hello"))
        assertEquals(listOf("old", "deep"), ids(b, "hello", "castle"))
        assertEquals(emptyList(), ids(b, "castle", "a", "b", "c"))
    }

    @Test
    fun recursionFollowsContentAndHonoursFlags() {
        val chain = arrayOf(
            entry("a", "alpha", content = "leads to beta"),
            entry("b", "beta", content = "leads to gamma"),
            entry("c", "gamma", content = "end"),
        )
        assertEquals(listOf("a"), ids(book(*chain), "alpha"))
        assertEquals(listOf("a", "b", "c"), ids(book(*chain, recursive = true), "alpha"))
        val excluded = arrayOf(chain[0], chain[1].copy(excludeRecursion = true), chain[2])
        assertEquals(listOf("a"), ids(book(*excluded, recursive = true), "alpha"))
        val prevented = arrayOf(chain[0], chain[1].copy(preventRecursion = true), chain[2])
        assertEquals(listOf("a", "b"), ids(book(*prevented, recursive = true), "alpha"))
    }

    @Test
    fun recursionTerminatesOnCycles() {
        val b = book(entry("a", "alpha", content = "beta"), entry("b", "beta", content = "alpha"), recursive = true)
        assertEquals(listOf("a", "b"), ids(b, "alpha"))
    }

    @Test
    fun budgetKeepsAlwaysActiveThenPriorityAndSkipsWhatDoesNotFit() {
        val b = book(
            entry("low", "k", content = "x".repeat(40)) { copy(priority = 1) },
            entry("high", "k", content = "x".repeat(40)) { copy(priority = 9) },
            entry("const", content = "x".repeat(30)) { copy(alwaysActive = true, priority = 0) },
            entry("small", "k", content = "x".repeat(10)) { copy(priority = 0) },
        )
        val result = WorldbookMatcher.match(b, listOf("k"), budget = 85) { it.length }
        assertEquals(listOf("high", "const", "small"), result.all.map { it.id })
        assertEquals(listOf("low"), result.overBudget)
        assertEquals(80, result.usedTokens)
        assertEquals(4, ids(b, "k", budget = 0).size)
    }

    @Test
    fun orderingAndPlacement() {
        val b = book(
            entry("z", "k") { copy(insertionOrder = 30, placement = Placement.AfterDefinition) },
            entry("y", "k") { copy(insertionOrder = 10) },
            entry("x", "k") { copy(insertionOrder = 20, placement = Placement.AfterDefinition) },
            entry("w", "k") { copy(insertionOrder = 10) },
        )
        val r = WorldbookMatcher.match(b, listOf("k")) { it.length }
        assertEquals(listOf("y", "w"), r.before.map { it.id })
        assertEquals(listOf("x", "z"), r.after.map { it.id })
        repeat(3) { assertEquals(r, WorldbookMatcher.match(b, listOf("k")) { it.length }) }
    }

    @Test
    fun nullOrEmptyBook() {
        assertTrue(WorldbookMatcher.match(null, listOf("x")).all.isEmpty())
        assertFalse(WorldbookMatcher.triggers(entry("a", "x"), ""))
    }
}
