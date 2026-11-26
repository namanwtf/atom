package atom.characters

import atom.characters.data.CharacterStore
import atom.characters.model.Character
import atom.characters.model.ChatBinding
import atom.core.io.deleteTreeNoFollow
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Editing a character shows in its chats at once. */
class LiveCharacterTest {
    private val dir = Files.createTempDirectory("atom-live-character").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteTreeNoFollow()
    }

    private val mira = Character(id = "mira", name = "Mira", profile = "A cartographer.")

    @Test
    fun snapshotsFollowTheStoredCharacter() {
        val bindings = mapOf(
            "c1" to ChatBinding("c1", "mira", snapshot = mira),
            "c2" to ChatBinding("c2", "gone", snapshot = Character(id = "gone", name = "Gone")),
        )
        val edited = mira.copy(name = "Mira Vale", image = "mira.png", imageVersion = 7)
        val next = CharacterStore.withLiveSnapshots(bindings, listOf(edited))
        assertEquals(edited, next.getValue("c1").snapshot)
        // A deleted character keeps its snapshot, so the chat goes on working.
        assertEquals("Gone", next.getValue("c2").snapshot.name)
        // Nothing changed: the same map comes back (no write).
        assertSame(next, CharacterStore.withLiveSnapshots(next, listOf(edited)))
    }

    @Test
    fun renamesAreReportedByShownName() {
        val before = listOf(mira, Character(id = "b", name = "Bo", nickname = "Bobo"))
        val after = listOf(mira.copy(name = "Mira Vale"), Character(id = "b", name = "Bo-renamed", nickname = "Bobo"), Character(id = "new", name = "New"))
        // Bo's shown name is the unchanged nickname; a new character is not a rename.
        assertEquals(mapOf("mira" to ("Mira" to "Mira Vale")), CharacterStore.renamed(before, after))
        assertTrue(CharacterStore.renamed(after, after).isEmpty())
    }

    @Test
    fun savingACharacterRefreshesItsOpenChats() = runTest {
        val store = CharacterStore(File(dir, "characters"), Dispatchers.Unconfined) { 1_000 }
        store.load()
        val saved = store.save(mira)
        store.bind(ChatBinding("chat-1", saved.id, snapshot = saved))
        store.update(saved.id) { it.copy(name = "Mira Vale", profile = "A cartographer of dreams.") }
        val snapshot = store.bindings.value.getValue("chat-1").snapshot
        assertEquals("Mira Vale", snapshot.name)
        assertEquals("A cartographer of dreams.", snapshot.profile)
        // Written to disk too: a restart keeps it.
        val reopened = CharacterStore(File(dir, "characters"), Dispatchers.Unconfined)
        reopened.load()
        assertEquals("Mira Vale", reopened.bindings.value.getValue("chat-1").snapshot.name)
    }
}
