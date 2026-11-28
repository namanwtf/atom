package atom.core.agent

/**
 * Limits the user set in Settings > Advanced. Null (and the defaults) mean "Auto": the value follows the selected
 * model ([ModelLimits]).
 */
data class LimitOverrides(
    /** Output tokens per request; capped by the model's own limit when that is known. */
    val maxOutputTokens: Int? = null,
    /** Model calls per run. */
    val maxSteps: Int? = null,
    /** Characters of one tool result the model sees in full. */
    val toolResultChars: Int? = null,
    /** How long one subagent may work, in minutes. */
    val subagentMinutes: Int? = null,
    /** How long a run waits for an approval, in minutes. */
    val approvalWaitMinutes: Int? = null,
    /** What happens when nobody answers in time: the run pauses (true) or the action is declined (false). */
    val approvalPausesRun: Boolean = true,
    /** The longest a new scheduled task may run, in minutes. */
    val taskMinutes: Int? = null,
    /** Images (screenshots, attachments) each request keeps for a model that can see. */
    val imagesKept: Int? = null,
)

/**
 * Every size limit of a run, derived in one place from the selected model: its context window, its output limit,
 * whether it sees images and whether it runs on the phone, plus the user's [LimitOverrides].
 *
 * Small on-device models keep the tight values they always had (their prompt is prefill on the phone's own chip).
 * A cloud model with an unknown window gets the values ATOM used before limits followed the model, so nothing grows
 * for a model nobody knows. Frontier cloud models with large windows get room in proportion to the window.
 */
