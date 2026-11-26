package atom.llm.cloud

import java.util.Locale

/**
 * Which brand logo identifies a provider or model. Returns a stable key ("openai", "deepseek", ...) that the app
 * maps to its bundled logo images; null means "no logo, draw a monogram".
 *
 * A provider gets a brand logo only when it is one of the built-in presets, matched by its exact preset id: a
 * provider the user added (a LAN "OpenAI-compatible" server, a proxy) never borrows a brand by its name or URL.
 * Aggregators and user providers show the model's family when it is recognisable.
 */
object ProviderBrands {
    /** Every key [key] can return. */
    val KEYS: Set<String> = setOf(
        "openai", "anthropic", "claude", "gemini", "deepseek", "qwen", "kimi", "minimax", "stepfun",
        "siliconflow", "openrouter", "xai", "grok", "groq", "mistral", "gemma", "meta", "zhipu", "doubao",
    )

    /** Built-in preset id -> brand key. MiMo has no logo that reads at capsule size, so it uses the monogram. */
    private val PRESETS: Map<String, String> = mapOf(
        "openai" to "openai",
        "anthropic" to "anthropic",
        "gemini" to "gemini",
        "deepseek" to "deepseek",
        "qwen" to "qwen",
        "moonshot" to "kimi",
        "minimax" to "minimax",
        "stepfun" to "stepfun",
        "siliconflow" to "siliconflow",
        "openrouter" to "openrouter",
        "xai" to "xai",
        "groq" to "groq",
        "mistral" to "mistral",
    )

    /** Presets that serve other brands' models: the model family is the more useful logo there. */
    private val AGGREGATORS = setOf("openrouter", "siliconflow", "groq")

    // Model ids and display names: "gpt-5", "Claude Sonnet", "gemma-3n-e4b", "Qwen3 8B" ...
    private val MODEL_PREFIXES: List<Pair<String, String>> = listOf(
        "gemma" to "gemma",
        "gemini" to "gemini",
        "claude" to "claude",
        "gpt" to "openai",
        "chatgpt" to "openai",
        "o1" to "openai",
        "o3" to "openai",
        "o4" to "openai",
        "codex" to "openai",
        "deepseek" to "deepseek",
        "qwen" to "qwen",
        "qwq" to "qwen",
        "llama" to "meta",
        "meta-llama" to "meta",
        "mistral" to "mistral",
        "mixtral" to "mistral",
        "ministral" to "mistral",
        "codestral" to "mistral",
        "magistral" to "mistral",
        "devstral" to "mistral",
        "kimi" to "kimi",
        "moonshot" to "kimi",
        "glm" to "zhipu",
        "grok" to "grok",
        "minimax" to "minimax",
        "abab" to "minimax",
        "step" to "stepfun",
        "doubao" to "doubao",
    )

    // Vendor prefixes of aggregator model ids ("anthropic/claude-…", "x-ai/grok-…"), matched exactly.
    private val VENDORS: Map<String, String> = mapOf(
        "openai" to "openai",
        "anthropic" to "claude",
        "google" to "gemini",
        "deepseek" to "deepseek",
        "deepseek-ai" to "deepseek",
        "qwen" to "qwen",
        "meta-llama" to "meta",
        "mistralai" to "mistral",
        "moonshotai" to "kimi",
        "x-ai" to "grok",
        "z-ai" to "zhipu",
        "thudm" to "zhipu",
        "zai-org" to "zhipu",
        "minimax" to "minimax",
        "minimaxai" to "minimax",
        "stepfun-ai" to "stepfun",
    )

    /**
     * The brand for a provider given by its exact built-in preset id ([presetId]; null or any other id for user
     * providers), falling back to the family of [model].
     */
    fun key(presetId: String?, model: String? = null): String? {
        val provider = presetKey(presetId)
        val family = model?.let(::modelKey)
        return if (provider == null || provider in AGGREGATORS) family ?: provider else provider
    }

    /** The brand of a built-in preset id, exact match only. */
    fun presetKey(presetId: String?): String? = presetId?.let { PRESETS[it] }

    /** The family of a model id or name, e.g. "openai/gpt-5" or "Qwen3 8B Q4". */
    fun modelKey(model: String): String? {
        val s = model.lowercase(Locale.ROOT).trim()
        if (s.isEmpty()) return null
        // "vendor/model" ids on aggregators: look at the model part first, then the vendor.
        val parts = s.split('/', ':').filter { it.isNotEmpty() }
        for (part in parts.reversed() + s) {
            val head = part.trimStart('@', '-', '_')
            MODEL_PREFIXES.firstOrNull { (prefix, _) -> head.startsWith(prefix) && boundaryAfter(head, prefix.length) }?.let { return it.second }
        }
        return parts.firstNotNullOfOrNull { VENDORS[it] }
    }

    /** "step-2" and "step3" are StepFun, "stepwise-foo" is not: the prefix must end at a separator or a digit. */
    private fun boundaryAfter(s: String, end: Int): Boolean {
        if (end >= s.length) return true
        return !s[end].isLetter()
    }
}
