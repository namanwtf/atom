package atom.llm.cloud

import java.util.Locale

/**
 * Which brand logo identifies a provider or model. Returns a stable key ("openai", "deepseek", ...) that the app
 * maps to its bundled logo images; null means "no logo, draw a monogram".
 *
 * Provider facts win (its id, then its name, then its endpoint host). For aggregators and local servers, which host
 * many brands, the model's family is shown when it is recognisable.
 */
object ProviderBrands {
    /** Every key [key] can return. */
    val KEYS: Set<String> = setOf(
        "openai", "anthropic", "claude", "gemini", "deepseek", "qwen", "kimi", "mimo", "minimax", "stepfun",
        "siliconflow", "openrouter", "xai", "grok", "groq", "mistral", "gemma", "meta", "ollama", "lmstudio",
        "zhipu", "doubao",
    )

    /** Providers that serve other brands' models: the model family is the more useful logo there. */
    private val AGGREGATORS = setOf("openrouter", "siliconflow", "groq", "ollama", "lmstudio")

    // Ordered: the first match wins, so longer or more specific tokens come first.
    private val PROVIDER_TOKENS: List<Pair<String, String>> = listOf(
        "openrouter" to "openrouter",
        "siliconflow" to "siliconflow",
        "siliconcloud" to "siliconflow",
        "lmstudio" to "lmstudio",
        "lm-studio" to "lmstudio",
        "lm studio" to "lmstudio",
        "ollama" to "ollama",
        "anthropic" to "anthropic",
        "claude" to "anthropic",
        "openai" to "openai",
        "chatgpt" to "openai",
        "gemini" to "gemini",
        "vertex" to "gemini",
        "google" to "gemini",
        "deepseek" to "deepseek",
        "bailian" to "qwen",
        "dashscope" to "qwen",
        "alibaba" to "qwen",
        "qwen" to "qwen",
        "moonshot" to "kimi",
        "kimi" to "kimi",
        "xiaomi" to "mimo",
        "mimo" to "mimo",
        "minimax" to "minimax",
        "stepfun" to "stepfun",
        "groq" to "groq",
        "grok" to "xai",
        "xai" to "xai",
        "x.ai" to "xai",
        "mistral" to "mistral",
        "zhipu" to "zhipu",
        "bigmodel" to "zhipu",
        "doubao" to "doubao",
        "volces" to "doubao",
        "volcengine" to "doubao",
    )

    private val HOST_TOKENS: List<Pair<String, String>> = listOf(
        "api.openai.com" to "openai",
        "anthropic.com" to "anthropic",
        "googleapis.com" to "gemini",
        "deepseek.com" to "deepseek",
        "aliyuncs.com" to "qwen",
        "moonshot.cn" to "kimi",
        "moonshot.ai" to "kimi",
        "xiaomimimo.com" to "mimo",
        "minimax" to "minimax",
        "stepfun.com" to "stepfun",
        "siliconflow" to "siliconflow",
        "openrouter.ai" to "openrouter",
        "api.x.ai" to "xai",
        "groq.com" to "groq",
        "mistral.ai" to "mistral",
        "bigmodel.cn" to "zhipu",
        "volces.com" to "doubao",
        ":11434" to "ollama",
        ":1234" to "lmstudio",
    )

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
        "mimo" to "mimo",
        "doubao" to "doubao",
    )

    /** The brand of a provider (by id, display name or endpoint), falling back to the model family. */
    fun key(providerId: String?, name: String? = null, baseUrl: String? = null, model: String? = null): String? {
        val provider = providerKey(providerId, name, baseUrl)
        val family = model?.let(::modelKey)
        return if (provider == null || provider in AGGREGATORS) family ?: provider else provider
    }

    fun providerKey(providerId: String?, name: String? = null, baseUrl: String? = null): String? {
        for (raw in listOf(providerId, name)) {
            val s = raw?.lowercase(Locale.ROOT)?.trim().orEmpty()
            if (s.isEmpty()) continue
            PROVIDER_TOKENS.firstOrNull { (token, _) -> s.contains(token) }?.let { return it.second }
        }
        val host = baseUrl?.lowercase(Locale.ROOT).orEmpty()
        if (host.isNotEmpty()) HOST_TOKENS.firstOrNull { (token, _) -> host.contains(token) }?.let { return it.second }
        return null
    }

    /** The family of a model id or name, e.g. "openai/gpt-5" or "Qwen3 8B Q4". */
    fun modelKey(model: String): String? {
        val s = model.lowercase(Locale.ROOT).trim()
        if (s.isEmpty()) return null
        // "vendor/model" ids on aggregators: look at both the vendor and the model part.
        val parts = s.split('/', ':').filter { it.isNotEmpty() }.reversed() + s
        for (part in parts) {
            val head = part.trimStart('@', '-', '_')
            MODEL_PREFIXES.firstOrNull { (prefix, _) -> head.startsWith(prefix) && boundaryAfter(head, prefix.length) }?.let { return it.second }
        }
        // Vendor names in a path ("anthropic/...", "x-ai/...", "google/...").
        return parts.firstNotNullOfOrNull { p -> providerKey(p) }
    }

    /** "step-2" and "step3" are StepFun, "stepwise-foo" is not: the prefix must end at a separator or a digit. */
    private fun boundaryAfter(s: String, end: Int): Boolean {
        if (end >= s.length) return true
        val c = s[end]
        return !c.isLetter()
    }
}
