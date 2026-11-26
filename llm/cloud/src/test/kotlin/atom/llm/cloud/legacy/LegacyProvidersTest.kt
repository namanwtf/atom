package atom.llm.cloud.legacy

import atom.core.legacy.LegacyModelRow
import atom.core.legacy.LegacyProviderRow
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.CapabilitySource
import atom.llm.cloud.ChatGptAuth
import atom.llm.cloud.Family
import atom.llm.cloud.GrokAuth
import atom.llm.cloud.HeaderEntry
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.PresetCatalog
import atom.llm.cloud.ReasoningInfo
import atom.llm.cloud.ReasoningMode
import atom.llm.cloud.Wire
import atom.llm.cloud.legacy.LegacyProviders.SignIn
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyProvidersTest {
    private fun builtIn(id: String, type: String = LegacyProviderRow.TYPE_OPENAI, endpoint: String = "chat_completions", url: String = "") =
        LegacyProviderRow(id = id, type = type, name = id.removePrefix("builtin-"), baseUrl = url, builtIn = true, endpointMode = endpoint)

    @Test
    fun everyOldBuiltInMapsToAnAtom4Preset() {
        val expected = mapOf(
            "builtin-openai" to "openai", "builtin-anthropic" to "anthropic", "builtin-dashscope" to "qwen",
            "builtin-deepseek" to "deepseek", "builtin-kimi" to "moonshot", "builtin-mimo" to "mimo",
            "builtin-minimax" to "minimax", "builtin-stepfun" to "stepfun", "builtin-siliconflow" to "siliconflow",
            "builtin-openrouter" to "openrouter", "builtin-xai" to "xai", "builtin-gemini" to "gemini",
        )
        assertEquals(expected, LegacyProviders.PRESET_IDS)
        for ((old, preset) in expected) {
            val type = when (old) {
                "builtin-anthropic" -> LegacyProviderRow.TYPE_ANTHROPIC
                "builtin-gemini" -> LegacyProviderRow.TYPE_GEMINI
                else -> LegacyProviderRow.TYPE_OPENAI
            }
            val config = LegacyProviders.toConfig(builtIn(old, type), emptyList())
            val p = assertNotNull(PresetCatalog.byId(preset), preset)
            assertEquals(preset, config.id)
            assertEquals(preset, config.presetId)
            assertEquals(p.family, config.family)
            assertTrue(config.wire in p.wires, "$old: ${config.wire}")
            assertEquals(p.baseUrl, config.baseUrl, "blank URLs fall back to the preset")
            assertEquals(p.models.map { it.id }, config.models.map { it.id }, "official models are kept")
            assertEquals(PresetCatalog.DEFAULT_SYSTEM_PROMPT, config.systemPrompt)
        }
        // Not flagged built-in: a custom provider, whatever its id.
        assertNull(LegacyProviders.toConfig(builtIn("builtin-openai").copy(builtIn = false), emptyList()).presetId)
    }

    @Test
    fun endpointModes() {
        assertEquals(Wire.OpenAiResponses, LegacyProviders.wireFor(builtIn("builtin-openai", endpoint = "responses")))
        assertEquals(Wire.OpenAiChat, LegacyProviders.wireFor(builtIn("builtin-openai", endpoint = "")))
        // DeepSeek has no Responses endpoint in Atom 4: back to the preset's own wire.
        assertEquals(Wire.OpenAiChat, LegacyProviders.wireFor(builtIn("builtin-deepseek", endpoint = "responses")))
        assertEquals(Wire.Anthropic, LegacyProviders.wireFor(builtIn("builtin-anthropic", LegacyProviderRow.TYPE_ANTHROPIC)))
        assertEquals(Wire.Gemini, LegacyProviders.wireFor(builtIn("builtin-gemini", LegacyProviderRow.TYPE_GEMINI, "ai_studio")))
        assertEquals(Wire.Gemini, LegacyProviders.wireFor(builtIn("builtin-gemini", LegacyProviderRow.TYPE_GEMINI, "whatever")))
        assertEquals(Wire.Vertex, LegacyProviders.wireFor(builtIn("builtin-gemini", LegacyProviderRow.TYPE_GEMINI, "vertex_ai")))
        assertEquals(Wire.OpenAiChat, LegacyProviders.wireFor(LegacyProviderRow("u", type = "something-new")))
    }

    @Test
    fun customOpenAiCompatibleKeepsEverythingTheUserSet() {
        val row = LegacyProviderRow(
            id = "6f1c", type = LegacyProviderRow.TYPE_CUSTOM, name = " My proxy ", baseUrl = "https://llm.example.com/v1",
            apiKey = "sk-test", enabled = false, endpointMode = "responses", systemPrompt = "Be terse.",
            customHeadersJson = """[{"name":"X-Team","value":"7"},{"name":"Authorization","value":"Bearer leak"},{"name":" ","value":"x"}]""",
            customBodyJson = """[{"key":"temperature","value":0.2},{"key":"extra","value":{"a":[1,2]}}]""",
            hostedWebSearch = true,
        )
        val models = listOf(
            LegacyModelRow("m2", "6f1c", "beta-large", "Large", sortOrder = 2, contextWindow = 32_000, contextWindowOverride = 64_000),
            LegacyModelRow("m1", "6f1c", "alpha", "", sortOrder = 1, contextWindowOverride = 8_000, inputModalitiesJson = """["text","image"]"""),
            LegacyModelRow("m3", "6f1c", "hidden", enabled = false),
            LegacyModelRow("o1", "other", "not-mine"),
        )
        val c = LegacyProviders.toConfig(row, models, seededPrompt = "You are Atom.", rememberedModelRowId = "m2")
        assertEquals("", c.id)
        assertNull(c.presetId)
        assertEquals("My proxy", c.displayName)
        assertEquals(Wire.OpenAiResponses, c.wire)
        assertEquals("https://llm.example.com/v1", c.baseUrl)
        assertEquals(false, c.enabled)
        assertEquals(Family.CUSTOM, c.family)
        assertEquals("Be terse.", c.systemPrompt)
        assertEquals(listOf(HeaderEntry("X-Team", "7")), c.headers, "auth headers the old app never sent are dropped")
        assertEquals("""{"temperature":0.2,"extra":{"a":[1,2]}}""", c.extraBody)
        assertEquals(true, c.hostedWebSearch)
        assertEquals(AuthMethod.ApiKey, c.auth)
        assertEquals(listOf("alpha", "beta-large"), c.models.map { it.id }, "enabled models in sort order")
        assertEquals("beta-large", c.defaultModel)
        val alpha = c.models[0]
        assertEquals("alpha", alpha.displayName)
        assertNull(alpha.contextWindow, "a copy of the advertised window is not an override")
        assertEquals(8_000, alpha.detected?.contextWindow)
        assertEquals(true, alpha.detected?.vision)
        val beta = c.models[1]
        assertEquals(64_000, beta.contextWindow, "a real override stays an override")
        assertEquals(32_000, beta.detected?.contextWindow)
        assertEquals(CapabilitySource.Manual, beta.detected?.source)
    }

    @Test
    fun theSeededPromptBecomesAtom4sDefault() {
        val row = builtIn("builtin-kimi").copy(systemPrompt = "You are Atom.")
        assertEquals(PresetCatalog.DEFAULT_SYSTEM_PROMPT, LegacyProviders.toConfig(row, emptyList(), seededPrompt = "You are Atom.").systemPrompt)
        assertEquals("Mine", LegacyProviders.toConfig(row.copy(systemPrompt = "Mine"), emptyList(), seededPrompt = "You are Atom.").systemPrompt)
        assertEquals("", LegacyProviders.toConfig(LegacyProviderRow("c", systemPrompt = null), emptyList()).systemPrompt)
    }

    @Test
    fun anthropic() {
        val row = LegacyProviderRow("a1", type = LegacyProviderRow.TYPE_ANTHROPIC, name = "Claude proxy", baseUrl = "https://proxy.example.com", anthropicVersion = "2024-01-01")
        val c = LegacyProviders.toConfig(row, emptyList())
        assertEquals(Wire.Anthropic, c.wire)
        assertEquals(Family.ANTHROPIC, c.family)
        assertEquals("2024-01-01", c.anthropicVersion)
        assertEquals("", LegacyProviders.toConfig(row.copy(anthropicVersion = "2023-06-01"), emptyList()).anthropicVersion)
        // The version column is written on every row; it only matters for Anthropic.
        assertEquals("", LegacyProviders.toConfig(builtIn("builtin-openai").copy(anthropicVersion = "2024-01-01"), emptyList()).anthropicVersion)
    }

    @Test
    fun geminiAiStudioAndVertex() {
        val studio = LegacyProviders.toConfig(
            builtIn("builtin-gemini", LegacyProviderRow.TYPE_GEMINI, "ai_studio", PresetCatalog.GEMINI_BASE_URL).copy(hostedWebSearch = true),
            emptyList(),
        )
        assertEquals(Wire.Gemini, studio.wire)
        assertEquals(PresetCatalog.GEMINI_BASE_URL, studio.baseUrl)
        assertEquals(true, studio.googleSearch)
        assertEquals(false, studio.hostedWebSearch)

        val vertex = LegacyProviders.toConfig(
            builtIn("builtin-gemini", LegacyProviderRow.TYPE_GEMINI, "vertex_ai", "https://aiplatform.googleapis.com/v1beta1/projects/demo-1/locations/us-central1")
                .copy(vertexProject = "", vertexLocation = ""),
            emptyList(),
        )
        assertEquals(Wire.Vertex, vertex.wire)
        assertEquals("", vertex.baseUrl, "Google's own host is implied")
        assertEquals("demo-1", vertex.vertexProject)
        assertEquals("us-central1", vertex.vertexLocation)

        val columns = LegacyProviders.toConfig(
            LegacyProviderRow("g2", type = LegacyProviderRow.TYPE_GEMINI, endpointMode = "vertex_ai", baseUrl = "https://vertex-proxy.example.com", vertexProject = "p", vertexLocation = " "),
            emptyList(),
        )
        assertEquals("https://vertex-proxy.example.com", columns.baseUrl, "a proxy URL is kept")
        assertEquals("p", columns.vertexProject)
        assertEquals("global", columns.vertexLocation)
        assertEquals(Family.GEMINI, columns.family)
        // Gemini rows are API-key rows whatever auth_mode says.
        assertEquals(AuthMethod.ApiKey, LegacyProviders.requestedAuth(LegacyProviderRow("g", type = LegacyProviderRow.TYPE_GEMINI, authMode = LegacyProviderRow.AUTH_CHATGPT)))
    }

    @Test
    fun oauthRows() {
        val chatgpt = builtIn("builtin-openai", endpoint = "chat_completions").copy(authMode = LegacyProviderRow.AUTH_CHATGPT)
        assertEquals(AuthMethod.ChatGpt, LegacyProviders.requestedAuth(chatgpt))
        val c = LegacyProviders.toConfig(chatgpt, emptyList())
        assertEquals(Wire.OpenAiResponses, c.wire)
        assertEquals(AuthMethod.ApiKey, c.auth, "switched to the account only once the sign-in is imported")
        val grok = builtIn("builtin-xai").copy(authMode = LegacyProviderRow.AUTH_GROK)
        assertEquals(AuthMethod.Grok, LegacyProviders.requestedAuth(grok))
        assertEquals(Wire.OpenAiResponses, LegacyProviders.wireFor(grok))
        assertEquals(Family.XAI, LegacyProviders.familyFor(grok, null))
    }

    @Test
    fun familiesFromBaseUrlHosts() {
        fun family(url: String, type: String = LegacyProviderRow.TYPE_CUSTOM) = LegacyProviders.familyFor(LegacyProviderRow("x", type = type, baseUrl = url), null)
        assertEquals(Family.QWEN, family("https://ws-1.maas.aliyuncs.com/v1"))
        assertEquals(Family.MOONSHOT, family("https://api.moonshot.ai/v1"))
        assertEquals(Family.XAI, family("https://cli-chat-proxy.grok.com/v1"))
        assertEquals(Family.ANTHROPIC, family("https://my-proxy.example.com", LegacyProviderRow.TYPE_ANTHROPIC))
        assertEquals(Family.CUSTOM, family("http://localhost:11434/v1"))
    }

    @Test
    fun modelReasoning() {
        val caps = """{"supportedEfforts":["minimal","low","high","x-high"],"defaultEffort":"high","defaultEnabled":true,"mandatory":false,"canDisable":true,"supportsBudget":false,"maxBudgetTokens":null,"supportsMaxTokens":null}"""
        val auto = LegacyProviders.model(LegacyModelRow("r", "p", "thinker", reasoning = 1, reasoningCapabilitiesJson = caps, source = "REMOTE"))
        assertEquals(ReasoningMode.Auto, auto.reasoningMode)
        assertEquals(
            ReasoningInfo(true, listOf(ReasoningEffort.Off, ReasoningEffort.Low, ReasoningEffort.High, ReasoningEffort.XHigh), ReasoningEffort.High),
            auto.detected?.reasoning,
        )
        assertEquals(CapabilitySource.Remote, auto.detected?.source)

        val mandatory = LegacyProviders.capabilities("""{"supportedEfforts":[],"defaultEffort":null,"defaultEnabled":null,"mandatory":true,"canDisable":true,"supportsBudget":false,"maxBudgetTokens":null,"supportsMaxTokens":null}""")
        assertEquals(ReasoningInfo(true, emptyList(), null), mandatory, "forced reasoning")
        assertNull(LegacyProviders.capabilities("null"))

        val none = LegacyProviders.model(LegacyModelRow("r", "p", "plain", reasoning = 0))
        assertEquals(ReasoningInfo.None, none.detected?.reasoning)
        val unknown = LegacyProviders.model(LegacyModelRow("r", "p", "plain"))
        assertNull(unknown.detected?.reasoning, "left to Atom 4's detection")

        val off = LegacyProviders.model(LegacyModelRow("r", "p", "x", reasoning = 1, reasoningCapabilitiesJson = caps, reasoningOverride = 0))
        assertEquals(ReasoningMode.Off, off.reasoningMode)
        val on = LegacyProviders.model(
            LegacyModelRow("r", "p", "x", reasoningOverride = 1, reasoningCapabilitiesOverrideJson = """{"supportedEfforts":["medium"],"defaultEffort":null,"defaultEnabled":null,"mandatory":true,"canDisable":false,"supportsBudget":false,"maxBudgetTokens":null,"supportsMaxTokens":null}"""),
        )
        assertEquals(ReasoningMode.On, on.reasoningMode)
        assertEquals(listOf(ReasoningEffort.Medium), on.reasoningLevels)
        val onDefault = LegacyProviders.model(LegacyModelRow("r", "p", "x", reasoningOverride = 1))
        assertEquals(ReasoningMode.On, onDefault.reasoningMode)
        assertNull(onDefault.reasoningLevels, "an all-default override uses the detected levels")
    }

    @Test
    fun catalogModelsUseAtom4sEntryWithTheUsersOverrides() {
        val preset = PresetCatalog.byId("openai")!!
        val known = preset.models.first()
        val m = LegacyProviders.model(
            LegacyModelRow("builtin-openai-x", "builtin-openai", known.id, "Old name", contextWindow = 1, contextWindowOverride = 400_000, reasoningOverride = 0, source = "catalog"),
            preset,
        )
        assertEquals(known.displayName, m.displayName)
        assertTrue(m.builtIn)
        assertEquals(400_000, m.contextWindow)
        assertEquals(ReasoningMode.Off, m.reasoningMode)
    }

    @Test
    fun builtInsMergeIntoTheAtom4Preset() {
        val existing = PresetCatalog.byId("deepseek")!!.toConfig()
        val row = builtIn("builtin-deepseek", url = "https://api.deepseek.com").copy(name = "DeepSeek (work)", enabled = false)
        val imported = LegacyProviders.toConfig(row, listOf(LegacyModelRow("d9", "builtin-deepseek", "deepseek-old")))
        val fresh = LegacyProviders.merge(existing, imported, userConfigured = false)
        assertEquals("deepseek", fresh.id)
        assertEquals("DeepSeek (work)", fresh.displayName)
        assertEquals(false, fresh.enabled)
        assertTrue(fresh.models.any { it.id == "deepseek-old" })
        assertTrue(existing.models.all { e -> fresh.models.any { it.id == e.id } })

        val configured = existing.copy(displayName = "Mine", enabled = true)
        val kept = LegacyProviders.merge(configured, imported, userConfigured = true)
        assertEquals("Mine", kept.displayName, "never overwrite what the user set up in Atom 4")
        assertEquals(true, kept.enabled)
        assertEquals(configured.models.map(ModelConfig::id) + "deepseek-old", kept.models.map(ModelConfig::id))
        assertEquals(configured, LegacyProviders.merge(configured, LegacyProviders.toConfig(row, emptyList()), userConfigured = true))
    }

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{}".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    private val chatgptRow = builtIn("builtin-openai").copy(authMode = LegacyProviderRow.AUTH_CHATGPT)
    private val grokRow = builtIn("builtin-xai").copy(authMode = LegacyProviderRow.AUTH_GROK)

    @Test
    fun chatGptSignIns() {
        val access = jwt("""{"https://api.openai.com/auth":{"chatgpt_plan_type":"plus"}}""")
        val good = """{"authMode":"openai_chatgpt","clientId":"${ChatGptAuth.CLIENT_ID}","subject":"user-1","email":"a@b.c","accessToken":"$access","refreshToken":"rt","idToken":"","accountId":"acct-9","expiresAt":1000,"scopes":["openid"]}"""
        val usable = assertIs<SignIn.Usable>(LegacyProviders.signIn(good, chatgptRow, nowMillis = 5000))
        assertEquals(AuthMethod.ChatGpt, usable.method)
        assertEquals(access, usable.tokens.accessToken)
        assertEquals("rt", usable.tokens.refreshToken)
        assertNull(usable.tokens.idToken)
        assertEquals("acct-9", usable.tokens.accountId)
        assertEquals("a@b.c", usable.tokens.email)
        assertEquals(1000L, usable.tokens.expiresAtMillis, "expired but refreshable")
        assertEquals("plus", usable.tokens.plan)

        // No stored expiry (0): the access token's own "exp" claim is used.
        val withExp = jwt("""{"exp":1900000000}""")
        val noExpiry = good.replace(access, withExp).replace("\"expiresAt\":1000", "\"expiresAt\":0")
        assertEquals(1_900_000_000_000L, assertIs<SignIn.Usable>(LegacyProviders.signIn(noExpiry, chatgptRow, 0)).tokens.expiresAtMillis)

        assertIs<SignIn.SignInAgain>(LegacyProviders.signIn(good.replace("acct-9", ""), chatgptRow, 0))
        assertIs<SignIn.SignInAgain>(LegacyProviders.signIn(good.replace(ChatGptAuth.CLIENT_ID, "other"), chatgptRow, 0))
        assertIs<SignIn.SignInAgain>(LegacyProviders.signIn(good, grokRow, 0), "mode must match the row")
        assertIs<SignIn.SignInAgain>(LegacyProviders.signIn(good.replace("\"rt\"", "\"\""), chatgptRow, nowMillis = 5000), "expired, nothing to refresh with")
        // Signed out: tokens wiped, only the e-mail left (defaults omitted).
        assertIs<SignIn.SignInAgain>(LegacyProviders.signIn("""{"authMode":"openai_chatgpt","clientId":"${ChatGptAuth.CLIENT_ID}","email":"a@b.c"}""", chatgptRow, 0))
        assertIs<SignIn.SignInAgain>(LegacyProviders.signIn("not json", chatgptRow, 0))
    }

    @Test
    fun grokSignIns() {
        val good = """{"authMode":"xai_grok","clientId":"${GrokAuth.CLIENT_ID}","subject":"sub-1","accessToken":"at","scopes":["openid","grok-cli:access"]}"""
        val usable = assertIs<SignIn.Usable>(LegacyProviders.signIn(good, grokRow, 0))
        assertEquals(AuthMethod.Grok, usable.method)
        assertEquals("sub-1", usable.tokens.accountId)
        assertNull(usable.tokens.refreshToken)
        assertNull(usable.tokens.expiresAtMillis)
        assertIs<SignIn.SignInAgain>(LegacyProviders.signIn(good.replace(",\"grok-cli:access\"", ""), grokRow, 0))
        assertIs<SignIn.SignInAgain>(LegacyProviders.signIn(good.replace("\"sub-1\"", "null"), grokRow, 0))
    }
}
