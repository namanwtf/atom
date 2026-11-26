package atom.general.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatePathTest {

    // ---- versions ----

    @Test fun earlierAppVersionIsOlderThanFour() {
        val old = assertNotNull(SemVer.parse("3.4.5-atom.38"))
        val new = assertNotNull(SemVer.parse("v4.0.0"))
        assertTrue(old < new)
        assertTrue(assertNotNull(SemVer.parse("3.4.5")) < new)
        assertTrue(assertNotNull(SemVer.parse("v4.0.0")) < assertNotNull(SemVer.parse("v4.0.1")))
        assertTrue(assertNotNull(SemVer.parse("v4.0.9")) < assertNotNull(SemVer.parse("v4.0.10")))
    }

    /**
     * The earlier app's own updater (described in docs/UPDATES.md): strip a leading "v", drop the CURRENT
     * version's "-suffix" and "+meta", split on ".", compare segment by segment (numbers when both are integers,
     * else strings). Release tags of this app must look newer to it.
     */
    private fun earlierAppSeesNewer(current: String, tag: String): Boolean {
        val mine = current.removePrefix("v").substringBefore('+').substringBefore('-').split('.')
        val latest = tag.removePrefix("v").split('.')
        for (i in 0 until maxOf(mine.size, latest.size)) {
            val a = mine.getOrElse(i) { "0" }
            val b = latest.getOrElse(i) { "0" }
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = if (an != null && bn != null) bn.compareTo(an) else b.compareTo(a)
            if (c != 0) return c > 0
        }
        return false
    }

    @Test fun releaseTagsLookNewerToTheEarlierUpdater() {
        assertTrue(earlierAppSeesNewer("3.4.5-atom.38", "v4.0.0"))
        assertTrue(earlierAppSeesNewer("3.4.5", "v4.0.17"))
        assertFalse(earlierAppSeesNewer("3.4.5", "v3.4.5"))
    }

    @Test fun evaluateOffersFourToTheEarlierVersion() {
        val r = release("v4.0.0", apkAssets("4.0.0"))
        val s = Releases.evaluate(SLUG, "3.4.5-atom.38", r)
        assertIs<UpdateStatus.Available>(s)
        assertEquals("4.0.0", s.latest)
        assertEquals("atom-4.0.0.apk", s.apk?.name)
        assertIs<UpdateStatus.UpToDate>(Releases.evaluate(SLUG, "4.0.0", r))
    }

    // ---- asset picking ----

    @Test fun picksTheVersionedApkAndItsChecksum() {
        val apk = assertNotNull(Releases.pickApk(SLUG, release("v4.0.3", apkAssets("4.0.3"))))
        assertEquals("atom-4.0.3.apk", apk.name)
        assertEquals("$DL/v4.0.3/atom-4.0.3.apk", apk.url)
        assertEquals("$DL/v4.0.3/atom-4.0.3.apk.sha256", apk.sha256Url)
        assertEquals(1234L, apk.size)
    }

    @Test fun parsesAssetsFromGithubJson() {
        val body = """
            {"tag_name":"v4.0.1","html_url":"https://github.com/namanwtf/atom/releases/tag/v4.0.1","assets":[
              {"name":"atom-4.0.1.apk","size":99,"state":"uploaded","content_type":"application/vnd.android.package-archive",
               "browser_download_url":"$DL/v4.0.1/atom-4.0.1.apk"},
              {"name":"atom-4.0.1.apk.sha256","size":80,"state":"uploaded","browser_download_url":"$DL/v4.0.1/atom-4.0.1.apk.sha256"}
            ]}
        """.trimIndent()
        val apk = assertNotNull(Releases.pickApk(SLUG, Releases.parseRelease(body)))
        assertEquals(99L, apk.size)
    }

    @Test fun noChecksumMeansNoInAppInstall() {
        val assets = apkAssets("4.0.0").filterNot { it.name.endsWith(".sha256") }
        assertNull(Releases.pickApk(SLUG, release("v4.0.0", assets)))
        val s = Releases.evaluate(SLUG, "3.4.5", release("v4.0.0", assets))
        assertIs<UpdateStatus.Available>(s)
        assertNull(s.apk) // the About row falls back to opening the release page
    }

    @Test fun rejectsForeignHostsIncompleteUploadsAndOddNames() {
        val foreign = listOf(
            GithubAsset("atom-4.0.0.apk", "https://evil.example/atom-4.0.0.apk", 10),
            GithubAsset("atom-4.0.0.apk.sha256", "https://evil.example/atom-4.0.0.apk.sha256", 10),
        )
        assertNull(Releases.pickApk(SLUG, release("v4.0.0", foreign)))
        val otherRepo = apkAssets("4.0.0", base = "https://github.com/someone/else/releases/download")
        assertNull(Releases.pickApk(SLUG, release("v4.0.0", otherRepo)))
        val open = apkAssets("4.0.0").map { if (it.name.endsWith(".apk")) it.copy(state = "open") else it }
        assertNull(Releases.pickApk(SLUG, release("v4.0.0", open)))
        val empty = apkAssets("4.0.0").map { if (it.name.endsWith(".apk")) it.copy(size = 0) else it }
        assertNull(Releases.pickApk(SLUG, release("v4.0.0", empty)))
        val weird = listOf(asset("../atom-4.0.0.apk"), asset("../atom-4.0.0.apk.sha256"))
        assertNull(Releases.pickApk(SLUG, release("v4.0.0", weird)))
        val notOurs = listOf(asset("other-4.0.0.apk"), asset("other-4.0.0.apk.sha256"))
        assertNull(Releases.pickApk(SLUG, release("v4.0.0", notOurs)))
    }

    @Test fun severalApksNeedOneNamedAfterTheTag() {
        val two = apkAssets("4.0.2") + apkAssets("4.0.1")
        assertEquals("atom-4.0.2.apk", Releases.pickApk(SLUG, release("v4.0.2", two))?.name)
        assertNull(Releases.pickApk(SLUG, release("v4.0.5", two)))
        // A single APK is accepted even when its name does not repeat the tag.
        assertEquals("atom-4.0.1.apk", Releases.pickApk(SLUG, release("v4.0.5", apkAssets("4.0.1")))?.name)
    }

    @Test fun theOnDevicePackIsNeverAnAppUpdate() {
        val withPack = apkAssets("4.0.3") + listOf(
            asset("atom-ondevice-4.0.3.apk", "$DL/v4.0.3"),
            asset("atom-ondevice-4.0.3.apk.sha256", "$DL/v4.0.3"),
        )
        assertEquals("atom-4.0.3.apk", Releases.pickApk(SLUG, release("v4.0.3", withPack))?.name)
        // A release with only the pack offers no app download.
        assertNull(Releases.pickApk(SLUG, release("v4.0.3", withPack.filter { it.name.startsWith("atom-ondevice-") })))
        val pack = assertNotNull(ReleaseFiles.asset(SLUG, release("v4.0.3", withPack), Releases.packAssetName("4.0.3")))
        assertEquals("$DL/v4.0.3/atom-ondevice-4.0.3.apk.sha256", pack.sha256Url)
        assertNull(ReleaseFiles.asset(SLUG, release("v4.0.3", apkAssets("4.0.3")), Releases.packAssetName("4.0.3")))
    }

    @Test fun thePackMustMatchTheAppExactly() {
        val pkg = "io.github.namanwtf.atom.ondevice"
        val pack = UpdateVerify.ApkFacts(pkg, installed.versionCode, setOf(CERT))
        assertEquals(UpdateVerify.PackCheck.Ok, UpdateVerify.checkPack(installed, pack, pkg))
        assertEquals(UpdateVerify.PackCheck.Ok, UpdateVerify.checkPack(installed, pack.copy(signers = setOf(CERT.uppercase())), pkg))
        assertIs<UpdateVerify.PackCheck.WrongVersion>(UpdateVerify.checkPack(installed, pack.copy(versionCode = installed.versionCode + 1), pkg))
        assertIs<UpdateVerify.PackCheck.WrongPackage>(UpdateVerify.checkPack(installed, pack.copy(packageName = "io.github.namanwtf.atom"), pkg))
        assertEquals(UpdateVerify.PackCheck.SignerMismatch, UpdateVerify.checkPack(installed, pack.copy(signers = setOf(OTHER)), pkg))
        // No key rotation for code that runs inside the app.
        assertEquals(
            UpdateVerify.PackCheck.SignerMismatch,
            UpdateVerify.checkPack(installed, pack.copy(signers = setOf(OTHER), signerHistory = listOf(CERT, OTHER)), pkg),
        )
        assertEquals(UpdateVerify.PackCheck.Unreadable, UpdateVerify.checkPack(installed, null, pkg))
    }

    @Test fun onlyTheReleaseBuildInstallsReleasesOverItself() {
        assertTrue(Releases.canSelfUpdate("io.github.namanwtf.atom"))
        assertFalse(Releases.canSelfUpdate("io.github.namanwtf.atom.debug"))
        assertFalse(Releases.canSelfUpdate("io.github.namanwtf.other"))
    }

    // ---- checksums ----

    @Test fun parsesChecksumFiles() {
        val hex = "a".repeat(64)
        assertEquals(hex, UpdateVerify.parseSha256("$hex  atom-4.0.0.apk\n", "atom-4.0.0.apk"))
        assertEquals(hex, UpdateVerify.parseSha256("$hex *atom-4.0.0.apk", "atom-4.0.0.apk"))
        assertEquals(hex, UpdateVerify.parseSha256("${hex.uppercase()}\n", "atom-4.0.0.apk"))
        assertNull(UpdateVerify.parseSha256("$hex  other.apk", "atom-4.0.0.apk"))
        assertNull(UpdateVerify.parseSha256("not a hash  atom-4.0.0.apk", "atom-4.0.0.apk"))
        assertNull(UpdateVerify.parseSha256("abc123", "atom-4.0.0.apk"))
        assertNull(UpdateVerify.parseSha256("", "atom-4.0.0.apk"))
    }

    @Test fun hashCheck() {
        val bytes = "hello".toByteArray()
        val known = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(known, UpdateVerify.sha256Hex(bytes))
        assertEquals(known, UpdateVerify.sha256Hex(bytes.inputStream()))
        assertTrue(UpdateVerify.hashMatches(known.uppercase(), UpdateVerify.sha256Hex(bytes)))
        assertFalse(UpdateVerify.hashMatches(known, UpdateVerify.sha256Hex("hellO".toByteArray())))
        assertFalse(UpdateVerify.hashMatches("", ""))
    }

    // ---- APK identity ----

    private val installed = UpdateVerify.ApkFacts("io.github.namanwtf.atom", 2026112500, setOf(CERT))

    @Test fun acceptsSameAppSameKeyNewerVersion() {
        val c = installed.copy(versionCode = 2026112612)
        assertEquals(UpdateVerify.ApkCheck.Ok, UpdateVerify.check(installed, c))
        assertEquals(UpdateVerify.ApkCheck.Ok, UpdateVerify.check(installed, c.copy(signers = setOf(CERT.uppercase()))))
    }

    @Test fun refusesOtherKeysPackagesAndOlderBuilds() {
        val newer = installed.copy(versionCode = 2026112612)
        assertEquals(UpdateVerify.ApkCheck.SignerMismatch, UpdateVerify.check(installed, newer.copy(signers = setOf(OTHER))))
        assertIs<UpdateVerify.ApkCheck.WrongPackage>(UpdateVerify.check(installed, newer.copy(packageName = "io.github.namanwtf.atom.debug")))
        assertIs<UpdateVerify.ApkCheck.NotNewer>(UpdateVerify.check(installed, installed))
        assertIs<UpdateVerify.ApkCheck.NotNewer>(UpdateVerify.check(installed, installed.copy(versionCode = 1)))
        assertEquals(UpdateVerify.ApkCheck.Unreadable, UpdateVerify.check(installed, null))
        assertEquals(UpdateVerify.ApkCheck.Unreadable, UpdateVerify.check(installed, newer.copy(signers = emptySet())))
        // A second signer is not the same identity.
        assertEquals(UpdateVerify.ApkCheck.SignerMismatch, UpdateVerify.check(installed, newer.copy(signers = setOf(CERT, OTHER))))
    }

    @Test fun acceptsRotatedKeyWhoseLineageHasTheInstalledOne() {
        val rotated = installed.copy(versionCode = 2026112612, signers = setOf(OTHER), signerHistory = listOf(CERT, OTHER))
        assertEquals(UpdateVerify.ApkCheck.Ok, UpdateVerify.check(installed, rotated))
        assertEquals(
            UpdateVerify.ApkCheck.SignerMismatch,
            UpdateVerify.check(installed, rotated.copy(signerHistory = listOf(OTHER))),
        )
    }

    private companion object {
        const val SLUG = "namanwtf/atom"
        const val DL = "https://github.com/namanwtf/atom/releases/download"
        // The earlier app's release certificate (docs/UPDATES.md), as lower-case hex.
        const val CERT = "f3ba9f48e153a99798bb0d7c74c241d8d0c3f9c1db2686f7b50c28f187cb8fa7"
        const val OTHER = "0000000000000000000000000000000000000000000000000000000000000001"

        fun asset(name: String, base: String = "$DL/v4.0.0") = GithubAsset(name, "$base/$name", 1234)

        fun apkAssets(version: String, base: String = DL) = listOf(
            asset("atom-$version.apk", "$base/v$version"),
            asset("atom-$version.apk.sha256", "$base/v$version"),
        )

        fun release(tag: String, assets: List<GithubAsset>) =
            GithubRelease(tagName = tag, htmlUrl = "https://github.com/$SLUG/releases/tag/$tag", assets = assets)
    }
}
