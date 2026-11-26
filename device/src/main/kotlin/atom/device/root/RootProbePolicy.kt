package atom.device.root

/** What asks for a root check. Only [Manual] is the user's own tap; the rest are automatic. */
enum class ProbeTrigger {
    /** Once per process, in the background, when the app starts. */
    AppStart,

    /** Atom came back to the foreground (for example from the root manager, where the user allowed Atom). */
    Foreground,

    /** A tool or a shell needs to know right now. */
    ToolUse,

    /** Authorize / Check again. */
    Manual,
}

/** What is remembered between probes, and across restarts. */
data class RootMemory(
    val status: RootStatus = RootStatus.Unknown,
    /** The user asked for root (Authorize / Check again) or root was granted once: worth checking at start. */
    val chosen: Boolean = false,
    /**
     * Superuser prompts that automatic checks showed and that ended without a grant, since the last explicit
     * check. At [RootProbePolicy.MAX_STRIKES] nothing is probed automatically any more until the user taps
     * Authorize / Check again.
     */
    val strikes: Int = 0,
)

/**
 * When Atom probes root on its own, and what a probe's result means for the next one. Pure: [RootAccess] feeds it
 * the facts and does the probing.
 *
 * A check of a granted root is silent, and so is a refusal the root manager remembers (or KernelSU, which never
 * prompts), so those are re-checked freely. A check that showed a superuser prompt the user refused, or let time
 * out twice, is never repeated automatically: the user's decision stands until they tap Check again.
 */
object RootProbePolicy {
    /** A refusal that took longer than this came from a prompt the user saw and answered. */
    const val PROMPT_SEEN_MS = 2_000L

    const val MAX_STRIKES = 2

    /** Foreground re-checks closer together than this are skipped (the root manager's prompt itself pauses Atom). */
    const val FOREGROUND_GAP_MS = 5_000L

    fun blocked(memory: RootMemory): Boolean = memory.strikes >= MAX_STRIKES

    /**
     * Whether [trigger] should start a probe now.
     *
     * [probing] is true while a probe runs; [verifiedThisProcess] once one finished in this process;
     * [rootManagerPresent] when a su binary or a root manager app is installed; [sinceLastProbeMs] is the time
     * since the last probe finished in this process (null if none did).
     */
    fun shouldProbe(
        trigger: ProbeTrigger,
        memory: RootMemory,
        probing: Boolean,
        verifiedThisProcess: Boolean,
        rootManagerPresent: Boolean,
        sinceLastProbeMs: Long?,
    ): Boolean {
        if (probing) return false
        val worthIt = memory.chosen || rootManagerPresent
        return when (trigger) {
            ProbeTrigger.Manual -> true
            ProbeTrigger.AppStart -> when {
                verifiedThisProcess -> false
                memory.status == RootStatus.Granted -> true
                blocked(memory) -> false
                else -> worthIt
            }
            ProbeTrigger.Foreground -> when {
                memory.status == RootStatus.Granted -> false
                blocked(memory) -> false
                sinceLastProbeMs != null && sinceLastProbeMs < FOREGROUND_GAP_MS -> false
                else -> worthIt
            }
            // A tool never causes a prompt the user already turned down: only unknown or granted root is checked.
            ProbeTrigger.ToolUse -> when {
                verifiedThisProcess -> false
                memory.status == RootStatus.Unknown || memory.status == RootStatus.Granted -> true
                memory.status == RootStatus.NotDetected -> rootManagerPresent
                else -> false
            }
        }
    }

    /** The memory after a probe started by [trigger] returned [result] after [durationMs]. */
    fun afterProbe(memory: RootMemory, trigger: ProbeTrigger, result: RootStatus, durationMs: Long): RootMemory {
        val manual = trigger == ProbeTrigger.Manual
        val base = if (manual) 0 else memory.strikes
        val strikes = when (result) {
            RootStatus.Granted -> 0
            RootStatus.TimedOut -> base + 1
            RootStatus.NotGranted -> if (durationMs >= PROMPT_SEEN_MS) MAX_STRIKES else base
            else -> base
        }
        return RootMemory(
            status = result,
            chosen = memory.chosen || manual || result == RootStatus.Granted,
            strikes = strikes.coerceAtMost(MAX_STRIKES),
        )
    }
}
