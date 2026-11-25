package atom.characters

import atom.characters.data.CharacterStore
import atom.characters.data.DefaultCharacter
import atom.characters.model.Character
import atom.characters.model.Persona
import atom.characters.model.WorldEntry
import atom.characters.model.Worldbook
import atom.characters.prompt.Macros
import atom.characters.prompt.PromptAssembler
import atom.characters.session.CharacterSession
import atom.characters.session.CharacterTools
import atom.core.llm.ChatMessage
import atom.core.tool.ToolContext
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptAndSessionTest {
    private val dir: File = Files.createTempDirectory("chars").toFile()
    private val store = CharacterStore(File(dir, "characters"), Dispatchers.Unconfined) { 1_000 }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val kai = Character(
        id = "kai",
        name = "Kai",
        profile = "{{char}} is a pilot who trusts <USER>.",
        personality = "dry humour",
        scenario = "{{user}} boards {{char}}'s ship.",
        greeting = "Welcome aboard, {{user}}.",
        alternateGreetings = listOf("Late again, {{user}}?"),
        exampleDialogue = "<START>\n{{char}}: Strap in.",
        systemPrompt = "{{original}}Speak like a pilot.",
        postHistoryInstructions = "Keep {{char}} terse.",
        worldbook = Worldbook(
            entries = listOf(
                WorldEntry(id = "ship", keys = listOf("ship"), content = "The ship is named Heron."),
                WorldEntry(id = "rule", keys = listOf("rule"), content = "Never land at night.", placement = atom.characters.model.Placement.AfterDefinition),
            ),
        ),
    )

    @Test
    fun macros() {
        assertEquals("Kai meets Ana. Ana!", Macros.apply("{{char}} meets {{ USER }}. <user>!", "Kai", "Ana"))
        assertEquals("x", Macros.apply("{{original}}x", "a", "b"))
    }

    @Test
    fun assemblesSectionsInOrder() {
        val p = PromptAssembler.build(kai, Persona("Ana", "{{user}} is {{char}}'s co-pilot."), listOf("Is this your ship?", "What's the rule?"), "Ana owes Kai a coffee.")
        val s = p.system
        val order = listOf("# Character chat", "## Character instructions", "## World\n", "## Kai\n", "## Scenario", "## World details", "## About Ana", "## Example dialogue", "## Story memory")
        val positions = order.map { s.indexOf(it) }
        assertTrue(positions.all { it >= 0 }, s)
        assertEquals(positions.sorted(), positions)
        assertTrue("Kai is a pilot who trusts Ana." in s)
        assertTrue("Ana boards Kai's ship." in s)
        assertTrue("Ana is Kai's co-pilot." in s)
        assertTrue("The ship is named Heron." in s && "Never land at night." in s)
        assertTrue("Speak like a pilot." in s && "{{original}}" !in s)
        assertTrue("character_id \"kai\"" in s)
        assertEquals("Keep Kai terse.", p.postHistory)
        assertFalse("Keep Kai terse." in s)
        assertTrue(p.systemWithPostHistory().endsWith("Keep Kai terse."))
        assertEquals(listOf("ship", "rule"), p.entries.map { it.id })
    }

    @Test
    fun noPersonaUsesDefaultNameAndSkipsEmptySections() {
        val bare = Character(id = "b", name = "Bare", greeting = "Hi {{user}}")
        val p = PromptAssembler.build(bare, null, emptyList(), "")
        assertFalse("## About" in p.system)
        assertFalse("## Story memory" in p.system)
        assertFalse("## Scenario" in p.system)
        assertNull(p.postHistory)
        assertEquals("Hi User", PromptAssembler.opening(bare, 0, null))
    }

    @Test
    fun regenerationPromptHasNoToolInstructions() {
        val p = PromptAssembler.build(kai, null, emptyList(), "", toolsAllowed = false)
        assertFalse("story_memory_update" in p.system)
        assertTrue("Do not use tools." in p.system)
    }

    @Test
    fun openingPicksGreetingAndClamps() {
        val ana = Persona("Ana")
        assertEquals("Welcome aboard, Ana.", PromptAssembler.opening(kai, 0, ana))
        assertEquals("Late again, Ana?", PromptAssembler.opening(kai, 1, ana))
        assertEquals("Late again, Ana?", PromptAssembler.opening(kai, 9, ana))
    }

    @Test
    fun storeSeedsDefaultAndRestoresIt() = runTest {
        store.load()
        assertEquals(listOf(DefaultCharacter.ID), store.characters.value.map { it.id })
        store.delete(DefaultCharacter.ID)
        assertFalse(store.hasDefault)
        val reopened = CharacterStore(File(dir, "characters"), Dispatchers.Unconfined)
        reopened.load()
        assertTrue(reopened.characters.value.isEmpty())
        reopened.restoreDefault()
        assertTrue(reopened.hasDefault)
    }

    @Test
    fun duplicateSearchAndFavourites() = runTest {
        store.load()
        store.save(kai.copy(tags = listOf("Sci-Fi", "Pilot")))
        val copy = assertNotNull(store.duplicate("kai"))
        assertEquals("Kai (copy)", copy.name)
        store.setFavourite("kai", true)
        val all = store.characters.value
        assertEquals(listOf("kai"), CharacterStore.search(all, "#pilot").filter { it.favourite }.map { it.id })
        assertEquals(2, CharacterStore.search(all, "kai").size)
        assertEquals(listOf(DefaultCharacter.ID), CharacterStore.search(all, "lumen").map { it.id })
        assertEquals(3, CharacterStore.search(all, "  ").size)
        assertTrue(CharacterStore.search(all, "#kai").isEmpty())
    }

    @Test
    fun sessionBuildsRunsKeepsSnapshotAndCandidates() = runTest {
        CharacterSession.installForTest(store)
        store.load()
        store.save(kai)
        store.setPersona(Persona("Ana", "A navigator."))
        store.setStoryMemory("kai", "Ana fixed the radio.")
        val convo = assertNotNull(CharacterSession.startChat("kai", greetingIndex = 1, usePersona = true))
        assertTrue(CharacterSession.isCharacterChat(convo))
        val opening = assertNotNull(CharacterSession.opening(convo))
        assertEquals("Late again, Ana?", opening.text)
        assertEquals("The story begins here", opening.marker)

        val history = listOf(ChatMessage.assistant(opening.text), ChatMessage.user("Nice ship."))
        val run = assertNotNull(CharacterSession.prepareRun(convo, history, contextWindow = 32_000))
        assertTrue("The ship is named Heron." in run.prompt.system)
        assertTrue("Ana fixed the radio." in run.prompt.system)
        assertTrue("## About Ana" in run.prompt.system)
        assertEquals("Kai is replying…", run.replyingLabel)
        assertEquals(setOf("kai"), CharacterSession.activeCharacterIds())

        val tool = CharacterTools.storyMemory({ store }, CharacterSession::activeCharacterIds)
        val ctx = object : ToolContext {
            override val taskId = "t"
            override suspend fun confirm(action: String) = true
        }
        val res = tool.run(buildJsonObject { put("action", JsonPrimitive("append")); put("text", JsonPrimitive("They reached Io.")) }, ctx)
        assertFalse(res.isError, res.text)
        assertEquals("Ana fixed the radio.\nThey reached Io.", store.storyMemory("kai"))
        CharacterSession.endRun(convo)
        assertTrue(tool.run(buildJsonObject { put("action", JsonPrimitive("read")) }, ctx).isError)

        store.delete("kai")
        val afterDelete = assertNotNull(CharacterSession.prepareRun(convo, history))
        assertEquals("Kai", afterDelete.character.name)
        CharacterSession.endRun(convo)

        val set1 = assertNotNull(CharacterSession.addCandidate(convo, "m1", "first", "second"))
        assertEquals(listOf("first", "second"), set1.texts)
        assertEquals("second", set1.current)
        val set2 = assertNotNull(CharacterSession.addCandidate(convo, "m1", "ignored", "third"))
        assertEquals("3 / 3", set2.position)
        assertEquals("first", CharacterSession.selectCandidate(convo, "m1", 0)?.current)
        CharacterSession.replaceCandidate(convo, "m1", "edited first")
        assertEquals(listOf("edited first", "second", "third"), CharacterSession.candidates(convo, "m1")?.texts)

        assertNull(CharacterSession.prepareRun("plain-conversation", history))
        val regen = assertNotNull(CharacterSession.prepareRun(convo, history, regenerate = true))
        assertTrue(regen.filterTools(listOf(tool)).isEmpty())
        CharacterSession.endRun(convo)
    }

    @Test
    fun runFiltersMemoryWriteTools() = runTest {
        CharacterSession.installForTest(store)
        store.load()
        val convo = assertNotNull(CharacterSession.startChat(DefaultCharacter.ID, 0, usePersona = false))
        val run = assertNotNull(CharacterSession.prepareRun(convo, emptyList()))
        val tools = CharacterTools.create({ store }, { emptySet() }) + listOf(named("memory_append"), named("memory_read"))
        assertEquals(setOf("story_memory_update", "list_characters", "memory_read"), run.filterTools(tools).map { it.spec.name }.toSet())
        CharacterSession.endRun(convo)
    }

    @Test
    fun backupRoundTrips() = runTest {
        store.load()
        store.save(kai)
        store.setStoryMemory("kai", "note")
        store.setImage("kai", TestPngs.tiny())
        store.setPersona(Persona("Ana"))
        val backup = store.exportBackup()
        val other = CharacterStore(File(dir, "restored"), Dispatchers.Unconfined)
        other.importBackup(backup)
        assertEquals(store.characters.value.map { it.id }, other.characters.value.map { it.id })
        assertEquals("note", other.storyMemory("kai"))
        assertEquals("Ana", other.persona.value.name)
        assertNotNull(other.imageFile(other.get("kai")!!))
    }

    private fun named(name: String) = object : atom.core.tool.Tool {
        override val spec = atom.core.llm.ToolSpec(name, "", atom.core.tool.schema())
        override val risk = atom.core.tool.Risk.Read
        override suspend fun run(args: kotlinx.serialization.json.JsonObject, ctx: ToolContext) = atom.core.tool.ToolResult.ok("")
    }
}
