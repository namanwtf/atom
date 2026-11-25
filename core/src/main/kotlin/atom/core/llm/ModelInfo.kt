package atom.core.llm

enum class ReasoningMode {
    /** The model cannot reason: the reasoning control is hidden. */
    Unsupported,

    /** Reasoning can be switched off and on, with the listed effort levels. */
    Optional,

    /** The model always reasons; only the listed levels (or none: a fixed "Default") can be chosen. */
    Always,
}

/** The reasoning control a model gets in the composer, and what to send for the user's choice. */
data class ReasoningSupport(
    val mode: ReasoningMode = ReasoningMode.Optional,
    /** Effort levels the model accepts besides "model default". [ReasoningEffort.Off] is never listed here. */
    val levels: List<ReasoningEffort> = ReasoningEffort.entries.filter { it != ReasoningEffort.Off },
) {
    val canDisable: Boolean get() = mode == ReasoningMode.Optional

    /** A saved effort that the model no longer accepts snaps to the nearest valid level (ties go deeper). */
    fun snap(effort: ReasoningEffort?): ReasoningEffort? {
        if (effort == null || effort == ReasoningEffort.Off || mode == ReasoningMode.Unsupported) return null
        if (effort in levels) return effort
        return levels.minWithOrNull(compareBy<ReasoningEffort> { kotlin.math.abs(it.ordinal - effort.ordinal) }.thenByDescending { it.ordinal })
    }

    /** What to put in [LlmRequest.reasoning] for the user's switch and chosen effort; null is the model default. */
    fun request(enabled: Boolean, effort: ReasoningEffort?): ReasoningEffort? = when (mode) {
        ReasoningMode.Unsupported -> null
        ReasoningMode.Always -> snap(effort)
        ReasoningMode.Optional -> if (enabled) snap(effort) else ReasoningEffort.Off
    }

    companion object {
        val Default = ReasoningSupport()
        val None = ReasoningSupport(ReasoningMode.Unsupported, emptyList())

        /** From a level list where [ReasoningEffort.Off] means "can be switched off" and an empty list means forced. */
        fun of(supported: Boolean, levels: List<ReasoningEffort>): ReasoningSupport = when {
            !supported -> None
            ReasoningEffort.Off in levels -> ReasoningSupport(ReasoningMode.Optional, levels.filter { it != ReasoningEffort.Off }.sorted())
            else -> ReasoningSupport(ReasoningMode.Always, levels.sorted())
        }
    }
}
