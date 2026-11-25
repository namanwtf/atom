package atom.extend

import atom.extend.skills.BuiltInSkills
import atom.extend.skills.GitHubKind
import atom.extend.skills.GitHubLocation
import atom.extend.skills.GitHubSkillSource
import atom.extend.skills.RemoteFile
import atom.extend.skills.SkillException
import atom.extend.skills.SkillInstaller
import atom.extend.skills.SkillLimits
import atom.extend.skills.SkillManifest
import atom.extend.skills.SkillStore
import atom.extend.skills.SkillZip
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.nio.file.Files as NioFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun skillMd(name: String, description: String = "Does $name things. Use when asked.") =
    "---\nname: $name\ndescription: $description\n---\n\n# $name\nSteps.\n"

private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        entries.forEach { (name, bytes) ->
            z.putNextEntry(ZipEntry(name))
            z.write(bytes)
            z.closeEntry()
        }
    }
    return out.toByteArray()
}

private object NoBuiltIns : BuiltInSkills {
    override fun names() = listOf("skill-creator")
    override fun files(name: String) = listOf("SKILL.md")
    override fun read(name: String, path: String) = if (name == "skill-creator" && path == "SKILL.md") skillMd("skill-creator").toByteArray() else null
}

class SkillsTest {
    private fun tempDir(): File = NioFiles.createTempDirectory("skills").toFile()

    @Test
    fun manifestParsesFrontmatterVariants() {
        val m = SkillManifest.parse("---\nname: pdf-tools\ndescription: >\n  Fill PDF forms.\n  Use for PDFs.\n---\nBody").getOrThrow()
        assertEquals("pdf-tools", m.name)
        assertEquals("Fill PDF forms. Use for PDFs.", m.description)
        assertEquals("Body", m.body)
        val quoted = SkillManifest.parse("---\nname: \"a-b\"\ndescription: 'It''s: fine'\n---\n").getOrThrow()
        assertEquals("It's: fine", quoted.description)
        assertTrue(SkillManifest.parse("---\nname: Bad Name\ndescription: x\n---\n").isFailure)
        assertTrue(SkillManifest.parse("no frontmatter").isFailure)
        val rendered = SkillManifest("a", "Has: colon", "Body").render()
        assertEquals("Has: colon", SkillManifest.parse(rendered).getOrThrow().description)
    }

    @Test
    fun unsafePathsAreRejected() {
        listOf("../evil", "a/../../evil", "/abs/path", "C:/win", "a\\b", "a//b", "./x", "").forEach {
            assertNull(SkillZip.safeRelativePath(it), it)
        }
        assertEquals("a/b/c.md", SkillZip.safeRelativePath("a/b/c.md"))
        assertEquals("dir", SkillZip.safeRelativePath("dir/"))
    }

    @Test
    fun zipSlipEntryFailsAndWritesNothingOutside() {
        val dir = tempDir()
        val bytes = zip("SKILL.md" to skillMd("x").toByteArray(), "../../escaped.txt" to "pwned".toByteArray())
        assertFailsWith<SkillException> { SkillZip.extract(ByteArrayInputStream(bytes), File(dir, "stage")) }
        assertFalse(File(dir, "escaped.txt").exists())
        assertFalse(File(dir.parentFile, "escaped.txt").exists())
    }

    @Test
    fun sizeLimitsCountInflatedBytes() {
        val big = ByteArray(2_000) { 'a'.code.toByte() }
        val bytes = zip("s/SKILL.md" to skillMd("s").toByteArray(), "s/big.txt" to big)
        val limits = SkillLimits(maxFileBytes = 1_000)
        val e = assertFailsWith<SkillException> { SkillZip.extract(ByteArrayInputStream(bytes), tempDir(), limits) }
        assertTrue("larger than" in e.message!!)
        assertFailsWith<SkillException> { SkillZip.extract(ByteArrayInputStream(bytes), tempDir(), SkillLimits(maxTotalBytes = 1_500)) }
        assertFailsWith<SkillException> { SkillZip.extract(ByteArrayInputStream(bytes), tempDir(), SkillLimits(maxArchiveBytes = 100)) }
        assertFailsWith<SkillException> { SkillZip.extract(ByteArrayInputStream(bytes), tempDir(), SkillLimits(maxFiles = 1)) }
    }

    @Test
    fun oneSkillPerZip() {
        val two = zip("a/SKILL.md" to skillMd("a").toByteArray(), "b/SKILL.md" to skillMd("b").toByteArray())
        assertFailsWith<SkillException> { SkillZip.extract(ByteArrayInputStream(two), tempDir()) }
        val none = zip("readme.md" to "hi".toByteArray())
        assertFailsWith<SkillException> { SkillZip.extract(ByteArrayInputStream(none), tempDir()) }
        assertFailsWith<SkillException> { SkillZip.extract(ByteArrayInputStream("not a zip".toByteArray()), tempDir()) }
    }

