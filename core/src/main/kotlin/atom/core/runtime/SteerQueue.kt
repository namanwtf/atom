package atom.core.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A user message sent while a task runs. */
data class QueuedMessage(
    val id: Long,
    val text: String,
    /** Local paths or content URIs the user attached. */
    val attachments: List<String> = emptyList(),
    /** Images are sent as pixels with the next turn, so they never steer a running task. */
    val hasImages: Boolean = false,
    /** Deliver at the running task's next safe point instead of after it finishes. */
    val steer: Boolean = false,
) {
    val canSteer: Boolean get() = !hasImages
}

/**
 * Messages waiting for one conversation. Steering messages go to the running task at its next safe point in the
 * order they were sent; the rest start the next turn once the task finishes, one turn at a time.
 */
class SteerQueue {
    private var seq = 0L
    private val _items = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val items: StateFlow<List<QueuedMessage>> = _items.asStateFlow()

    val size: Int get() = _items.value.size

    fun add(text: String, attachments: List<String> = emptyList(), hasImages: Boolean = false, steer: Boolean = false): QueuedMessage {
        val message = synchronized(this) { QueuedMessage(++seq, text, attachments, hasImages, steer && !hasImages) }
        _items.update { it + message }
        return message
    }

    fun remove(id: Long) = _items.update { list -> list.filterNot { it.id == id } }

    /** "Send now": steer the running task with this message. Image messages keep waiting for the task to end. */
    fun sendNow(id: Long): Boolean {
        var changed = false
        _items.update { list ->
            changed = false
            list.map {
                if (it.id == id && it.canSteer && !it.steer) {
                    changed = true
                    it.copy(steer = true)
                } else {
                    it
                }
            }
        }
        return changed
    }

    /** Removes and returns the steering messages, oldest first. */
    fun drainSteering(): List<QueuedMessage> {
        var out: List<QueuedMessage> = emptyList()
        _items.update { list ->
            out = list.filter { it.steer }
            list.filterNot { it.steer }
        }
        return out
    }

    /** The message that starts the next turn after the task finished, or null. */
    fun pollNext(): QueuedMessage? {
        var out: QueuedMessage? = null
        _items.update { list ->
            out = list.firstOrNull()
            if (out == null) list else list.drop(1)
        }
        return out
    }

    fun clear() = _items.update { emptyList() }
}
