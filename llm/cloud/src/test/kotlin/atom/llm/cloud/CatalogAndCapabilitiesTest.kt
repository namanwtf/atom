package atom.llm.cloud

import atom.core.llm.ReasoningEffort
import atom.core.llm.ReasoningEffort.High
import atom.core.llm.ReasoningEffort.Low
import atom.core.llm.ReasoningEffort.Max
import atom.core.llm.ReasoningEffort.Medium
import atom.core.llm.ReasoningEffort.Off
import atom.core.llm.ReasoningEffort.XHigh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class CatalogAndCapabilitiesTest {

    @Test
    fun presetIdsAreUniqueAndEveryPresetResolves() {
        val ids = PresetCatalog.providers.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (preset in PresetCatalog.providers) {
            assertEquals(preset.models.size, preset.models.map { it.id }.toSet().size, preset.id)
            assertTrue(preset.baseUrl.startsWith("https://"), preset.id)
            assertFalse(preset.baseUrl.endsWith("/"), preset.id)
            assertEquals(preset.id, Providers.byId(preset.id)?.id)
            assertTrue(preset.wire in preset.wires, preset.id)
            val config = preset.toConfig()
            assertTrue(config.builtIn)
            assertEquals(PresetCatalog.DEFAULT_SYSTEM_PROMPT, config.systemPrompt)
            for (model in preset.models) {
                assertEquals(model, PresetCatalog.model(preset.id, model.id))
                val mc = config.model(model.id)!!
                assertEquals(model.reasoning, mc.reasoning, "${preset.id}/${model.id}")
                assertEquals(model.contextWindow, mc.effectiveContextWindow)
            }
        }
    }

    @Test
    fun shipsTheSpecProvidersAndDefault() {
        val required = listOf("openai", "anthropic", "qwen", "deepseek", "moonshot", "mimo", "minimax", "stepfun", "siliconflow", "openrouter", "xai", "gemini", "groq", "mistral")
        assertEquals(required.toSet(), PresetCatalog.providers.map { it.id }.toSet())
        assertEquals("DeepSeek Flash", PresetCatalog.model(PresetCatalog.DEFAULT_PROVIDER, PresetCatalog.DEFAULT_MODEL)?.displayName)
        assertEquals(Wire.OpenAiResponses, PresetCatalog.byId("openai")!!.wire)
        assertEquals("GPT-6.1 Sol", PresetCatalog.model("openai", "gpt-6.1-sol")?.displayName)
        assertEquals(listOf(Low, Medium, High, XHigh), PresetCatalog.model("gemini", "gemini-4-neon")!!.reasoning.levels)
        assertEquals(Medium, PresetCatalog.model("gemini", "gemini-4-carbon")!!.reasoning.serverDefault)
        assertTrue(AuthMethod.ChatGpt in PresetCatalog.byId("openai")!!.authMethods)
        assertTrue(AuthMethod.Grok in PresetCatalog.byId("xai")!!.authMethods)
    }

    @Test
    fun configSurvivesJsonRoundTrip() {
        val config = PresetCatalog.byId("gemini")!!.toConfig().copy(
            headers = listOf(HeaderEntry("User-Agent", "x")),
            models = listOf(ModelConfig("m", reasoningMode = ReasoningMode.On, reasoningLevels = listOf(Off, Max))),
        )
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(ProviderConfig.serializer(), json.encodeToString(ProviderConfig.serializer(), config))
        assertEquals(config, back)
        assertEquals(listOf(Off, Max), back.models.single().reasoning.levels)
    }

    @Test
    fun resetAndDuplicate() {
        val edited = PresetCatalog.byId("deepseek")!!.toConfig().copy(baseUrl = "https://proxy", models = emptyList(), enabled = false, systemPrompt = "")
        val reset = edited.resetToPreset()
        assertEquals("https://api.deepseek.com", reset.baseUrl)
        assertEquals(2, reset.models.size)
        assertFalse(reset.enabled)
        assertEquals(PresetCatalog.DEFAULT_SYSTEM_PROMPT, reset.systemPrompt)

        val copy = reset.duplicate("deepseek-2", "Copy")
        assertEquals("DeepSeek Copy", copy.displayName)
        assertNull(copy.presetId)
        assertEquals(Family.DEEPSEEK, copy.family)
        assertTrue(copy.models.none { it.builtIn })
    }

    @Test
    fun snapKeepsValidLevelsAndMovesInvalidOnesToTheNearest() {
        val info = ReasoningInfo.of(Low, High, Max)
        assertEquals(High, Reasoning.snap(High, info))
        assertEquals(High, Reasoning.snap(Medium, info))
        assertEquals(Max, Reasoning.snap(XHigh, info))
        assertEquals(Low, Reasoning.snap(Off, info))
        assertNull(Reasoning.snap(null, info))
        assertNull(Reasoning.snap(High, ReasoningInfo.forced()))
        assertNull(Reasoning.snap(High, ReasoningInfo.None))
    }

    @Test
    fun parsesCatalogEffortNames() {
        assertEquals(listOf(Off, Low, Medium, High), Reasoning.parseLevels(listOf("minimal", "low", "medium", "high"), toggle = false))
        assertEquals(listOf(Low, Medium, High), Reasoning.parseLevels(listOf("minimal", "medium", "high"), toggle = false))
        assertEquals(listOf(Off, Low, High, Max), Reasoning.parseLevels(listOf("low", "high", "max"), toggle = true))
        assertEquals(listOf(Off, XHigh), Reasoning.parseLevels(listOf("none", "xhigh"), toggle = false))
    }

    @Test
    fun familyRulesDetectReasoningAndContext() {
        val gpt = CapabilityDetector.byFamily("gpt-6-nova")
        assertEquals(CapabilitySource.Family, gpt.source)
        assertTrue(gpt.reasoning!!.supported)
        assertEquals(1_050_000, gpt.contextWindow)

        assertEquals(ReasoningInfo.forced(), CapabilityDetector.byFamily("deepseek-reasoner").reasoning)
        assertFalse(CapabilityDetector.byFamily("gpt-4o-mini").reasoning!!.supported)
        assertEquals(listOf(Off, Low, Medium, High), CapabilityDetector.byFamily("qwen/qwen3-32b").reasoning!!.levels)
        assertEquals(CapabilitySource.Unknown, CapabilityDetector.byFamily("my-local-llama").source)
        assertNull(CapabilityDetector.byFamily("my-local-llama").reasoning)
    }

    @Test
    fun metadataBeatsFamilyRulesFieldByField() {
        val remote = DetectedCapabilities(contextWindow = 64_000, source = CapabilitySource.Remote)
        val detected = CapabilityDetector.detect("deepseek-v4-pro", remote)
        assertEquals(64_000, detected.contextWindow)
        assertEquals(listOf(Off, Low, High, Max), detected.reasoning!!.levels)
        assertEquals(CapabilitySource.Remote, detected.source)
    }

    @Test
    fun manualModelSettingsOverrideDetection() {
        val auto = ModelConfig("deepseek-v4-pro")
        assertTrue(auto.reasoning.supported)
        val off = auto.copy(reasoningMode = ReasoningMode.Off)
        assertFalse(off.reasoning.supported)
        val manual = auto.copy(reasoningMode = ReasoningMode.On, reasoningLevels = listOf(High, Low))
        assertEquals(listOf(Low, High), manual.reasoning.levels)
        assertEquals(32_000, auto.copy(contextWindow = 32_000).effectiveContextWindow)
        assertNotNull(ModelConfig("unknown-x").autoCapabilities)
        assertFalse(ModelConfig("unknown-x").reasoning.supported)
    }

    @Test
    fun familyIsGuessedFromBaseUrl() {
        assertEquals(Family.DEEPSEEK, Family.fromBaseUrl("https://api.deepseek.com"))
        assertEquals(Family.OPENROUTER, Family.fromBaseUrl("https://openrouter.ai/api/v1"))
        assertEquals(Family.QWEN, Family.fromBaseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1"))
        assertEquals(Family.CUSTOM, Family.fromBaseUrl("http://192.168.1.5:11434/v1"))
    }

    @Test
    fun everyEffortHasAWireName() {
        assertEquals(ReasoningEffort.entries.size, ReasoningEffort.entries.map { it.wireName }.toSet().size)
    }
}
