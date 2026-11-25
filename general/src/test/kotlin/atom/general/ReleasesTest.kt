package atom.general

import atom.general.update.GithubRelease
import atom.general.update.Releases
import atom.general.update.UpdateChecker
import atom.general.update.UpdateStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReleasesTest {
    private val latestJson = """
        {"url":"https://api.github.com/repos/o/r/releases/1","html_url":"https://github.com/o/r/releases/tag/v1.3.0",
         "id":1,"tag_name":"v1.3.0","name":"Atom 1.3","draft":false,"prerelease":false,
         "published_at":"2026-11-20T10:00:00Z","assets":[{"name":"atom.apk","size":123}],"body":"notes"}
    """.trimIndent()

    @Test fun parsesLatestReleaseIgnoringUnknownFields() {
        val r = Releases.parseRelease(latestJson)
        assertEquals("v1.3.0", r.tagName)
        assertEquals("https://github.com/o/r/releases/tag/v1.3.0", r.htmlUrl)
        assertEquals("Atom 1.3", r.name)
        assertFalse(r.prerelease)
    }

    @Test fun malformedJsonThrows() {
        assertFailsWith<Exception> { Releases.parseRelease("{\"name\":\"x\"}") } // tag_name missing
        assertFailsWith<Exception> { Releases.parseRelease("<html>rate limited</html>") }
    }

    @Test fun listPicksNewestStableBySemver() {
        val list = """[
            {"tag_name":"v2.0.0-beta.1","html_url":"https://github.com/o/r/releases/tag/v2.0.0-beta.1","prerelease":true},
            {"tag_name":"v1.10.0","html_url":"https://github.com/o/r/releases/tag/v1.10.0"},
            {"tag_name":"v1.9.0","html_url":"https://github.com/o/r/releases/tag/v1.9.0"},
            {"tag_name":"v3.0.0","html_url":"","draft":true},
            {"tag_name":"nightly","html_url":""}
        ]"""
        assertEquals("v1.10.0", Releases.parseLatestFromList(list)?.tagName)
    }

    @Test fun evaluateComparesVersions() {
        val r = Releases.parseRelease(latestJson)
        val newer = Releases.evaluate("o/r", "1.2.9", r)
        assertIs<UpdateStatus.Available>(newer)
        assertEquals("1.3.0", newer.latest)
        assertEquals("1.2.9", newer.current)
        assertEquals("https://github.com/o/r/releases/tag/v1.3.0", newer.releaseUrl)
        assertIs<UpdateStatus.UpToDate>(Releases.evaluate("o/r", "1.3.0", r))
        assertIs<UpdateStatus.UpToDate>(Releases.evaluate("o/r", "1.4.0-dev", r))
        assertIs<UpdateStatus.Available>(Releases.evaluate("o/r", "1.3.0-rc.2", r))
        assertIs<UpdateStatus.NoReleases>(Releases.evaluate("o/r", "1.0.0", null))
    }

    @Test fun foreignReleaseLinkFallsBackToRepoPage() {
        val r = GithubRelease(tagName = "v9.0.0", htmlUrl = "https://evil.example/download")
        val s = Releases.evaluate("o/r", "1.0.0", r)
        assertIs<UpdateStatus.Available>(s)
        assertEquals("https://github.com/o/r/releases/latest", s.releaseUrl)
    }

    @Test fun slugValidation() {
        assertTrue(Releases.isValidSlug("owner/repo"))
        assertTrue(Releases.isValidSlug("my-org/atom.next"))
        assertFalse(Releases.isValidSlug("owner"))
        assertFalse(Releases.isValidSlug("https://github.com/o/r"))
        assertFalse(Releases.isValidSlug("o/r/x"))
    }

    @Test fun emptyRepoMeansNotConfiguredWithoutNetwork() = runTest {
        assertEquals(UpdateStatus.NotConfigured, UpdateChecker.check("", "1.0.0"))
        assertEquals(UpdateStatus.NotConfigured, UpdateChecker.check("   ", "1.0.0"))
        assertIs<UpdateStatus.Failed>(UpdateChecker.check("not a slug", "1.0.0"))
    }

    @Test fun defaultsAreEmpty() {
        assertEquals("", GeneralConfig.DEFAULT_UPDATE_REPO)
        assertEquals("", GeneralConfig.DEFAULT_SOURCE_URL)
        assertEquals("", GeneralConfig.DEFAULT_FEEDBACK)
    }
}
