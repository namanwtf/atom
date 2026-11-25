package atom.xposed

/**
 * Limited retries with backoff for the hooks that repair state the ROM takes away (assistant role, accessibility
 * service). At most [maxAttempts] repairs inside any [windowMs]; the n-th repair in a window waits [delaysMs][n]
 * (the last delay repeats). When the budget is spent the hook stops fighting until the window has passed, so a
 * ROM that keeps undoing the change cannot turn this into a loop. Not thread-safe; callers serialize.
 */
class RetryBudget(
    private val maxAttempts: Int,
    private val windowMs: Long,
    private val delaysMs: List<Long>,
) {
    private val attempts = ArrayDeque<Long>()

    init {
        require(maxAttempts > 0 && windowMs > 0 && delaysMs.isNotEmpty())
    }

    /** Delay before the next repair, or null if the budget for the current window is spent. Does not consume. */
    fun nextDelay(now: Long): Long? {
        prune(now)
        if (attempts.size >= maxAttempts) return null
        return delaysMs[minOf(attempts.size, delaysMs.lastIndex)]
    }

    /** Records a repair attempt at [now]. */
    fun consume(now: Long) {
        prune(now)
        attempts.addLast(now)
    }

    val used: Int get() = attempts.size

    private fun prune(now: Long) {
        while (attempts.isNotEmpty() && now - attempts.first() >= windowMs) attempts.removeFirst()
    }
}