    @Test
    fun nestedFolderSkillExtractsWithResources() {
        val bytes = zip(
            "__MACOSX/._junk" to ByteArray(1),
            "my-skill/SKILL.md" to skillMd("my-skill").toByteArray(),
            "my-skill/templates/SKILL.md" to "template, not a skill".toByteArray(),
            "my-skill/scripts/run.py" to "print(1)".toByteArray(),
        )
        val staged = SkillZip.extract(ByteArrayInputStream(bytes), tempDir())
        assertEquals("my-skill", staged.manifest.name)
        assertTrue(File(staged.dir, "scripts/run.py").isFile)
    }

    @Test
    fun installIsTransactionalAndBuiltInsAreProtected(): Unit = runBlocking {
        val root = tempDir()
        val store = SkillStore(root, NoBuiltIns)
        val installer = SkillInstaller(store, GitHubSkillSource({ _, _, _ -> HttpReply(404, ByteArray(0)) }))
        val r1 = installer.importZip(ByteArrayInputStream(zip("SKILL.md" to skillMd("notes").toByteArray()))) { true }
        assertEquals(listOf("notes"), r1.installed)
        assertNotNull(store.get("notes"))

        val declined = installer.importZip(ByteArrayInputStream(zip("SKILL.md" to skillMd("notes", "New version. Use always.").toByteArray()))) { false }
        assertTrue(declined.installed.isEmpty())
        assertEquals("Does notes things. Use when asked.", store.get("notes")!!.manifest.description)

        val replaced = installer.importZip(ByteArrayInputStream(zip("SKILL.md" to skillMd("notes", "New version. Use always.").toByteArray()))) { true }
        assertEquals(listOf("notes"), replaced.installed)
        assertEquals("New version. Use always.", store.get("notes")!!.manifest.description)

        val builtIn = installer.importZip(ByteArrayInputStream(zip("SKILL.md" to skillMd("skill-creator").toByteArray()))) { true }
        assertTrue(builtIn.installed.isEmpty())
        assertFalse(File(root, "user/skill-creator").exists())
        assertFalse(store.delete("skill-creator"))
        assertFalse(File(root, ".staging").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun interruptedInstallIsRolledBack() {
        val root = tempDir()
        File(root, "user/old").mkdirs()
        File(root, "user/old/SKILL.md").writeText(skillMd("old", "Original. Use it."))
        val backup = File(root, ".trash/tx/old").apply { mkdirs() }
        File(backup, "SKILL.md").writeText(skillMd("old", "Original. Use it."))
        File(root, "user/old/SKILL.md").writeText(skillMd("old", "Half-installed. Broken."))
        File(root, "user/fresh").mkdirs()
        File(root, "user/fresh/SKILL.md").writeText(skillMd("fresh"))
        File(root, "install-journal.json").writeText(
            """{"entries":[{"name":"old","backup":"${backup.path}"},{"name":"fresh"}]}""",
        )
        val store = SkillStore(root, NoBuiltIns)
        assertEquals("Original. Use it.", store.get("old")!!.manifest.description)
        assertNull(store.get("fresh"))
        assertFalse(File(root, "install-journal.json").exists())
    }

    @Test
    fun bundledBuiltInsAreValidSkills() {
        val dir = File("src/main/assets/extend/skills")
        val names = dir.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()
        assertEquals(listOf("self-improving-agent", "skill-creator", "skill-installer"), names)
        names.forEach { n ->
            val m = SkillManifest.parse(File(dir, "$n/SKILL.md").readText()).getOrThrow()
            assertEquals(n, m.name)
            assertTrue(m.oneLine.length <= 160)
            assertTrue(m.body.length > 200)
        }
    }

    @Test
    fun enableSwitchPersists() {
        val root = tempDir()
        val store = SkillStore(root, NoBuiltIns)
        store.setEnabled("skill-creator", false)
        assertFalse(SkillStore(root, NoBuiltIns).get("skill-creator")!!.enabled)
        assertTrue(SkillStore(root, NoBuiltIns).enabled().isEmpty())
    }
}

class GitHubTest {
    @Test
    fun parsesRepoTreeBlobRawAndShorthand() {
        val repo = GitHubLocation.parse("https://github.com/anthropics/skills")!!
        assertEquals(GitHubKind.Repo, repo.kind)
        assertEquals("anthropics" to "skills", repo.owner to repo.repo)

        assertEquals(GitHubKind.Repo, GitHubLocation.parse("github.com/o/r.git/")!!.kind)
        assertEquals("r", GitHubLocation.parse("o/r")!!.repo)

        val tree = GitHubLocation.parse("https://github.com/o/r/tree/main/skills/pdf?tab=readme#x")!!
        assertEquals(GitHubKind.Tree, tree.kind)
        assertEquals("main" to "skills/pdf", tree.candidates.first())
        assertEquals("main/skills" to "pdf", tree.candidates[1])

        val blob = GitHubLocation.parse("https://github.com/o/r/blob/feature/x/skills/a/SKILL.md")!!
        assertEquals(GitHubKind.Blob, blob.kind)
        assertTrue(("feature/x" to "skills/a/SKILL.md") in blob.candidates)

        val raw = GitHubLocation.parse("https://raw.githubusercontent.com/o/r/refs/heads/main/a/SKILL.md")!!
        assertEquals(GitHubKind.Blob, raw.kind)
        assertEquals("main" to "a/SKILL.md", raw.candidates.first())

        val spaced = GitHubLocation.parse("https://github.com/o/r/tree/main/My%20Skill")!!
        assertEquals("main" to "My Skill", spaced.candidates.first())
    }

    @Test
    fun rejectsNonGitHubAndMalformed() {
        listOf(
            "https://gitlab.com/o/r",
            "https://github.com/o",
            "https://github.com/o/r/issues/1",
            "ftp://github.com/o/r",
            "https://github.com/o/r/blob/main",
            "not a url at all",
            "https://github.com/../r",
        ).forEach { assertNull(GitHubLocation.parse(it), it) }
    }

    @Test
    fun skillDirsPicksOutermostUnderPath() {
        val files = listOf(
            RemoteFile("README.md", 1),
            RemoteFile("skills/a/SKILL.md", 1),
            RemoteFile("skills/a/templates/SKILL.md", 1),
            RemoteFile("skills/b/SKILL.md", 1),
            RemoteFile("other/c/SKILL.md", 1),
        )
        assertEquals(listOf("other/c", "skills/a", "skills/b"), GitHubSkillSource.skillDirs(files, "", GitHubKind.Repo))
        assertEquals(listOf("skills/a", "skills/b"), GitHubSkillSource.skillDirs(files, "skills", GitHubKind.Tree))
        assertEquals(listOf("skills/b"), GitHubSkillSource.skillDirs(files, "skills/b/SKILL.md", GitHubKind.Blob))
        assertEquals(emptyList(), GitHubSkillSource.skillDirs(files, "README.md", GitHubKind.Blob))
    }

    @Test
    fun installsFromTreeThroughApiAndRawUrls(): Unit = runBlocking {
        val requested = mutableListOf<String>()
        val tree = """{"truncated":false,"tree":[
            {"path":"skills/pdf/SKILL.md","type":"blob","size":80},
            {"path":"skills/pdf/ref/forms.md","type":"blob","size":5},
            {"path":"skills/other/SKILL.md","type":"blob","size":80},
            {"path":"skills/pdf/ref","type":"tree"}]}"""
        val http = HttpGet { url, _, _ ->
            requested += url
            when {
                url == "https://api.github.com/repos/o/r/git/trees/main?recursive=1" -> HttpReply(200, tree.toByteArray())
                url.endsWith("/skills/pdf/SKILL.md") -> HttpReply(200, skillMd("pdf").toByteArray())
                url.endsWith("/skills/pdf/ref/forms.md") -> HttpReply(200, "forms".toByteArray())
                else -> HttpReply(404, ByteArray(0))
            }
        }
        val root = NioFiles.createTempDirectory("gh").toFile()
        val store = SkillStore(root, NoBuiltIns)
        val result = SkillInstaller(store, GitHubSkillSource(http)).installGitHub("https://github.com/o/r/tree/main/skills/pdf", null) { true }
        assertEquals(listOf("pdf"), result.report.installed)
        assertEquals("forms", store.read("pdf", "ref/forms.md")!!.toString(Charsets.UTF_8))
        assertTrue(requested.none { it.contains("skills/other") })
        assertTrue(requested.contains("https://raw.githubusercontent.com/o/r/main/skills/pdf/SKILL.md"))
    }

    @Test
    fun tooManySkillsAsksToNarrow(): Unit = runBlocking {
        val entries = (1..25).joinToString(",") { """{"path":"s$it/SKILL.md","type":"blob","size":10}""" }
        val http = HttpGet { url, _, _ ->
            when {
                url.endsWith("/repos/o/r") -> HttpReply(200, """{"default_branch":"dev"}""".toByteArray())
                url.contains("/git/trees/dev") -> HttpReply(200, """{"tree":[$entries]}""".toByteArray())
                else -> HttpReply(404, ByteArray(0))
            }
        }
        val store = SkillStore(NioFiles.createTempDirectory("gh").toFile(), NoBuiltIns)
        val e = assertFailsWith<SkillException> { SkillInstaller(store, GitHubSkillSource(http)).installGitHub("o/r", null) { true } }
        assertTrue("at most 20" in e.message!!)
    }

    @Test
    fun failedDownloadInstallsNothing(): Unit = runBlocking {
        val tree = """{"tree":[{"path":"a/SKILL.md","type":"blob","size":10},{"path":"b/SKILL.md","type":"blob","size":10}]}"""
        val http = HttpGet { url, _, _ ->
            when {
                url.contains("/git/trees/main") -> HttpReply(200, tree.toByteArray())
                url.endsWith("/a/SKILL.md") -> HttpReply(200, skillMd("a").toByteArray())
                else -> HttpReply(500, ByteArray(0))
            }
        }
        val root = NioFiles.createTempDirectory("gh").toFile()
        val store = SkillStore(root, NoBuiltIns)
        assertFailsWith<SkillException> {
            SkillInstaller(store, GitHubSkillSource(http)).installGitHub("https://github.com/o/r/tree/main", null) { true }
        }
        assertNull(store.get("a"))
        assertTrue(File(root, ".staging").listFiles().orEmpty().isEmpty())
    }
}
