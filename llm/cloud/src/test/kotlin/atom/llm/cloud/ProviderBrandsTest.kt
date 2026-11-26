package atom.llm.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderBrandsTest {
    @Test
    fun builtInPresetsHaveALogoExceptMimo() {
        for (p in PresetCatalog.providers) {
            val key = ProviderBrands.key(p.id)
            if (p.id == "mimo") {
                assertNull(key, "MiMo uses the monogram")
            } else {
                assertTrue(key != null && key in ProviderBrands.KEYS, "no logo for ${p.id}")
            }
        }
    }

    @Test
    fun presetIdsMapExactly() {
        assertEquals("qwen", ProviderBrands.key("qwen"))
        assertEquals("kimi", ProviderBrands.key("moonshot"))
        assertEquals("xai", ProviderBrands.key("xai"))
        assertEquals("deepseek", ProviderBrands.key("deepseek", model = "deepseek-flash"))
        // A first-party preset keeps its own logo whatever the model.
        assertEquals("openai", ProviderBrands.key("openai", model = "gpt-5"))
    }

    @Test
    fun userProvidersNeverBorrowABrandByName() {
        assertNull(ProviderBrands.key("openai-compatible-lan"))
        assertNull(ProviderBrands.key("my-openai-proxy", model = "my-finetune"))
        assertNull(ProviderBrands.key("custom"))
        // ... but a recognisable model family still shows.
        assertEquals("qwen", ProviderBrands.key("home-server", model = "qwen3:8b"))
    }

    @Test
    fun aggregatorsShowTheModelFamily() {
        assertEquals("claude", ProviderBrands.key("openrouter", model = "anthropic/claude-sonnet-4"))
        assertEquals("meta", ProviderBrands.key("siliconflow", model = "meta-llama/Llama-3.3-70B-Instruct"))
        assertEquals("grok", ProviderBrands.key("openrouter", model = "x-ai/grok-4"))
        assertEquals("openrouter", ProviderBrands.key("openrouter", model = "some-vendor/unknown-model"))
    }

    @Test
    fun onDeviceModelFamilies() {
        assertEquals("gemma", ProviderBrands.key(null, model = "Gemma 3n E4B"))
        assertEquals("qwen", ProviderBrands.key(null, model = "Qwen3-4B-Q4_K_M.gguf"))
        assertEquals("stepfun", ProviderBrands.modelKey("step-3"))
        assertNull(ProviderBrands.modelKey("stepwise-model"))
        assertNull(ProviderBrands.key(null, model = "my-finetune"))
        assertNull(ProviderBrands.key(null))
    }
}
