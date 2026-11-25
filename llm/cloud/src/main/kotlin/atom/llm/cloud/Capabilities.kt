package atom.llm.cloud

import atom.core.llm.ReasoningEffort
import kotlinx.serialization.Serializable

/**
 * What a model accepts for reasoning. [levels] are the explicit efforts it takes (Off included when thinking
 * can be switched off); "model default" (a null effort) is always valid when [supported]. A supported model
 * with no levels is forced-reasoning: the only choice is the default.
 */
@Serializable
data class ReasoningInfo(
    val supported: Boolean,
    val levels: List<ReasoningEffort> = emptyList(),
    /** The level the server uses when none is sent, when known. */
    val serverDefault: ReasoningEffort? = null,
) {
    companion object {
        val None = ReasoningInfo(supported = false)
        fun forced(default: ReasoningEffort? = null) = ReasoningInfo(true, emptyList(), default)
        fun of(vararg levels: ReasoningEffort, default: ReasoningEffort? = null) =
            ReasoningInfo(true, levels.toList().sorted(), default)
    }
}

/** Where a capability value came from, so the UI can say "Detected automatically" or "unknown". */
@Serializable
enum class CapabilitySource { Manual, Catalog, Remote, Family, Unknown }

@Serializable
data class DetectedCapabilities(
    val reasoning: ReasoningInfo? = null,
    val contextWindow: Int? = null,
    val vision: Boolean? = null,
    val source: CapabilitySource = CapabilitySource.Unknown,
)

object Reasoning {
    /**
     * Brings a saved effort into what the model accepts: kept when valid, the nearest valid level otherwise
     * (ties go to the deeper level), and null (model default) when the model has no explicit levels.
     */
    fun snap(saved: ReasoningEffort?, info: ReasoningInfo): ReasoningEffort? {
        if (saved == null || !info.supported) return null
        if (saved in info.levels) return saved
        if (info.levels.isEmpty()) return null
        return info.levels.minWith(compareBy<ReasoningEffort> { kotlin.math.abs(it.ordinal - saved.ordinal) }.thenByDescending { it.ordinal })
    }

    /**
     * Maps catalog effort names onto [ReasoningEffort]. "none" (and a toggle) mean thinking can be switched
     * off; "minimal" counts as Low unless Low is also listed, in which case it is the closest thing to Off
     * that model has.
     */
    fun parseLevels(values: List<String>, toggle: Boolean): List<ReasoningEffort> {
        val names = values.map { it.trim().lowercase() }.toSet()
        val out = sortedSetOf<ReasoningEffort>()
        if (toggle || "none" in names || "off" in names || "disabled" in names) out += ReasoningEffort.Off
        if ("minimal" in names) out += if ("low" in names) ReasoningEffort.Off else ReasoningEffort.Low
        if ("low" in names) out += ReasoningEffort.Low
        if ("medium" in names) out += ReasoningEffort.Medium
        if ("high" in names) out += ReasoningEffort.High
        if ("xhigh" in names || "extra_high" in names) out += ReasoningEffort.XHigh
        if ("max" in names || "maximum" in names) out += ReasoningEffort.Max
        return out.toList()
    }
}

/**
 * Fallback capability rules by model family, used when neither the built-in catalog nor the provider's
 * model list says anything about a model.
 */
object CapabilityDetector {
    private val L = ReasoningEffort.Low
    private val M = ReasoningEffort.Medium
    private val H = ReasoningEffort.High
    private val X = ReasoningEffort.XHigh
    private val MAX = ReasoningEffort.Max
    private val OFF = ReasoningEffort.Off

    private class Rule(val pattern: Regex, val reasoning: ReasoningInfo?, val context: Int?)

    private fun rule(pattern: String, reasoning: ReasoningInfo?, context: Int? = null) =
        Rule(Regex(pattern, RegexOption.IGNORE_CASE), reasoning, context)

