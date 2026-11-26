package atom.characters

import atom.characters.data.CharacterNotes
import atom.characters.data.StoryDraft
import atom.characters.model.Character
import atom.characters.model.WorldEntry
import atom.characters.model.Worldbook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterParityTest {
    @Test
    fun storyMemoryConflicts() {
        assertEquals(StoryDraft.Result.Same, StoryDraft.check("a", "a", "a draft"))
        assertEquals(StoryDraft.Result.AdoptRemote, StoryDraft.check("a", "b", "a"))
        assertEquals(StoryDraft.Result.AdoptRemote, StoryDraft.check("a", "b", "b"))
        assertEquals(StoryDraft.Result.Conflict, StoryDraft.check("a", "b", "my draft"))
    }

    @Test
    fun compatibilityNotes() {
        val plain = Character(id = "c", name = "Ada")
        assertTrue(CharacterNotes.compatibility(plain).isEmpty())
        val imported = plain.copy(
            extensions = JsonObject(mapOf("regex_scripts" to JsonPrimitive("x"))),
            worldbook = Worldbook(entries = listOf(WorldEntry(id = "e", name = "Castle", compatibilityNote = "Regex keys were skipped"))),
        )
        val notes = CharacterNotes.compatibility(imported)
        assertEquals("Castle: Regex keys were skipped", notes[0])
        assertTrue(notes[1].contains("regex_scripts"))
    }
}
