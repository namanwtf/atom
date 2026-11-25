package atom.characters.worldbook

import atom.characters.card.CardCodec
import atom.characters.model.Placement
import atom.characters.model.SecondaryLogic
import atom.characters.model.WorldEntry
import atom.characters.model.Worldbook
import atom.core.agent.ContextPacker
import java.util.Locale

/** Entries chosen for one request, each list in prompt order. */
data class WorldbookMatch(
    val before: List<WorldEntry>,
    val after: List<WorldEntry>,
    val usedTokens: Int,
    /** Ids of entries that matched but did not fit the token budget. */
    val overBudget: List<String>,
) {
    val all: List<WorldEntry> get() = before + after

    companion object {
        val Empty = WorldbookMatch(emptyList(), emptyList(), 0, emptyList())
    }
}

/**
 * Decides which worldbook entries to inject for the latest messages. Deterministic: the same book and messages always
 * give the same entries in the same order.
 *
 * 1. Every enabled entry with content is a candidate. Always-active entries are in; the others need a primary
 *    keyword in their scan window (the last `scanDepth` messages) and, when `requireSecondary` is on, the secondary
 *    keywords must satisfy the entry's [SecondaryLogic].
 * 2. With recursive scanning, the content of newly activated entries is scanned for further entries, up to
 *    [MAX_RECURSION] rounds. `excludeRecursion` entries cannot be activated this way; `preventRecursion` entries'
 *    content is not scanned.
 * 3. Budget: always-active entries first, then higher priority, then earlier declaration; an entry that does not fit
 *    is skipped and smaller ones may still fit.
 * 4. Output order: insertion order ascending, then declaration order, split by placement.
 */
object WorldbookMatcher {
    const val MAX_RECURSION = 8

    fun match(
        book: Worldbook?,
        recent: List<String>,
        budget: Int = book?.tokenBudget ?: 0,
        estimate: (String) -> Int = ContextPacker::estimate,
    ): WorldbookMatch {
        if (book == null) return WorldbookMatch.Empty
        val candidates = book.entries.withIndex().filter { it.value.enabled && it.value.content.isNotBlank() }
        if (candidates.isEmpty()) return WorldbookMatch.Empty

        val windows = HashMap<Int, String>()
        fun windowFor(e: WorldEntry): String {
            val depth = (e.scanDepth ?: book.scanDepth).coerceAtLeast(0)
            return windows.getOrPut(depth) { recent.takeLast(depth).joinToString("\n") }
        }

        val active = LinkedHashSet<Int>()
        val firstRound = candidates.filter { (_, e) -> e.alwaysActive || triggers(e, windowFor(e)) }
        firstRound.forEach { active += it.index }

        if (book.recursiveScanning) {
            var frontier = firstRound.filter { !it.value.preventRecursion }.map { it.value.content }
            var round = 0
            while (frontier.isNotEmpty() && round < MAX_RECURSION) {
                val text = frontier.joinToString("\n")
                val found = candidates.filter { (i, e) -> i !in active && !e.excludeRecursion && triggers(e, text) }
                if (found.isEmpty()) break
                found.forEach { active += it.index }
                frontier = found.filter { !it.value.preventRecursion }.map { it.value.content }
                round++
            }
        }

        val chosen = candidates.filter { it.index in active }
        val byImportance = chosen.sortedWith(
            compareByDescending<IndexedValue<WorldEntry>> { it.value.alwaysActive }
                .thenByDescending { it.value.effectivePriority }
                .thenBy { it.index },
        )
        val kept = ArrayList<IndexedValue<WorldEntry>>()
        val over = ArrayList<String>()
        var used = 0
        for (item in byImportance) {
            val cost = estimate(item.value.content)
            if (budget > 0 && used + cost > budget) {
                over += item.value.id
                continue
            }
            used += cost
            kept += item
        }
        val ordered = kept.sortedWith(compareBy<IndexedValue<WorldEntry>> { it.value.insertionOrder }.thenBy { it.index }).map { it.value }
        return WorldbookMatch(
            before = ordered.filter { it.placement == Placement.BeforeDefinition },
            after = ordered.filter { it.placement == Placement.AfterDefinition },
            usedTokens = used,
            overBudget = over,
        )
    }

    fun triggers(e: WorldEntry, text: String): Boolean {
        if (text.isEmpty()) return false
        if (e.keys.none { keyMatches(it, text, e) }) return false
        if (!e.requireSecondary || e.secondaryKeys.isEmpty()) return true
        val hits = e.secondaryKeys.count { keyMatches(it, text, e) }
        return when (e.secondaryLogic) {
            SecondaryLogic.AndAny -> hits > 0
            SecondaryLogic.AndAll -> hits == e.secondaryKeys.size
            SecondaryLogic.NotAny -> hits == 0
            SecondaryLogic.NotAll -> hits < e.secondaryKeys.size
        }
    }

    fun keyMatches(rawKey: String, text: String, e: WorldEntry): Boolean {
        val key = rawKey.trim()
        if (key.isEmpty()) return false
        CardCodec.compileKey(key)?.let { return it.containsMatchIn(text) }
        val hay = if (e.caseSensitive) text else text.lowercase(Locale.ROOT)
        val needle = if (e.caseSensitive) key else key.lowercase(Locale.ROOT)
        if (!e.wholeWords || !isAsciiWordChar(needle.first()) && !isAsciiWordChar(needle.last())) return hay.contains(needle)
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) return false
            val end = at + needle.length
            val leftOk = !isAsciiWordChar(needle.first()) || at == 0 || !isWordChar(hay[at - 1])
            val rightOk = !isAsciiWordChar(needle.last()) || end == hay.length || !isWordChar(hay[end])
            if (leftOk && rightOk) return true
            from = at + 1
        }
    }

    /** Problems the editor shows next to an entry; an entry with any of these never reaches the prompt. */
    fun issues(e: WorldEntry): List<EntryIssue> = buildList {
        if (!e.enabled) add(EntryIssue.Disabled)
        if (e.content.isBlank()) add(EntryIssue.EmptyContent)
        if (!e.alwaysActive && e.keys.none { it.isNotBlank() }) add(EntryIssue.NoKeywords)
    }

    private fun isAsciiWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
}

enum class EntryIssue { Disabled, EmptyContent, NoKeywords }
