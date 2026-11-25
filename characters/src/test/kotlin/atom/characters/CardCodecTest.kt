package atom.characters

import atom.characters.card.CardCodec
import atom.characters.card.CardException
import atom.characters.card.CardSpec
import atom.characters.model.CharacterOrigin
import atom.characters.model.Placement
import atom.characters.model.SecondaryLogic
import atom.characters.png.Png
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardCodecTest {
    private var n = 0
    private fun id() = "id${n++}"

    @Test
    fun parsesV1FlatCard() {
        val card = CardCodec.readJson(
            """{"name":" Mira ","description":"A pilot.\r\nBrave.","personality":"bold","scenario":"In orbit",
               "first_mes":"Hello {{user}}","mes_example":"<START>\n{{char}}: hi"}""",
            now = 5, newId = ::id,
        )
        assertEquals(CardSpec.V1, card.spec)
        val c = card.character
        assertEquals("Mira", c.name)
        assertEquals("A pilot.\nBrave.", c.profile)
        assertEquals("bold", c.personality)
        assertEquals("In orbit", c.scenario)
        assertEquals("Hello {{user}}", c.greeting)
        assertEquals(CharacterOrigin.Imported, c.origin)
        assertEquals(5, c.createdAt)
    }

    @Test
    fun parsesLegacyFieldNames() {
        val c = CardCodec.readJson(
            """{"char_name":"Old","char_persona":"p","world_scenario":"w","char_greeting":"g","example_dialogue":"e"}""",
            newId = ::id,
        ).character
        assertEquals(listOf("Old", "p", "w", "g", "e"), listOf(c.name, c.profile, c.scenario, c.greeting, c.exampleDialogue))
    }

    @Test
    fun parsesV2WithBookAndNormalises() {
        val json = """
        {"spec":"chara_card_v2","spec_version":"2.0","data":{
          "name":"Kai","description":"d","personality":null,"scenario":"","first_mes":"Hi",
          "mes_example":"","creator_notes":"notes","system_prompt":"Be Kai. {{original}}",
          "post_history_instructions":"Stay short","alternate_greetings":["Yo"," ",null],
          "tags":["Sci-Fi","sci-fi"," Robot ",""],"creator":"me","character_version":7,
          "extensions":{"talkativeness":"0.5","atom":{"credits":"Art by A"}},
          "character_book":{"name":"Lore","scan_depth":3,"token_budget":"500","recursive_scanning":true,
            "entries":[
              {"keys":["ship","Vessel"],"content":"The ship is old.","enabled":true,"insertion_order":10,
               "case_sensitive":false,"selective":true,"secondary_keys":["engine"],"constant":false,
               "position":"after_char","id":4,"name":"Ship","priority":5,
               "extensions":{"selectiveLogic":3,"match_whole_words":false,"exclude_recursion":true,"custom":1}},
              {"keys":"moon, tide","content":"Moon lore","enabled":false,"insertion_order":20,"position":"before_char","id":4}
            ]}
        }}""".trimIndent()
        val parsed = CardCodec.readJson(json, newId = ::id)
        assertEquals(CardSpec.V2, parsed.spec)
        val c = parsed.character
        assertEquals("", c.personality)
        assertEquals(listOf("Yo"), c.alternateGreetings)
        assertEquals(listOf("Sci-Fi", "Robot"), c.tags)
        assertEquals("7", c.version)
        assertEquals("Art by A", c.credits)
        assertNull(c.extensions?.get("atom"))
        assertNotNull(c.extensions?.get("talkativeness"))
        val book = assertNotNull(c.worldbook)
        assertEquals(3, book.scanDepth)
        assertEquals(500, book.tokenBudget)
        assertTrue(book.recursiveScanning)
        val (ship, moon) = book.entries
        assertEquals(listOf("ship", "Vessel"), ship.keys)
        assertTrue(ship.requireSecondary)
        assertEquals(SecondaryLogic.AndAll, ship.secondaryLogic)
        assertEquals(Placement.AfterDefinition, ship.placement)
        assertEquals(10, ship.insertionOrder)
        assertEquals(5, ship.priority)
        assertEquals(false, ship.wholeWords)
        assertTrue(ship.excludeRecursion)
        assertNotNull(ship.extensions?.get("custom"))
        assertEquals(listOf("moon", "tide"), moon.keys)
        assertEquals(false, moon.enabled)
        assertTrue(ship.id != moon.id)
    }

    @Test
    fun parsesV3Fields() {
        val json = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Ada","nickname":"Addy",
            "description":"x","first_mes":"Hey","creator_notes":"","creator_notes_multilingual":{"en":"English notes"},
            "assets":[{"type":"icon","uri":"ccdefault:","name":"main","ext":"png"}],"group_only_greetings":["g"]}}"""
        val parsed = CardCodec.readJson(json, newId = ::id)
        assertEquals(CardSpec.V3, parsed.spec)
        assertEquals("Addy", parsed.character.nickname)
        assertEquals("Addy", parsed.character.displayName)
        assertEquals("English notes", parsed.character.creatorNotes)
    }

    @Test
    fun sillyTavernWorldEntriesGetCompatibilityNotes() {
        val json = """{"name":"W","character_book":{"entries":{
            "0":{"uid":0,"key":["a"],"keysecondary":["b"],"selective":true,"selectiveLogic":2,"content":"c",
                 "order":50,"position":4,"depth":2,"disable":false,"probability":40,"useProbability":true},
            "1":{"uid":1,"key":["/dr(a|e)gon/i"],"content":"d","position":1,"group":"beasts"}}}}"""
        val book = CardCodec.readJson(json, newId = ::id).character.worldbook!!
        val (a, b) = book.entries
        assertEquals(SecondaryLogic.NotAny, a.secondaryLogic)
        assertEquals(50, a.insertionOrder)
        assertEquals(Placement.AfterDefinition, a.placement)
        val note = assertNotNull(a.compatibilityNote)
        assertTrue("depth 2" in note && "40%" in note, note)
        assertEquals(Placement.AfterDefinition, b.placement)
        assertTrue(b.compatibilityNote!!.contains("beasts"))
        assertEquals("/dr(a|e)gon/i", b.keys.single())
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<CardException> { CardCodec.readJson("not json") }
        assertFailsWith<CardException> { CardCodec.readJson("[1,2]") }
        assertFailsWith<CardException> { CardCodec.readJson("""{"description":"no name"}""") }
        assertFailsWith<CardException> { CardCodec.readPng(TestPngs.tiny()) }
    }

    @Test
    fun jsonExportRoundTrips() {
        val source = CardCodec.readJson(
            """{"spec":"chara_card_v2","data":{"name":"Kai","description":"d","first_mes":"Hi","alternate_greetings":["Yo"],
            "tags":["a"],"post_history_instructions":"p","extensions":{"atom":{"credits":"c"}},
            "character_book":{"entries":[{"keys":["k"],"secondary_keys":["s"],"selective":true,"content":"x",
            "insertion_order":3,"position":"after_char","extensions":{"selectiveLogic":1}}]}}}""",
            newId = ::id,
        ).character
        val again = CardCodec.readJson(CardCodec.exportJson(source), newId = { source.id })
        assertEquals(CardSpec.V3, again.spec)
        val c = again.character
        assertEquals(source.copy(createdAt = c.createdAt, updatedAt = c.updatedAt, worldbook = null), c.copy(worldbook = null))
        val e = c.worldbook!!.entries.single()
        val s = source.worldbook!!.entries.single()
        assertEquals(s.copy(id = e.id), e)
    }

    @Test
    fun pngExportCarriesV2AndV3AndReadsBack() {
        val c = CardCodec.readJson("""{"name":"Zed","description":"ünïcode ✓","first_mes":"Hi"}""", newId = ::id).character
        val png = CardCodec.exportPng(c, TestPngs.tiny())
        val texts = Png.texts(Png.readChunks(png))
        val v2 = String(Base64.getDecoder().decode(texts.getValue("chara")))
        assertTrue("chara_card_v2" in v2)
        assertTrue("chara_card_v3" in String(Base64.getDecoder().decode(texts.getValue("ccv3"))))
        val back = CardCodec.readPng(png, newId = ::id)
        assertEquals(CardSpec.V3, back.spec)
        assertEquals("ünïcode ✓", back.character.profile)
        val stripped = CardCodec.stripCard(png)
        assertTrue(Png.texts(Png.readChunks(stripped)).isEmpty())
    }

    @Test
    fun pngFallsBackToCharaWhenCcv3IsBroken() {
        val chara = Base64.getEncoder().encodeToString("""{"name":"Legacy","first_mes":"Hi"}""".toByteArray())
        val png = TestPngs.tiny(listOf(Png.textChunk("ccv3", "!!!notbase64"), Png.textChunk("chara", chara)))
        assertEquals("Legacy", CardCodec.readPng(png, newId = ::id).character.name)
    }
}
