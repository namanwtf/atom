package atom.core.legacy

import atom.core.legacy.PreferencesProto.Value
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte fixtures are built by hand from the protobuf wire format, the way DataStore writes a PreferenceMap. */
class PreferencesProtoTest {
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(out: ByteArrayOutputStream, field: Int, wire: Int) = varint(out, ((field shl 3) or wire).toLong())

    private fun len(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        tag(out, field, 2)
        varint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun bytes(block: (ByteArrayOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also(block).toByteArray()

    private fun entry(key: String, value: ByteArray) = bytes { e ->
        len(e, 1, key.toByteArray())
        len(e, 2, value)
    }

    private fun map(vararg entries: ByteArray) = bytes { m -> entries.forEach { len(m, 1, it) } }

    private fun str(s: String) = bytes { len(it, 5, s.toByteArray()) }
    private fun bool(b: Boolean) = bytes { tag(it, 1, 0); varint(it, if (b) 1 else 0) }
    private fun int(i: Int) = bytes { tag(it, 3, 0); varint(it, i.toLong()) }
    private fun long(l: Long) = bytes { tag(it, 4, 0); varint(it, l) }

    @Test
    fun decodesEveryValueType() {
        val float = bytes { tag(it, 2, 5); val b = java.lang.Float.floatToIntBits(1.5f); for (i in 0 until 4) it.write((b shr (8 * i)) and 0xFF) }
        val double = bytes { tag(it, 7, 1); val b = java.lang.Double.doubleToLongBits(2.25); for (i in 0 until 8) it.write(((b shr (8 * i)) and 0xFF).toInt()) }
        val set = bytes { v -> len(v, 6, bytes { s -> len(s, 1, "a".toByteArray()); len(s, 1, "b".toByteArray()) }) }
        val raw = bytes { len(it, 8, byteArrayOf(1, 2, 3)) }
        val file = map(
            entry("s", str("héllo")),
            entry("b", bool(true)),
            entry("i", int(6)),
            entry("neg", int(-1)),
            entry("l", long(1_764_000_000_000L)),
            entry("f", float),
            entry("d", double),
            entry("set", set),
            entry("raw", raw),
        )
        val prefs = PreferencesProto.decode(file)
        assertEquals(Value.Str("héllo"), prefs["s"])
        assertEquals(Value.Bool(true), prefs["b"])
        assertEquals(Value.Int32(6), prefs["i"])
        assertEquals(Value.Int32(-1), prefs["neg"])
        assertEquals(Value.Int64(1_764_000_000_000L), prefs["l"])
        assertEquals(Value.Float32(1.5f), prefs["f"])
        assertEquals(Value.Float64(2.25), prefs["d"])
        assertEquals(Value.StrSet(setOf("a", "b")), prefs["set"])
        assertTrue((prefs["raw"] as Value.Bytes).value.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun unknownFieldsAreSkipped() {
        val file = bytes { m ->
            tag(m, 9, 0); varint(m, 300)
            len(m, 1, entry("k", bytes { v -> tag(v, 15, 0); varint(v, 1); len(v, 5, "v".toByteArray()) }))
        }
        assertEquals(mapOf<String, Value>("k" to Value.Str("v")), PreferencesProto.decode(file))
    }

    @Test
    fun emptyFileIsAnEmptyMap() {
        assertTrue(PreferencesProto.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun truncatedFilesAreRejected() {
        val good = map(entry("selected_provider_id", str("builtin-openai")))
        assertFailsWith<PreferencesProto.MalformedException> { PreferencesProto.decode(good.copyOf(good.size - 3)) }
        assertFailsWith<PreferencesProto.MalformedException> { PreferencesProto.decode(byteArrayOf(0x0A, 0x7F)) }
    }

    @Test
    fun readsTheSelectionKeys() {
        val file = map(
            entry("selected_provider_id", str("builtin-openai")),
            entry("selected_model_id", str("builtin-openai-gpt-6-luna")),
            entry("selected_model_id_by_provider.builtin-xai", str("m-xai")),
            entry("selected_model_id_by_provider.", str("ignored")),
            entry("official_model_catalog_revision", int(6)),
            entry("memory_enabled", bool(true)),
        )
        val selection = LegacySelection.fromFile(file)
        assertEquals("builtin-openai", selection.providerId)
        assertEquals("builtin-openai-gpt-6-luna", selection.modelRowId)
        assertEquals(mapOf("builtin-xai" to "m-xai"), selection.perProvider)
    }

    @Test
    fun aMissingOrDamagedFileGivesNoSelection() {
        assertEquals(LegacySelection(), LegacySelection.fromFile(null))
        assertEquals(LegacySelection(), LegacySelection.fromFile(byteArrayOf(0x0A, 0x7F)))
    }

    private val providers = listOf(
        LegacyProviderRow("builtin-openai", enabled = false, sortOrder = 0),
        LegacyProviderRow("builtin-deepseek", sortOrder = 2),
        LegacyProviderRow("builtin-xai", sortOrder = 1),
    )
    private val models = listOf(
        LegacyModelRow("o1", "builtin-openai", "gpt-6-luna"),
        LegacyModelRow("x1", "builtin-xai", "grok-5", sortOrder = 1),
        LegacyModelRow("x0", "builtin-xai", "grok-5-mini", sortOrder = 0, enabled = false),
        LegacyModelRow("x2", "builtin-xai", "grok-5-fast", sortOrder = 2),
        LegacyModelRow("d1", "builtin-deepseek", "deepseek-flash"),
    )

    @Test
    fun selectionFollowsTheOldRule() {
        // Selected provider disabled: first enabled provider by sort order (xAI), lowest enabled model.
        assertEquals("x1", LegacySelection("builtin-openai", "o1").resolve(providers, models)?.id)
        // The selected model must belong to the chosen provider.
        assertEquals("x1", LegacySelection("builtin-xai", "d1").resolve(providers, models)?.id)
        // Then the remembered model of that provider.
        assertEquals("x2", LegacySelection("builtin-xai", "d1", mapOf("builtin-xai" to "x2")).resolve(providers, models)?.id)
        // A disabled selected model is skipped.
        assertEquals("x1", LegacySelection("builtin-xai", "x0").resolve(providers, models)?.id)
        assertEquals("d1", LegacySelection("builtin-deepseek", "d1").resolve(providers, models)?.id)
        assertEquals("x1", LegacySelection().resolve(providers, models)?.id)
        assertNull(LegacySelection().resolve(providers.map { it.copy(enabled = false) }, models))
    }

    @Test
    fun modelTargetsDropDanglingReferences() {
        val ids = mapOf("builtin-xai" to "xai", "builtin-deepseek" to "deepseek")
        val has = { p: String, m: String -> p == "xai" && m in setOf("grok-5", "grok-5-fast") }
        assertEquals("xai" to "grok-5", LegacySelection.target("x1", models, ids, has))
        assertNull(LegacySelection.target("deleted-row", models, ids, has))
        assertNull(LegacySelection.target("o1", models, ids, has), "provider not imported")
        assertNull(LegacySelection.target("d1", models, ids, has), "model missing in Atom 4")
        assertNull(LegacySelection.target(null, models, ids, has))
    }

    @Test
    fun credentialFileNamesAreTheSha256OfTheProviderId() {
        assertEquals("de02e4ec751c524334a4eb58e4ed33317c2ffdac51108a492a4e1d7880cd3bb4.bin", LegacyFiles.credentialFileName("builtin-openai"))
        assertEquals("2087d40eec13f1aaf584c519f43868a70114766f3889034b388e9726b1aa5e1f.bin", LegacyFiles.credentialFileName("builtin-xai"))
    }

    @Test
    fun effortAliases() {
        assertEquals(LegacyEffort.Off, LegacyEffort.parse("none"))
        assertEquals(LegacyEffort.XHigh, LegacyEffort.parse("extra-high"))
        assertEquals(LegacyEffort.XHigh, LegacyEffort.parse("x-high"))
        assertEquals(LegacyEffort.Default, LegacyEffort.parse("bogus"))
        assertIs<atom.core.llm.ReasoningEffort>(LegacyEffort.Minimal.effort)
        assertNull(LegacyEffort.Default.effort)
    }
}
