package atom.llm.cloud

import atom.core.llm.ReasoningEffort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** OpenAI-style effort names; vendors that cap at "xhigh" get [ReasoningEffort.Max] folded into it. */
internal val ReasoningEffort.wireName: String
    get() = when (this) {
        ReasoningEffort.Off -> "none"
        ReasoningEffort.Low -> "low"
        ReasoningEffort.Medium -> "medium"
        ReasoningEffort.High -> "high"
        ReasoningEffort.XHigh -> "xhigh"
        ReasoningEffort.Max -> "max"
    }

internal fun thinkingBudgetFor(effort: ReasoningEffort): Int = when (effort) {
    ReasoningEffort.Off -> 0
    ReasoningEffort.Low -> 1024
    ReasoningEffort.Medium -> 4096
    ReasoningEffort.High -> 16_384
    ReasoningEffort.XHigh -> 32_768
    ReasoningEffort.Max -> 65_536
}

/** Chat Completions reasoning parameters per vendor. Nothing is sent for a null (model default) effort. */
internal object ChatReasoning {
    fun apply(body: JsonObjectBuilder, family: String, effort: ReasoningEffort?) {
        if (effort == null) return
        when (family) {
            Family.OPENROUTER -> body.putJsonObject("reasoning") {
                if (effort == ReasoningEffort.Off) put("enabled", false)
                else put("effort", if (effort == ReasoningEffort.Max) "xhigh" else effort.wireName)
            }
            Family.QWEN, Family.SILICONFLOW -> {
                body.put("enable_thinking", effort != ReasoningEffort.Off)
                if (effort != ReasoningEffort.Off) body.put("thinking_budget", thinkingBudgetFor(effort))
            }
            Family.DEEPSEEK, Family.MOONSHOT, Family.MIMO -> {
                body.putJsonObject("thinking") { put("type", if (effort == ReasoningEffort.Off) "disabled" else "enabled") }
                if (effort != ReasoningEffort.Off) body.put("reasoning_effort", effort.wireName)
            }
            Family.MISTRAL -> if (effort != ReasoningEffort.Off) body.put("prompt_mode", "reasoning")
            Family.MINIMAX -> Unit
            Family.GROQ, Family.XAI, Family.STEPFUN ->
                body.put("reasoning_effort", if (effort == ReasoningEffort.Max) "xhigh" else effort.wireName)
            else -> body.put("reasoning_effort", effort.wireName)
        }
    }

    /** Vendors that need the previous turn's reasoning sent back while a tool loop is in progress. */
    fun interleaved(family: String): Boolean =
        family == Family.DEEPSEEK || family == Family.MOONSHOT || family == Family.QWEN ||
            family == Family.MIMO || family == Family.SILICONFLOW
}

/**
 * Some wires need data the shared history has no slot for (interleaved reasoning text, encrypted reasoning
 * items) replayed alongside the tool calls that followed it. Kept in memory, keyed by the first call id.
 */
internal class CallSideCache<T : Any>(private val capacity: Int = 256) {
    private val map = object : LinkedHashMap<String, T>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > capacity
    }

    fun put(callId: String, value: T) = synchronized(map) { map[callId] = value }

    fun get(callId: String): T? = synchronized(map) { map[callId] }
}

internal object ReplayCaches {
    val chatReasoning = CallSideCache<String>()
    val responsesReasoning = CallSideCache<JsonArray>()
}