data class ModelLimits(
    val onDevice: Boolean = false,
    /** Context window in tokens; null when unknown. */
    val window: Int? = null,
    /** The model's own output limit in tokens; null when unknown. */
    val maxOutput: Int? = null,
    val vision: Boolean = false,
    val overrides: LimitOverrides = LimitOverrides(),
) {
    private val cloudWindow: Int? = if (onDevice) null else window?.takeIf { it > 0 }

    /** Characters of one tool result kept in full: a twentieth of the window (in characters), 8,000 to 60,000. */
    val toolResultChars: Int
        get() = overrides.toolResultChars?.coerceIn(MIN_TOOL_RESULT_CHARS, MAX_TOOL_RESULT_CHARS)
            ?: cloudWindow?.let { (it / 20).coerceIn(TOOL_RESULT_CHARS, MAX_AUTO_TOOL_RESULT_CHARS) }
            ?: TOOL_RESULT_CHARS

    /** Steps whose tool results all stay in full (older results shrink to a stub). */
    val keepFullToolSteps: Int
        get() = when {
            cloudWindow == null -> KEEP_FULL_STEPS
            cloudWindow >= 500_000 -> 6
            cloudWindow >= 200_000 -> 4
            else -> KEEP_FULL_STEPS
        }

    /** What an older tool result shrinks to. */
    val compactedToolResultChars: Int
        get() = cloudWindow?.let { (it / 2_000).coerceIn(COMPACTED_CHARS, 1_000) } ?: COMPACTED_CHARS

    /** Images each request keeps: up to 6 for a window of 200K or more, 3 from 64K, else 1; none without vision. */
    val imagesKept: Int
        get() {
            if (!vision) return 0
            overrides.imagesKept?.let { return it.coerceIn(0, MAX_IMAGES) }
            return when {
                cloudWindow == null -> 1
                cloudWindow >= 200_000 -> 6
                cloudWindow >= 64_000 -> 3
                else -> 1
            }
        }

    /**
     * Output tokens for a compaction summary in a [usable]-token prompt: an eighth of it, 400 to 2,000, as always on
     * the phone; a known cloud window gets up to a thirty-second of it (at most 16,000, never above the model's
     * output limit), so a long session keeps the detail it needs.
     */
    fun compactionSummaryTokens(usable: Int): Int {
        val small = (usable / 8).coerceIn(400, 2_000)
        val w = cloudWindow ?: return small
        val big = maxOf(small, minOf(w / 32, 16_000))
        return maxOutput?.takeIf { it > 0 }?.let { minOf(big, it).coerceAtLeast(400) } ?: big
    }

    /** Characters of transcript a compaction request may carry: three per usable token, at least 8,000. */
    fun compactionTranscriptChars(usable: Int): Int = (usable * 3).coerceAtLeast(8_000)

    /** What one tool result and one call's arguments keep inside the compaction transcript. */
    val compactionToolResultChars: Int get() = cloudWindow?.let { (it / 250).coerceIn(1_500, 6_000) } ?: 1_500
    val compactionArgumentChars: Int get() = cloudWindow?.let { (it / 1_000).coerceIn(400, 2_000) } ?: 400

    /** Terminal output kept per command: standard output, error output and a job's log. */
    val terminalStdoutChars: Int get() = cloudWindow?.let { (it / 32).coerceIn(3_900, 30_000) } ?: 3_900
    val terminalStderrChars: Int get() = cloudWindow?.let { (it / 128).coerceIn(1_000, 8_000) } ?: 1_000
    val terminalLogChars: Int get() = cloudWindow?.let { (it / 32).coerceIn(4_000, 30_000) } ?: 4_000

    /** Characters of the screen outline read_screen returns. */
    val screenTreeChars: Int get() = cloudWindow?.let { (it / 40).coerceIn(6_000, 24_000) } ?: 6_000

    /** One full search_history entry, and a subagent's summary. */
    val historyEntryChars: Int get() = cloudWindow?.let { (it / 40).coerceIn(6_000, 24_000) } ?: 6_000
    val subagentSummaryChars: Int get() = cloudWindow?.let { (it / 40).coerceIn(6_000, 24_000) } ?: 6_000

    /** A scheduled task's previous result, given to its next run. */
    val taskMemoChars: Int get() = cloudWindow?.let { (it / 64).coerceIn(4_000, 16_000) } ?: 4_000

    /** An MCP tool's description. */
    val mcpDescriptionChars: Int get() = if ((cloudWindow ?: 0) >= 128_000) 4_000 else 1_000

    /** Core memory sent with every request, in tokens: about 3% of the window, up to 8,000 for big windows. */
    val memoryTokens: Int
        get() {
            val w = window?.takeIf { it > 0 } ?: DEFAULT_MEMORY_WINDOW
            val cap = if ((cloudWindow ?: 0) >= 128_000) MAX_MEMORY_TOKENS else SMALL_MEMORY_TOKENS
            return (w * 0.03).toInt().coerceIn(200, cap)
        }

    /** Parallel subagents one call may start (on the phone they run one after another). */
    val maxParallelSubagents: Int get() = if (onDevice) 4 else 8

    /** How long one subagent may work. On the phone it stays short. */
    val subagentBudgetMs: Long
        get() = when {
            onDevice -> LOCAL_SUBAGENT_MS
            else -> (overrides.subagentMinutes?.coerceIn(1, 60)?.let { it * MINUTE }) ?: SUBAGENT_MS
        }

    /** How long a run waits for an approval before it pauses (or declines, see [LimitOverrides.approvalPausesRun]). */
    val approvalWaitMs: Long get() = overrides.approvalWaitMinutes?.coerceIn(1, 24 * 60)?.let { it * MINUTE } ?: APPROVAL_WAIT_MS

    /** Default duration of a new scheduled task, in minutes. */
    val taskMinutes: Int get() = overrides.taskMinutes ?: if (onDevice) 5 else 15

    /** Model calls per run: [default] (the engine's own cap) unless the user set one. */
    fun maxSteps(default: Int): Int = overrides.maxSteps?.coerceIn(1, MAX_STEPS) ?: default

    /** Output tokens per request: [auto] unless the user set a value, never above the model's own limit. */
    fun maxOutputTokens(auto: Int): Int {
        val custom = overrides.maxOutputTokens?.takeIf { it > 0 } ?: return auto
        return maxOutput?.takeIf { it > 0 }?.let { minOf(custom, it) } ?: custom
    }

    /** The packing policy for this model, starting from [base] (deliberate caps such as on-device budgets stay). */
    fun policy(base: ContextPolicy = ContextPolicy()): ContextPolicy =
        if (onDevice) base else base.copy(
            keepFullToolResults = keepFullToolSteps,
            maxToolResultChars = toolResultChars,
            compactedToolResultChars = compactedToolResultChars,
            keepImages = imagesKept,
        )

    companion object {
        /** A cloud model nothing is known about: the values ATOM always used. */
        val Default = ModelLimits()

        const val TOOL_RESULT_CHARS = 8_000
        const val MAX_AUTO_TOOL_RESULT_CHARS = 60_000
        const val MIN_TOOL_RESULT_CHARS = 1_000
        const val MAX_TOOL_RESULT_CHARS = 200_000
        const val KEEP_FULL_STEPS = 3
        const val COMPACTED_CHARS = 280
        const val MAX_IMAGES = 12
        const val MAX_STEPS = 2_000
        const val DEFAULT_MEMORY_WINDOW = 32_768
        const val SMALL_MEMORY_TOKENS = 1_200
        const val MAX_MEMORY_TOKENS = 8_000
        private const val MINUTE = 60_000L

        /** A cloud subagent: inside the 15-minute limit of the spawn tools. */
        const val SUBAGENT_MS = 14 * MINUTE

        /** An on-device subagent, as before. */
        const val LOCAL_SUBAGENT_MS = 55_000L

        /** How long a run waits for an approval by default. */
        const val APPROVAL_WAIT_MS = 10 * MINUTE
    }
}
