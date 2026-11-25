package atom.characters.data

import atom.characters.model.Character

/** The character's "Compatibility notes": what an imported card had that Atom could not represent or does not use. */
object CharacterNotes {
    fun compatibility(c: Character): List<String> = buildList {
        c.worldbook?.entries?.forEach { e ->
            e.compatibilityNote?.takeIf { it.isNotBlank() }?.let { add(e.name.ifBlank { "Worldbook entry" } + ": " + it) }
        }
        val kept = (c.extensions?.keys.orEmpty() + c.worldbook?.extensions?.keys.orEmpty()).toSortedSet()
        if (kept.isNotEmpty()) {
            add("Card extensions kept for export but not used by Atom (scripts and extension UIs never run): " + kept.joinToString())
        }
    }
}
