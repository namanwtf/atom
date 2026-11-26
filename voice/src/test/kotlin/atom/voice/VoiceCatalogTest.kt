package atom.voice

import atom.voice.speech.SpeechConfig
import atom.voice.speech.TtsServiceId
import atom.voice.speech.VoiceCatalog
import atom.voice.speech.VoiceGender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceCatalogTest {
    @Test
    fun voicesAreGroupedFemaleThenMale() {
        val qwen = VoiceCatalog.grouped(TtsServiceId.Qwen)
        assertEquals(listOf(VoiceGender.Female, VoiceGender.Male), qwen.map { it.first })
        assertTrue(qwen.first().second.any { it.id == "Cherry" })
        assertTrue(qwen.last().second.any { it.id == "Ethan" })
        assertEquals(VoiceCatalog.qwen.size, qwen.sumOf { it.second.size })
    }

    @Test
    fun ungroupedDefaultsComeFirst() {
        val mimo = VoiceCatalog.grouped(TtsServiceId.Mimo)
        assertEquals(null, mimo.first().first)
        assertEquals(listOf("mimo_default"), mimo.first().second.map { it.id })
        assertEquals(listOf(null, VoiceGender.Female, VoiceGender.Male), mimo.map { it.first })
    }

    @Test
    fun everyPresetIsListedOnce() {
        TtsServiceId.entries.forEach { service ->
            val ids = VoiceCatalog.grouped(service).flatMap { g -> g.second.map { it.id } }
            assertEquals(VoiceCatalog.presets(service).map { it.id }.sorted(), ids.sorted())
        }
        assertTrue(VoiceCatalog.grouped(TtsServiceId.Off).isEmpty())
    }

    @Test
    fun previewIntroducesAtomAndFitsTheLimit() {
        assertTrue(SpeechConfig.DEFAULT_PREVIEW.startsWith("Hi, I'm Atom"))
        assertTrue(SpeechConfig.DEFAULT_PREVIEW.length <= SpeechConfig.PREVIEW_MAX)
    }
}