    private val RULES = listOf(
        rule("(^|/)o[1-9](-mini|-pro)?($|-)", ReasoningInfo.of(L, M, H), 200_000),
        rule("(^|/)gpt-[6-9]", ReasoningInfo.of(L, M, H, X, MAX), 1_050_000),
        rule("(^|/)gpt-5\\.[1-9]", ReasoningInfo.of(OFF, L, M, H, X), 400_000),
        rule("(^|/)gpt-5", ReasoningInfo.of(L, M, H), 400_000),
        rule("(^|/)gpt-oss", ReasoningInfo.of(L, M, H), 131_072),
        rule("(^|/)gpt-4\\.1", ReasoningInfo.None, 1_047_576),
        rule("(^|/)gpt-4o", ReasoningInfo.None, 128_000),
        rule("claude-(fable|opus-5|sonnet-6|opus-6)", ReasoningInfo.of(L, M, H, X, MAX), 1_000_000),
        rule("claude-(opus|sonnet)-(4-[6-9]|5)", ReasoningInfo.of(OFF, L, M, H, X, MAX), 1_000_000),
        rule("claude-(opus|sonnet|haiku)-4", ReasoningInfo.of(OFF, L, M, H), 200_000),
        rule("claude-3-7", ReasoningInfo.of(OFF, L, M, H), 200_000),
        rule("claude-3", ReasoningInfo.None, 200_000),
        rule("gemini-4-(neon|hydrogen)", ReasoningInfo.of(L, M, H, X, default = H), 1_048_576),
        rule("gemini-4", ReasoningInfo.of(L, M, H, default = M), 1_048_576),
        rule("gemini-3\\.[5-9]-flash-lite", ReasoningInfo.of(OFF, L, M, H), 1_048_576),
        rule("gemini-3", ReasoningInfo.of(L, M, H), 1_048_576),
        rule("gemini-2\\.5-pro", ReasoningInfo.of(L, M, H), 1_048_576),
        rule("gemini-2\\.5", ReasoningInfo.of(OFF, L, M, H), 1_048_576),
        rule("gemini-(1|2\\.0)", ReasoningInfo.None, 1_048_576),
        rule("deepseek-(reasoner|r1)", ReasoningInfo.forced(), 131_072),
        rule("deepseek-(v4|flash)", ReasoningInfo.of(OFF, L, H, MAX), 1_048_576),
        rule("deepseek-(chat|v3)", ReasoningInfo.None, 131_072),
        rule("kimi-k3", ReasoningInfo.of(L, H, MAX), 1_048_576),
        rule("kimi-k2.*thinking", ReasoningInfo.forced(), 262_144),
        rule("kimi-k2\\.7", ReasoningInfo.forced(), 262_144),
        rule("kimi-k2\\.[5-6]", ReasoningInfo.of(OFF), 262_144),
        rule("kimi-k2", ReasoningInfo.None, 131_072),
        rule("qwq|qvq", ReasoningInfo.forced(), 131_072),
        rule("qwen3\\.[5-9]-(max|flash)", ReasoningInfo.of(OFF, L, M, X), 1_000_000),
        rule("qwen3", ReasoningInfo.of(OFF, L, M, H), 262_144),
        rule("grok-[4-9]", ReasoningInfo.of(L, M, H, X), 500_000),
        rule("grok-3-mini", ReasoningInfo.of(L, H), 131_072),
        rule("mimo-v2\\.[6-9]", ReasoningInfo.of(OFF), 1_048_576),
        rule("mimo", ReasoningInfo.forced(), 1_000_000),
        rule("minimax-m[2-9]", ReasoningInfo.of(OFF), 1_000_000),
        rule("step-[3-9]", ReasoningInfo.of(L, M, H), 256_000),
        rule("glm-(4\\.[5-9]|[5-9])", ReasoningInfo.of(OFF), 200_000),
        rule("magistral", ReasoningInfo.forced(), 128_000),
        rule("(^|[-/_])(r1|reasoner|thinking)($|[-/_])", ReasoningInfo.forced(), null),
    )

    /** Returns family-rule capabilities, or [CapabilitySource.Unknown] with nulls when nothing matches. */
    fun byFamily(modelId: String): DetectedCapabilities {
        val rule = RULES.firstOrNull { it.pattern.containsMatchIn(modelId) }
            ?: return DetectedCapabilities(source = CapabilitySource.Unknown)
        return DetectedCapabilities(rule.reasoning, rule.context, null, CapabilitySource.Family)
    }

    /** Remote or catalog metadata wins field by field; family rules fill the gaps. */
    fun detect(modelId: String, metadata: DetectedCapabilities?): DetectedCapabilities {
        val family = byFamily(modelId)
        if (metadata == null) return family
        val reasoning = metadata.reasoning ?: family.reasoning
        val context = metadata.contextWindow ?: family.contextWindow
        val source = if (metadata.reasoning != null || metadata.contextWindow != null) metadata.source else family.source
        return DetectedCapabilities(reasoning, context, metadata.vision ?: family.vision, source)
    }
}
