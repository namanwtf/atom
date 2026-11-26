package atom.app.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStatusTest {
    /** Fresh install: DeepSeek Flash is the default selection but has no key. Every screen says so the same way. */
    @Test fun freshInstallDefaultWithoutKeyNeedsAKey() {
        assertTrue(ModelStatus.keyMissing(providerEnabled = true, apiKeyAuth = true, keyOptional = false, hasKey = false, modelExists = true))
        val home = EngineInfo("DeepSeek Flash", onDevice = false, missing = false, ready = false, reason = NotReady.NeedsKey, providerName = "DeepSeek")
        assertEquals(ModelStatus.NeedsKey("DeepSeek Flash"), home.status)
        assertEquals(home.status, ModelStatus.of("DeepSeek Flash", NotReady.NeedsKey, "DeepSeek"))
        assertFalse(home.status.ready)
    }

    @Test fun aKeyMakesItReady() {
        assertFalse(ModelStatus.keyMissing(providerEnabled = true, apiKeyAuth = true, keyOptional = false, hasKey = true, modelExists = true))
        assertEquals(ModelStatus.Ready("DeepSeek Flash"), ModelStatus.of("DeepSeek Flash", null, "DeepSeek"))
        assertEquals(ModelStatus.Ready("DeepSeek Flash"), EngineInfo("DeepSeek Flash", onDevice = false, missing = false).status)
    }

    @Test fun keyOptionalProvidersAndOtherProblemsAreNotAboutKeys() {
        // Local servers and custom endpoints need no key.
        assertFalse(ModelStatus.keyMissing(providerEnabled = true, apiKeyAuth = true, keyOptional = true, hasKey = false, modelExists = true))
        // A provider that is off, an account provider, or a removed model is "Not set", not "add key".
        assertFalse(ModelStatus.keyMissing(providerEnabled = false, apiKeyAuth = true, keyOptional = false, hasKey = false, modelExists = true))
        assertFalse(ModelStatus.keyMissing(providerEnabled = true, apiKeyAuth = false, keyOptional = false, hasKey = false, modelExists = true))
        assertFalse(ModelStatus.keyMissing(providerEnabled = true, apiKeyAuth = true, keyOptional = false, hasKey = false, modelExists = false))
        assertEquals(ModelStatus.NotSet, ModelStatus.of("DeepSeek Flash", NotReady.Configure, "DeepSeek"))
    }

    @Test fun noSelectionIsNotSetEverywhere() {
        assertEquals(ModelStatus.NotSet, ModelStatus.of(null, NotReady.Configure, null))
        assertEquals(ModelStatus.NotSet, EngineInfo(null, onDevice = false, missing = false, ready = false, reason = NotReady.Configure).status)
        assertEquals(ModelStatus.NotSet, ModelStatus.of(" ", null, null))
    }

    @Test fun otherReasonsKeepTheirOwnState() {
        assertEquals(ModelStatus.SignIn("ChatGPT"), ModelStatus.of("GPT-6", NotReady.SignIn, "ChatGPT"))
        assertEquals(ModelStatus.SignIn("Grok"), ModelStatus.of(null, NotReady.SignIn, "Grok"))
        assertEquals(ModelStatus.KeyUnreadable("Kimi K3"), ModelStatus.of("Kimi K3", NotReady.KeyUnreadable, "Kimi"))
        assertEquals(ModelStatus.NeedsPack("Gemma"), ModelStatus.of("Gemma", NotReady.NeedsPack, null))
    }

    /** A running task shows its engine as ready, whatever the saved selection's state. */
    @Test fun aLiveRunIsReady() {
        val live = EngineInfo("Qwen 3B", onDevice = false, missing = false, live = true, ready = true)
        assertEquals(ModelStatus.Ready("Qwen 3B"), live.status)
    }
}
