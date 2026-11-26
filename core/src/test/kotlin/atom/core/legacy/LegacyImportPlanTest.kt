package atom.core.legacy

import atom.core.legacy.LegacyImportPlan.ConversationAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyImportPlanTest {
    private val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val valid = { id: String -> id.all { it.isLetterOrDigit() || it == '-' } }

    @Test
    fun runsAutomaticallyOnlyUntilARunFinished() {
        assertTrue(LegacyImportPlan.shouldAutoRun(databaseExists = true, state = null))
        assertTrue(LegacyImportPlan.shouldAutoRun(true, LegacyImportState(conversations = mapOf("a" to "a"))), "an interrupted run resumes")
        assertFalse(LegacyImportPlan.shouldAutoRun(true, LegacyImportState(completedAt = 5)))
        assertFalse(LegacyImportPlan.shouldAutoRun(false, null))
    }

    @Test
    fun conversationsAreImportedOnceUnderTheirOldId() {
        val store = HashSet<String>()
        var state = LegacyImportState()
        fun run(): Int {
            var imported = 0
            for (old in listOf(uuid, "weird id/../x")) {
                val (action, target) = LegacyImportPlan.conversation(state, old, valid, { it in store }, { "new-$old".filter(Char::isLetterOrDigit) })
                if (action == ConversationAction.Import) {
                    store += target
                    imported++
                }
                if (action != ConversationAction.AlreadyImported) state = state.copy(conversations = state.conversations + (old to target))
            }
            return imported
        }
        assertEquals(2, run())
        assertEquals(setOf(uuid, "newweirdidx"), store)
        assertEquals(0, run(), "a re-run duplicates nothing")
        // Deleted in Atom 4 after the import: not brought back.
        store.remove(uuid)
        assertEquals(0, run())
    }

    @Test
    fun aConversationSavedBeforeTheMarkerWasWrittenIsNotDuplicated() {
        val (action, target) = LegacyImportPlan.conversation(LegacyImportState(), uuid, valid, { it == uuid }, { error("unused") })
        assertEquals(ConversationAction.ExistsInAtom4, action)
        assertEquals(uuid, target)
    }

    @Test
    fun keysAreAddedOnlyWhenMissing() {
        val s = LegacyImportState()
        assertTrue(LegacyImportPlan.shouldImportKey(s, "p", "sk-1", atom4HasKey = false))
        assertFalse(LegacyImportPlan.shouldImportKey(s, "p", "sk-1", atom4HasKey = true), "never overwrite an Atom 4 key")
        assertFalse(LegacyImportPlan.shouldImportKey(s, "p", " ", atom4HasKey = false))
        assertFalse(LegacyImportPlan.shouldImportKey(s, "p", null, atom4HasKey = false))
        assertFalse(LegacyImportPlan.shouldImportKey(s.copy(keys = setOf("p")), "p", "sk-1", atom4HasKey = false))
    }

    @Test
    fun signInsAreImportedOnceAndNeverOverAnAtom4SignIn() {
        val oauth = LegacyProviderRow("builtin-openai", authMode = LegacyProviderRow.AUTH_CHATGPT)
        val s = LegacyImportState()
        assertTrue(LegacyImportPlan.shouldImportSignIn(s, oauth, atom4SignedIn = false))
        assertFalse(LegacyImportPlan.shouldImportSignIn(s, oauth, atom4SignedIn = true))
        assertFalse(LegacyImportPlan.shouldImportSignIn(s.copy(signIns = setOf("builtin-openai")), oauth, false))
        assertFalse(LegacyImportPlan.shouldImportSignIn(s, oauth.copy(authMode = "api_key"), false))
        // Gemini rows always behave as API-key rows.
        assertFalse(LegacyImportPlan.shouldImportSignIn(s, oauth.copy(type = LegacyProviderRow.TYPE_GEMINI), false))
    }

    @Test
    fun theSelectionIsAppliedOnceAndNotOverAnAtom4Choice() {
        assertTrue(LegacyImportPlan.shouldApplySelection(LegacyImportState(), chosenInAtom4 = false))
        assertFalse(LegacyImportPlan.shouldApplySelection(LegacyImportState(), chosenInAtom4 = true))
        assertFalse(LegacyImportPlan.shouldApplySelection(LegacyImportState(selectionApplied = true), false))
    }

    @Test
    fun theMarkerRoundTripsAndToleratesGarbage() {
        val s = LegacyImportState(
            completedAt = 7,
            providers = mapOf("builtin-openai" to "openai"),
            keys = setOf("builtin-openai"),
            conversations = mapOf("a" to "a"),
            report = LegacyImportReport(at = 7, conversations = 1, signInAgain = listOf("xAI")),
            noticeShown = false,
        )
        assertEquals(s, LegacyImportState.decode(s.encode()))
        assertNull(LegacyImportState.decode("{"))
        assertNull(LegacyImportState.decode(""))
        assertNull(LegacyImportState.decode(null))
    }

    @Test
    fun theSeededPromptIsTheOneBuiltInsShare() {
        val rows = listOf(
            LegacyProviderRow("builtin-openai", builtIn = true, systemPrompt = "You are Atom."),
            LegacyProviderRow("builtin-xai", builtIn = true, systemPrompt = "You are Atom."),
            LegacyProviderRow("builtin-kimi", builtIn = true, systemPrompt = "Custom"),
            LegacyProviderRow("x", builtIn = false, systemPrompt = "Custom"),
        )
        assertEquals("You are Atom.", LegacyImportPlan.seededPrompt(rows))
        assertNull(LegacyImportPlan.seededPrompt(rows.take(1)))
    }

    @Test
    fun apiKeysOfOAuthRowsAreNeverUsed() {
        assertEquals("sk", LegacyProviderRow("a", apiKey = "sk").usableApiKey)
        assertNull(LegacyProviderRow("a", apiKey = "sk", authMode = LegacyProviderRow.AUTH_GROK).usableApiKey)
        assertEquals("g", LegacyProviderRow("a", apiKey = "g", type = LegacyProviderRow.TYPE_GEMINI, authMode = LegacyProviderRow.AUTH_GROK).usableApiKey)
        assertNull(LegacyProviderRow("a", apiKey = "  ").usableApiKey)
    }
}
