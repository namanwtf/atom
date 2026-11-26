package atom.core.legacy

import atom.core.io.deleteTreeNoFollow
import atom.core.legacy.LegacyCleanup.Offer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyCleanupTest {
    private val day = 24L * 60 * 60 * 1000
    private val done = 1_000_000L
    private val dirs = ArrayList<File>()

    @AfterTest
    fun cleanUp() {
        dirs.forEach { it.deleteTreeNoFollow() }
    }

    private fun tempDir(): File = Files.createTempDirectory("atom-legacy-cleanup").toFile().also { dirs += it }

    @Test
    fun cleansSevenDaysAfterACleanImportOnly() {
        val state = LegacyImportState(completedAt = done)
        assertEquals(7 * day, LegacyCleanup.DELAY_MS)
        assertFalse(LegacyCleanup.shouldAutoClean(state, done))
        assertFalse(LegacyCleanup.shouldAutoClean(state, done + 7 * day - 1))
        assertTrue(LegacyCleanup.shouldAutoClean(state, done + 7 * day))
        assertTrue(LegacyCleanup.shouldAutoClean(state, done + 30 * day))
        // A clock set back before the import waits.
        assertFalse(LegacyCleanup.shouldAutoClean(state, done - day))
    }

    @Test
    fun neverCleansWithoutACleanImport() {
        assertFalse(LegacyCleanup.shouldAutoClean(null, Long.MAX_VALUE))
        val failing = LegacyImportState(attempts = 3, report = LegacyImportReport(at = done, error = "disk"))
        assertFalse(LegacyCleanup.shouldAutoClean(failing, done + 365 * day))
        assertFalse(LegacyCleanup.shouldAutoClean(LegacyImportState(report = LegacyImportReport(failedConversations = 1)), done + 365 * day))
    }

    @Test
    fun cleansOnce() {
        val state = LegacyImportState(completedAt = done)
        val cleaned = LegacyCleanup.markCleaned(state, done + 8 * day)
        assertEquals(done + 8 * day, cleaned.legacyCleanedAt)
        assertFalse(LegacyCleanup.shouldAutoClean(cleaned, done + 100 * day))
        assertEquals(cleaned, LegacyCleanup.markCleaned(cleaned, done + 9 * day), "the first time is kept")
        // Survives the marker file.
        val decoded = LegacyImportState.decode(cleaned.encode())
        assertEquals(done + 8 * day, decoded?.legacyCleanedAt)
        assertNull(LegacyImportState.decode(LegacyImportState(completedAt = done).encode())?.legacyCleanedAt)
        assertNull(LegacyImportState.decode("""{"completedAt":5}""")?.legacyCleanedAt, "markers written before this field")
    }

    @Test
    fun theBackupRowFollowsTheImport() {
        assertEquals(Offer.Hidden, LegacyCleanup.offer(oldFilesExist = false, databaseExists = false, state = LegacyImportState(completedAt = done)))
        assertEquals(Offer.ImportFirst, LegacyCleanup.offer(true, true, null))
        assertEquals(Offer.ImportFirst, LegacyCleanup.offer(true, true, LegacyImportState(attempts = 1)), "a first run is still to finish")
        assertEquals(Offer.Ready, LegacyCleanup.offer(true, true, LegacyImportState(completedAt = done)))
        val failed = LegacyImportState(attempts = 1, report = LegacyImportReport(error = "disk"))
        assertEquals(Offer.Unimported, LegacyCleanup.offer(true, true, failed))
        assertEquals(Offer.Unimported, LegacyCleanup.offer(true, true, LegacyImportState(attempts = LegacyImportPlan.MAX_AUTO_ATTEMPTS)))
        // A later failed re-run does not undo an earlier clean import.
        assertEquals(Offer.Ready, LegacyCleanup.offer(true, true, failed.copy(completedAt = done)))
        // Nothing to import without the database.
        assertEquals(Offer.Ready, LegacyCleanup.offer(true, false, null))
    }

    @Test
    fun removesOnlyThePreviousAppsNames() {
        val atom4Names = setOf(
            "atom4_settings", "atom4_providers", "atom4_vault", "atom4_runtime", "atom4_appearance",
            "atom4_notification_history.db", "atom4_overlay", "atom4_web", "atom4_speech", "atom4_litert_backend",
            "atom4_root", "atom4_charge_limit", "atom4_tool_switches", "atom4_system", "atom4_clock",
            "atom4_automation_places", "atom4_extend_secrets", "atom4.vault", "atom4_conversations", "atom4_run-journal",
            "atom4_models", "atom4_characters", "atom4_linux", "atom4_workspace", "atom4_extend", "atom4_attachments",
            "atom4_shared", "atom4_generated_images", "atom4_automation", "atom4_undo_journal.json",
            "atom4_firewall_rules.json", "atom4_legacy_import.json", "atom4_import", "atom4_updates",
        )
        val roots = setOf("databases", "files", "no_backup", "shared_prefs")
        for (target in LegacyCleanup.TARGETS) {
            val parts = target.path.split('/')
            assertTrue(parts.first() in roots, target.path)
            assertTrue(parts.size >= 2 && parts.none { it.isEmpty() || it == "." || it == ".." }, target.path)
            for (part in parts) {
                assertFalse(part.startsWith("atom4"), target.path)
                assertFalse(part.substringBefore('.') in atom4Names || part in atom4Names, target.path)
            }
            // Never one of the stores Atom 4 itself keeps there.
            assertFalse(parts.last().removeSuffix(".preferences_pb").removeSuffix(".xml") in atom4Names, target.path)
        }
        assertEquals(LegacyCleanup.TARGETS.size, LegacyCleanup.TARGETS.map { it.path }.toSet().size)
        assertEquals(LegacyCleanup.TARGETS.first(), LegacyCleanup.DATABASE, "the database goes first")
        for (key in LegacyCleanup.KEYS) {
            assertFalse(key.alias.startsWith("atom4"), key.alias)
            assertFalse(key.alias in atom4Names, key.alias)
            assertTrue(key.after.isNotEmpty() && key.after.all { it in LegacyCleanup.TARGETS }, key.alias)
        }
        assertEquals(LegacyCleanup.KEYS.size, LegacyCleanup.KEYS.map { it.alias }.toSet().size)
        assertEquals(
            listOf("databases/atom.db", "databases/atom.db-wal", "databases/atom.db-shm", "databases/atom.db-journal"),
            LegacyCleanup.TARGETS.map { it.path }.filter { it.startsWith("databases/") },
        )
        assertTrue(LegacyCleanup.KEYS.any { it.alias == "atom_provider_credentials_v1" && it.after == listOf(LegacyCleanup.CREDENTIALS) })
        val paths = LegacyCleanup.TARGETS.map { it.path }
        // The saved secrets, each with the copy a crashed commit leaves, and the settings' unfinished write.
        for (name in listOf("atom_mcp_secrets", "atom_speech_secrets", "atom_image_gen_secrets", "atom_web_tools_secrets")) {
            assertTrue("shared_prefs/$name.xml" in paths, name)
            assertTrue("shared_prefs/$name.xml.bak" in paths, name)
        }
        assertTrue("files/datastore/atom_settings.preferences_pb.tmp" in paths)
        assertEquals(
            listOf("files/memory/MEMORY.md", "files/memory/MEMORY.md.bak", "files/memory/MEMORY.md.new"),
            LegacyCleanup.TARGETS.filter { it.memory }.map { it.path },
        )
        val webTools = LegacyCleanup.KEYS.single { it.alias == "atom_web_tools_credentials_v1" }
        assertEquals(listOf("shared_prefs/atom_web_tools_secrets.xml", "shared_prefs/atom_web_tools_secrets.xml.bak"), webTools.after.map { it.path })
    }

    @Test
    fun keepsTheOldMemoryUntilItWasImported() {
        val data = tempDir()
        val memory = File(data, "files/memory/MEMORY.md").apply { parentFile.mkdirs(); writeText("- likes tea") }
        File(data, "databases/atom.db").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(listOf(LegacyCleanup.DATABASE), LegacyCleanup.existing(data, memoryImported = false))
        assertEquals(emptyList(), LegacyCleanup.deleteFiles(data, memoryImported = false))
        assertTrue(memory.isFile, "not imported: kept")
        assertEquals(listOf(LegacyCleanup.MEMORY), LegacyCleanup.existing(data, memoryImported = true))
        assertEquals(emptyList(), LegacyCleanup.deleteFiles(data, memoryImported = true))
        assertFalse(memory.exists())
    }

    @Test
    fun deletesTheOldFilesAndKeepsEverythingElse() {
        val data = tempDir()
        val outside = tempDir()
        val precious = File(outside, "keep.txt").apply { writeText("x") }
        fun file(path: String) = File(data, path).apply { parentFile.mkdirs(); writeText("x") }
        for (t in LegacyCleanup.TARGETS) if (!t.directory) file(t.path)
        assertTrue(LegacyCleanup.TARGETS.single { it.directory } == LegacyCleanup.CREDENTIALS)
        file("no_backup/provider_credentials/a.bin")
        file("no_backup/provider_credentials/b.bin.bak")
        // A link inside the credentials folder is removed as a link, never followed.
        Files.createSymbolicLink(File(data, "no_backup/provider_credentials/link").toPath(), outside.toPath())
        val keep = listOf(
            "databases/atom4_notification_history.db",
            "databases/androidx.work.workdb",
            "files/datastore/atom4_settings.preferences_pb",
            "files/atom4_legacy_import.json",
            "files/bubble_history.json",
            "no_backup/atom4_other.bin",
            "shared_prefs/atom4_speech.xml",
            "shared_prefs/atom4_extend_secrets.xml",
            "shared_prefs/atom4_web.xml",
            "files/atom4_extend/MEMORY.md",
            "files/memory/notes.txt",
        ).map(::file)

        assertEquals(LegacyCleanup.TARGETS, LegacyCleanup.existing(data, memoryImported = true))
        assertEquals(emptyList(), LegacyCleanup.deleteFiles(data, memoryImported = true))
        assertEquals(emptyList(), LegacyCleanup.existing(data, memoryImported = true))
        keep.forEach { assertTrue(it.isFile, it.path) }
        assertTrue(precious.isFile)
        assertTrue(File(data, "files/datastore").isDirectory, "Atom 4's DataStore folder stays")

        // Idempotent: nothing left to do.
        assertEquals(emptyList(), LegacyCleanup.deleteFiles(data, memoryImported = true))
        keep.forEach { assertTrue(it.isFile, it.path) }
    }

    @Test
    fun reportsWhatIsLeft() {
        val data = tempDir()
        // A non-empty folder where a file is expected cannot be removed with File.delete.
        File(data, "databases/atom.db-wal/inner").apply { parentFile.mkdirs(); writeText("x") }
        File(data, "databases/atom.db").apply { writeText("x") }
        assertEquals(listOf(LegacyCleanup.DATABASE_WAL), LegacyCleanup.deleteFiles(data, memoryImported = false))
        assertFalse(File(data, "databases/atom.db").exists())
    }
}
