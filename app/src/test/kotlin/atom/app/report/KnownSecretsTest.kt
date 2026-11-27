package atom.app.report

import atom.app.data.KeyVault
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The report's exact-match list covers every stored secret, not only provider keys. Fake values throughout. */
class KnownSecretsTest {
    @Test
    fun everyVaultNamespaceCountsButTheUnreadableMarkers() {
        val names = listOf(
            "api.openai", "oauth.chatgpt", "hf.token",
            // Feature modules: web search and image keys, speech keys, and one that does not exist yet.
            "mod.web.brave", "mod.web.image_override", "mod.speech.DEEPGRAM", "mod.later.anything",
            "stale.api.old", "stale.mod.web.tavily",
        )
        assertEquals(
            listOf("api.openai", "oauth.chatgpt", "hf.token", "mod.web.brave", "mod.web.image_override", "mod.speech.DEEPGRAM", "mod.later.anything"),
            KeyVault.storedNames(names),
        )
    }

    // In no well-known key shape and short of the long-token rule: only the exact match can catch these.
    private val webKey = "brv-Kq7!mZ2#x"
    private val speechKey = "dg:Pw9-qL3?r"
    private val mcpToken = "mcp-T0k&n-42"
    private val mcpUrlKey = "Zk4!pQ8-w"
    private val headerValue = "Bearer hdr-Vx3#9z!q"
    private val signIn = """{"access":"acc-7Yq!2m#k","refresh":"ref-4Lp?9w&z","expires":123}"""

    @Test
    fun valuesJsonStringsAndBearerTokensAreAllKnown() {
        val known = KnownSecrets.of(listOf(webKey, speechKey, signIn), listOf(headerValue), listOf(mcpToken, mcpUrlKey))
        for (s in listOf(webKey, speechKey, mcpToken, mcpUrlKey, headerValue, "hdr-Vx3#9z!q", "acc-7Yq!2m#k", "ref-4Lp?9w&z")) {
            assertTrue(s in known, "$s missing from $known")
        }
        // Too short to match safely, and numbers inside a sign-in: left out.
        assertFalse("123" in known)
        assertTrue(KnownSecrets.of(listOf("short"), emptyList(), emptyList()).isEmpty())
        assertEquals(known.sortedByDescending { it.length }, known, "longest first")
    }

    @Test
    fun theReportCarriesNoneOfThem() {
        val known = KnownSecrets.of(listOf(webKey, speechKey, signIn), listOf(headerValue), listOf(mcpToken, mcpUrlKey))
        val input = ReportInput(
            createdAt = 1_764_150_000_000,
            zone = ZoneId.of("UTC"),
            app = AppFacts("4.0.6", 2026120100, "io.github.namanwtf.atom", debugBuild = false),
            device = DeviceFacts("15", 35, "Google", "Pixel 6", rom = null, build = "AP3A.241005.015"),
            note = "search failed with $webKey, speech with $speechKey, MCP said $mcpToken (url ...&k=$mcpUrlKey), sign-in acc-7Yq!2m#k",
            log = listOf(
                "11-26 10:00:01.000  4321  4350 W AtomWeb: search key $webKey rejected",
                "11-26 10:00:02.000  4321  4350 W AtomMcp: auth hdr-Vx3#9z!q and $mcpToken failed",
            ),
        )
        val text = ProblemReport.text(input, known)
        for (s in listOf(webKey, speechKey, mcpToken, mcpUrlKey, "hdr-Vx3#9z!q", "acc-7Yq!2m#k")) {
            assertFalse(s in text, "a stored secret survived: $s\n$text")
        }
    }
}
