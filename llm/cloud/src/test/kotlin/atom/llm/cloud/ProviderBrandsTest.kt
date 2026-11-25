package atom.llm.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderBrandsTest {
    @Test
    fun everyBuiltInProviderHasALogo() {
        for (p in PresetCatalog.providers) {
            val key = ProviderBrands.key(p.id, p.displayName)
            assertTrue(key != null && key in ProviderBrands.KEYS, "no logo for ${p.id}")
        }
    }

    @Test
    fun builtInIdsAndNames() {
        assertEquals("qwen", ProviderBrands.key("qwen", "Alibaba Bailian"))
        assertEquals("kimi", ProviderBrands.key("moonshot", "Kimi (Moonshot)"))
        assertEquals("mimo", ProviderBrands.key("mimo", "MiMo (Xiaomi)"))
        assertEquals("xai", ProviderBrands.key("xai", "xAI"))
        assertEquals("groq", ProviderBrands.key("groq"))
        assertEquals("deepseek", ProviderBrands.key("deepseek", model = "deepseek-flash"))
    }

    @Test
    fun copiesAndCustomProviders() {
        assertEquals("openai", ProviderBrands.key("openai-copy", "OpenAI Copy"))
        assertEquals("anthropic", ProviderBrands.key("my-proxy", "Work", baseUrl = "https://api.anthropic.com"))
        assertEquals("ollama", ProviderBrands.key("custom", "Home server", baseUrl = "http://192.168.1.5:11434/v1"))
        assertNull(ProviderBrands.key("custom", "Home server", baseUrl = "http://192.168.1.5:9000/v1"))
    }

    @Test
    fun aggregatorsShowTheModelFamily() {
        assertEquals("claude", ProviderBrands.key("openrouter", "OpenRouter", model = "anthropic/claude-sonnet-4"))
        assertEquals("meta", ProviderBrands.key("siliconflow", model = "meta-llama/Llama-3.3-70B-Instruct"))
        assertEquals("openrouter", ProviderBrands.key("openrouter", "OpenRouter", model = "some-vendor/unknown-model"))
        // A first-party provider keeps its own logo whatever the model.
        assertEquals("openai", ProviderBrands.key("openai", model = "gpt-5"))
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
