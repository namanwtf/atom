package atom.general

import atom.general.backup.BackupEngine
import atom.general.backup.BackupProblem
import atom.general.backup.ImportResult
import atom.general.backup.ParseResult
import atom.general.backup.ValidationResult
import atom.ui.feature.BackupContributor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** In-memory contributor holding one string; can be told to fail at each step. */
private class Fake(
    override val key: String,
    var value: String,
    override val containsSecrets: Boolean = false,
    var failImport: Boolean = false,
    var failImportOnlyFor: String? = null,
    var failSnapshot: Boolean = false,
    var rejectWith: String? = null,
) : BackupContributor {
    override val label: Int = 0
    val writes = mutableListOf<String>()

    override suspend fun export(): JsonElement {
        if (failSnapshot) error("disk read failed")
        return JsonObject(mapOf("v" to JsonPrimitive(value)))
    }

    override suspend fun validate(data: JsonElement): String? {
        rejectWith?.let { return it }
        val o = data as? JsonObject ?: return "not an object"
        return if ((o["v"] as? JsonPrimitive)?.isString == true) null else "missing v"
    }

    override suspend fun import(data: JsonElement) {
        val v = data.jsonObject.getValue("v").jsonPrimitive.content
        if (failImport && (failImportOnlyFor == null || failImportOnlyFor == v)) error("write failed")
        writes += v
        value = v
    }
}

class BackupEngineTest {
    private fun engine(vararg c: BackupContributor) = BackupEngine("1.2.3", { c.toList() }, { 1_764_000_000_000L })

    private suspend fun exported(vararg c: Fake): String = engine(*c).export()

    private fun parseOk(e: BackupEngine, text: String) = assertIs<ParseResult.Ok>(e.parse(text)).file

