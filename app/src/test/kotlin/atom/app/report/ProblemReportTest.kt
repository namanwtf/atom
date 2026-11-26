package atom.app.report

import atom.app.data.StoredMessage
import atom.core.agent.Scrubber
import atom.core.llm.ChatMessage
import atom.core.llm.ContentPart
import atom.core.llm.ToolCall
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProblemReportTest {
    // Fake values in the shapes real keys have. None of them is a real credential.
    private val openAi = "sk-proj-" + "Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56"
    private val anthropic = "sk-ant-api03-" + "Zx98Yw76Vu54Ts32Rq10Po98Nm76Lk54"
    private val google = "AIza" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"
    private val github = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
    private val slack = "xoxb-" + "123456789012-1234567890123-AbCdEfGhIjKlMnOpQrStUvWx"
    private val hf = "hf_" + "AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val aws = "AKIA" + "IOSFODNN7EXAMPLE"
    private val jwt = "eyJhbGciOiJIUzI1NiJ9" + ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" + ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV"
    private val oauth = "ya29." + "a0AfH6SMBx1Yz2Wv3Ut4Sr5Qp6On7Ml8Kj9"
    private val unknownShape = "Qm9vZ2llV29vZ2llMTIzNDU2Nzg5MEFCQ0RFRkdISUo"

    /** Saved by the person (a custom provider's key in no known format): only the exact match can catch it. */
    private val saved = "Tr0ub4dor&3-horse"

    private val seeds = listOf(openAi, anthropic, google, github, slack, hf, aws, jwt, oauth, unknownShape, saved)

    private fun stepsWithSecrets(): List<StoredMessage> {
        val search = ToolCall("c1", "web_search", """{"query":"flights to Rome","key":"$google"}""")
        val sms = ToolCall("c2", "send_sms", """{"to":"+39 333 123 4567","text":"see you at 8","silent":true}""")
        val notes = ToolCall("c3", "notifications_current", "{}")
        return listOf(
            StoredMessage(ChatMessage.user("text Anna that I'm late")),
            StoredMessage(ChatMessage.assistant("", listOf(search, sms))),
            StoredMessage(ChatMessage.toolResult(search, listOf(ContentPart.Text("Results for flights to Rome: ..."))), durationMs = 900),
            StoredMessage(
                ChatMessage.toolResult(sms, listOf(ContentPart.Text("HTTP 401 Authorization: Bearer $openAi rejected for anna@example.com"))),
                isError = true,
                durationMs = 1500,
            ),
            StoredMessage(ChatMessage.assistant("", listOf(notes))),
            StoredMessage(ChatMessage.toolResult(notes, listOf(ContentPart.Text(Scrubber.placeholder("notifications_current")))), isError = true),
        )
    }

    private fun input(conversation: String? = null, note: String = "") = ReportInput(
        createdAt = 1_764_150_000_000,
        zone = ZoneId.of("UTC"),
        app = AppFacts("4.1.0", 2026120100, "io.github.namanwtf.atom", debugBuild = false),
        device = DeviceFacts("15", 35, "Google", "Pixel 6", rom = null, build = "AP3A.241005.015"),
        note = note,
        run = RunFacts(
            outcome = "Failed",
            error = "Provider said: 401 invalid x-api-key: $anthropic (request to https://api.example.com/v1/messages?key=$google)",
            source = "Chat",
            model = "Example · model-1",
            startedAt = 1_764_149_990_000,
            durationMs = 4200,
            steps = ProblemReport.steps(stepsWithSecrets(), 0),
        ),
        exits = listOf(
            ExitFacts(1_764_100_000_000, "Crash", "java.lang.IllegalStateException: token=$hf", trace = listOf("at atom.app.Foo.bar(Foo.kt:12) $slack")),
        ),
        log = listOf(
            "11-26 10:00:00.123  4321  4350 W AtomTools: calling with api_key=$saved",
            "11-26 10:00:01.456  4321  4350 E AtomContext: refresh failed $oauth for john.doe@example.org +44 20 7946 0958",
            "11-26 10:00:02.789  4321  4350 I AtomOverlay: aws $aws jwt $jwt",
            "11-26 10:00:03.000  4321  4350 D AtomPack: blob $unknownShape done",
            "11-26 10:00:04.000  4321  4350 E AndroidRuntime: FATAL EXCEPTION: main $github",
        ),
        conversation = conversation,
        screenshot = true,
    )

    /** Anything that still looks like a credential after redaction, found with patterns of the test's own. */
    private val keyLike = listOf(
        Regex("sk-[A-Za-z0-9_-]{10,}"),
        Regex("AIza[0-9A-Za-z_-]{10,}"),
        Regex("gh[pousr]_[A-Za-z0-9]{10,}"),
        Regex("xox[abprs]-[A-Za-z0-9-]{6,}"),
        Regex("hf_[A-Za-z0-9]{10,}"),
        Regex("eyJ[A-Za-z0-9_-]{10,}"),
        Regex("ya29\\.[A-Za-z0-9_-]{6,}"),
        Regex("A[KS]IA[0-9A-Z]{12,}"),
        Regex("(?i)bearer\\s+(?!\\[redacted\\])\\S{6,}"),
        Regex("(?i)\\b(api[_-]?key|x-api-key|token|secret|password|authorization)\\s*[\"']?\\s*[:=]\\s*[\"']?(?!\\[redacted\\])[A-Za-z0-9]"),
        Regex("(?<![A-Za-z0-9_-])(?=[A-Za-z0-9_-]*\\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}"),
    )

    private fun assertNoSecrets(text: String) {
        for (s in seeds) assertFalse(s in text, "a seeded secret survived: $s\n$text")
        for (r in keyLike) {
            val hit = r.find(text)
            assertNull(hit, "key-like string in the report: '${hit?.value}' (pattern ${r.pattern})\n$text")
        }
    }

    @Test fun reportCarriesNoKeyLikeStrings() {
        val conversation = "## User\n\nmy key is $openAi and the HF one $hf, token: $github\n\n## Atom\n\nGot it $jwt"
        val text = ProblemReport.text(input(conversation, note = "it broke after I pasted $anthropic and $saved"), known = listOf(saved))
        assertNoSecrets(text)
    }

    @Test fun reportWithoutConversationOrNoteCarriesNoKeyLikeStrings() {
        assertNoSecrets(ProblemReport.text(input(), known = listOf(saved)))
    }

    @Test fun reportKeepsWhatHelpsFindTheProblem() {
        val text = ProblemReport.text(input(), known = listOf(saved))
        assertTrue("Atom 4.1.0 (2026120100)" in text)
        assertTrue("Android 15 (API 35)" in text)
        assertTrue("Model: Google Pixel 6" in text)
        assertTrue("Outcome: Failed" in text)
        assertTrue("1. web_search(query: text, key: text) · ok" in text, text)
        assertTrue("2. send_sms(to: text, text: text, silent: true) · failed" in text, text)
        assertTrue("3. notifications_current() · failed" in text, text)
        assertTrue("(sensitive result, left out)" in text)
        assertTrue("HTTP 401" in text)
        assertTrue("W AtomTools:" in text)
        assertTrue("Screenshot attached." in text)
        assertTrue("https://api.example.com/[path]" in text, text)
    }

    @Test fun toolArgumentsAndPersonalDataStayOut() {
        val text = ProblemReport.text(input(), known = listOf(saved))
        for (personal in listOf("flights to Rome", "see you at 8", "+39 333 123 4567", "Anna", "anna@example.com", "john.doe@example.org", "+44 20 7946 0958", "Results for")) {
            assertFalse(personal in text, "personal data in the report: $personal")
        }
        assertTrue("[email]" in text)
        assertTrue("[number]" in text)
    }

    @Test fun buildNamesAreKept() {
        val build = "sdk_gphone64_x86_64-userdebug 15 AE3A.240806.043 12960925 dev-keys"
        val text = ProblemReport.text(input().copy(device = input().device.copy(build = build)))
        assertTrue("Build: $build" in text, text)
    }

    @Test fun conversationIsOnlyThereWhenChosen() {
        assertFalse("Conversation (included by you)" in ProblemReport.text(input()))
        assertTrue("Conversation (included by you)" in ProblemReport.text(input(conversation = "## User\n\nhello")))
    }

    @Test fun unreadableLogIsSaid() {
        val text = ProblemReport.text(input().copy(log = null))
        assertTrue("Could not be read on this phone." in text)
    }

    @Test fun logIsCappedAtTheLastLines() {
        val lines = (1..500).map { "11-26 10:00:00.000  1  1 I AtomTools: line $it" }
        val text = ProblemReport.text(input().copy(log = lines))
        assertTrue("Atom log (last ${ProblemReport.MAX_LOG_LINES} lines)" in text)
        assertTrue("line 500" in text)
        assertFalse("line 300\n" in text)
    }

    @Test fun storedOutcomeReadsAFailedTurn() {
        val messages = stepsWithSecrets() + StoredMessage.notice("Runtime failed: timeout", now = 2_000).copy(isError = true)
        val facts = ProblemReport.storedOutcome(messages, ProblemReport.lastTurnStart(messages))
        assertEquals("Failed", facts.outcome)
        assertEquals("Runtime failed: timeout", facts.error)
        assertEquals(3, facts.steps.size)
    }

    @Test fun argumentShapesNeverShowValues() {
        assertEquals("to: text, n: number, on: false, list: list, o: object, z: null", ProblemReport.argShape("""{"to":"x","n":3,"on":false,"list":[1],"o":{},"z":null}"""))
        assertEquals("unreadable arguments", ProblemReport.argShape("not json"))
        assertEquals("", ProblemReport.argShape(""))
    }

    @Test fun romNames() {
        assertEquals("HyperOS OS2.0", RomNames.detect(mapOf("ro.mi.os.version.name" to "OS2.0", "ro.miui.ui.version.name" to "V140")::get))
        assertEquals("MIUI V140", RomNames.detect(mapOf("ro.miui.ui.version.name" to "V140")::get))
        assertEquals("One UI 6.1", RomNames.detect(mapOf("ro.build.version.oneui" to "60100")::get))
        assertNull(RomNames.detect { null })
    }
}
