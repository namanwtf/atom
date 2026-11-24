package atom.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Event-driven "wait until the screen stops changing". The accessibility service calls [signal] for each
 * window event; [awaitSettle] suspends until a quiet gap with no events, capped at [maxMs]. No polling and
 * no fixed sleeps: a static screen returns after the first quiet window, a busy one after the cap.
 */
internal class SettleDetector(
    /** First quiet window, slightly longer because apps often take a beat before reacting to input. */
    private val initialQuietMs: Long = 500,
    private val quietMs: Long = 350,
    private val maxMs: Long = 3_000,
) {
    private val counter = MutableStateFlow(0L)

    fun signal() = counter.update { it + 1 }

    /** True if the screen went quiet; false if it was still changing when the cap was hit. */
    suspend fun awaitSettle(): Boolean {
        var seen = counter.value
        var window = initialQuietMs
        val settled = withTimeoutOrNull(maxMs) {
            var quiet = false
            while (!quiet) {
                val next = withTimeoutOrNull(window) { counter.first { it != seen } }
                if (next == null) quiet = true else seen = next
                window = quietMs
            }
            true
        }
        return settled ?: false
    }
}