    @Test fun headerHasFormatVersionAppVersionTimeKeysAndSecretsFlag() = runTest {
        val a = Fake("a", "1")
        val b = Fake("b", "2", containsSecrets = true)
        val text = exported(a, b)
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("atom-backup", root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("formatVersion").jsonPrimitive.content.toInt())
        assertEquals("1.2.3", root.getValue("appVersion").jsonPrimitive.content)
        assertEquals("2025-11-24T16:00:00Z", root.getValue("createdAt").jsonPrimitive.content)
        assertEquals(listOf("a", "b"), root.getValue("contributors").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", root.getValue("containsSecrets").jsonPrimitive.content)
        val h = parseOk(engine(a, b), text).header
        assertEquals(listOf("a", "b"), h.keys)
        assertTrue(h.containsSecrets)
        assertEquals(1_764_000_000_000L, h.createdAtMillis)
        assertTrue(engine(a, b).exportContainsSecrets())
    }

    @Test fun rejectsCorruptForeignAndUnsupportedFiles() = runTest {
        val e = engine(Fake("a", "1"))
        assertEquals(ParseResult.Invalid(BackupProblem.Empty), e.parse("  "))
        assertEquals(ParseResult.Invalid(BackupProblem.NotJson), e.parse("{\"format\":\"atom-backup\""))
        assertEquals(ParseResult.Invalid(BackupProblem.Foreign), e.parse("[1,2]"))
        assertEquals(ParseResult.Invalid(BackupProblem.Foreign), e.parse("{\"format\":\"other-app\",\"formatVersion\":1}"))
        assertEquals(ParseResult.Invalid(BackupProblem.UnsupportedVersion(2)), e.parse("{\"format\":\"atom-backup\",\"formatVersion\":2,\"contributors\":[],\"data\":{}}"))
        assertIs<BackupProblem.Corrupt>((e.parse("{\"format\":\"atom-backup\",\"formatVersion\":1}") as ParseResult.Invalid).problem)
        // Section list and data disagree.
        assertIs<BackupProblem.Corrupt>(
            (e.parse("{\"format\":\"atom-backup\",\"formatVersion\":1,\"contributors\":[\"a\",\"b\"],\"data\":{\"a\":{}}}") as ParseResult.Invalid).problem,
        )
    }

    @Test fun everyContributorValidatesBeforeAnythingIsWritten() = runTest {
        val a = Fake("a", "old-a")
        val b = Fake("b", "old-b")
        val text = exported(Fake("a", "new-a"), Fake("b", "new-b"))
        b.rejectWith = "bad data"
        val e = engine(a, b)
        val file = parseOk(e, text)
        val v = e.validate(file)
        assertIs<ValidationResult.Invalid>(v)
        assertEquals(listOf<BackupProblem>(BackupProblem.SectionInvalid("b", "bad data")), v.problems)
        val r = e.import(file) { false }
        assertIs<ImportResult.Invalid>(r)
        assertTrue(a.writes.isEmpty() && b.writes.isEmpty(), "nothing written")
        assertEquals("old-a", a.value)
    }

    @Test fun importReplacesAllSectionsAndRoundTrips() = runTest {
        val a = Fake("a", "old-a")
        val b = Fake("b", "old-b")
        val text = exported(Fake("a", "new-a"), Fake("b", "new-b"), Fake("zzz", "from-newer-app"))
        val e = engine(a, b, Fake("c", "keep-c"))
        val file = parseOk(e, text)
        val plan = assertIs<ValidationResult.Ok>(e.validate(file)).plan
        assertEquals(listOf("zzz"), plan.unknownKeys)
        assertEquals(listOf("c"), plan.untouchedKeys)
        val r = e.import(file) { false }
        assertEquals(ImportResult.Success(listOf("a", "b"), listOf("zzz")), r)
        assertEquals("new-a", a.value)
        assertEquals("new-b", b.value)
    }

    @Test fun refusesWhileConversationsSave() = runTest {
        val a = Fake("a", "old")
        val e = BackupEngine("1.2.3", { listOf(a) }, { 1_764_000_000_000L }, saving = { true })
        val file = parseOk(e, exported(Fake("a", "new")))
        assertEquals(ImportResult.Saving, e.import(file) { false })
        assertEquals(ImportResult.Busy, e.import(file) { true })
        assertEquals("old", a.value)
        assertTrue(a.writes.isEmpty())
    }

    @Test fun refusesWhileTasksRun() = runTest {
        val a = Fake("a", "old")
        val e = engine(a)
        val file = parseOk(e, exported(Fake("a", "new")))
        assertEquals(ImportResult.Busy, e.import(file) { true })
        assertEquals("old", a.value)
        assertTrue(a.writes.isEmpty())
    }

    @Test fun failureRollsBackEveryImportedSection() = runTest {
        val a = Fake("a", "old-a")
        val b = Fake("b", "old-b")
        val c = Fake("c", "old-c", failImport = true, failImportOnlyFor = "new-c")
        val e = engine(a, b, c)
        val file = parseOk(e, exported(Fake("a", "new-a"), Fake("b", "new-b"), Fake("c", "new-c")))
        val r = e.import(file) { false }
        assertIs<ImportResult.Failed>(r)
        assertEquals("c", r.failedKey)
        assertTrue(r.rollbackFailures.isEmpty())
        assertEquals("old-a", a.value)
        assertEquals("old-b", b.value)
        assertEquals("old-c", c.value)
        // a and b were imported and then restored from their snapshots, newest first.
        assertEquals(listOf("new-a", "old-a"), a.writes)
        assertEquals(listOf("new-b", "old-b"), b.writes)
        assertEquals(listOf("old-c"), c.writes)
    }

    @Test fun reportsSectionsWhoseRollbackAlsoFailed() = runTest {
        val a = Fake("a", "old-a")
        val b = Fake("b", "old-b", failImport = true) // fails for both new and snapshot data
        val e = engine(a, b)
        val file = parseOk(e, exported(Fake("a", "new-a"), Fake("b", "new-b")))
        val r = assertIs<ImportResult.Failed>(e.import(file) { false })
        assertEquals("b", r.failedKey)
        assertEquals(listOf("b"), r.rollbackFailures)
        assertEquals("old-a", a.value)
    }

    @Test fun snapshotFailureAbortsBeforeAnyWrite() = runTest {
        val a = Fake("a", "old-a")
        val b = Fake("b", "old-b")
        val e = engine(a, b)
        val file = parseOk(e, exported(Fake("a", "new-a"), Fake("b", "new-b")))
        b.failSnapshot = true
        val r = assertIs<ImportResult.Failed>(e.import(file) { false })
        assertEquals("b", r.failedKey)
        assertTrue(a.writes.isEmpty() && b.writes.isEmpty())
    }

    @Test fun fileWithNothingKnownIsRejected() = runTest {
        val e = engine(Fake("a", "x"))
        val file = parseOk(e, exported(Fake("other", "y")))
        assertEquals(ValidationResult.Invalid(listOf(BackupProblem.NothingToImport)), e.validate(file))
    }

    @Test fun fileNameIsStable() {
        assertTrue(BackupEngine.fileName(0L).matches(Regex("atom-backup-\\d{8}-\\d{4}\\.json")))
    }
}
