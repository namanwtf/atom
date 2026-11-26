package atom.core.agent

import atom.core.llm.HostedToolKind

/**
 * The provider-hosted calls running right now, one slot per call id, so the status line keeps "Running X" while any
 * call is still running when another finishes. A call without an id has a slot per kind. Not thread-safe: feed it
 * from the one coroutine that reads the run's events.
 */
class HostedCalls {
    private val running = LinkedHashMap<String, HostedToolKind>()

    /** Records a start or finish and returns what the status should show: the newest call still running, or null. */
    fun update(kind: HostedToolKind, started: Boolean, callId: String?): HostedToolKind? {
        val key = callId ?: ("kind:" + kind.name)
        if (started) {
            running.remove(key)
            running[key] = kind
        } else {
            running.remove(key)
        }
        return running.values.lastOrNull()
    }

    fun clear() = running.clear()
}
