package atom.web.image

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class JobState { Running, Done, Failed }

/** One generate/edit request, for the chat's image card. */
data class ImageJob(
    val id: String,
    val conversationId: String?,
    val edit: Boolean,
    val count: Int,
    val prompt: String,
    val model: String,
    val startedAt: Long,
    val state: JobState = JobState.Running,
    val finishedAt: Long? = null,
    val images: List<GalleryItem> = emptyList(),
    val error: String? = null,
) {
    val title: String
        get() {
            val noun = if (count == 1) "image" else "images"
            return when (state) {
                JobState.Running -> if (edit) "Editing $count $noun" else "Generating $count $noun"
                JobState.Done -> if (edit) "Edited ${images.size} ${if (images.size == 1) "image" else "images"}" else "Generated ${images.size} ${if (images.size == 1) "image" else "images"}"
                JobState.Failed -> if (edit) "Editing failed" else "Generation failed"
            }
        }
}

object ImageJobs {
    /** Marker in tool results; the chat finds the card with [parseId]. */
    const val MARKER = "image_job:"

    private val state = MutableStateFlow<Map<String, ImageJob>>(emptyMap())
    val jobs: StateFlow<Map<String, ImageJob>> = state.asStateFlow()

    fun put(job: ImageJob) = state.update { m ->
        val next = LinkedHashMap(m)
        next[job.id] = job
        while (next.size > 50) next.remove(next.keys.first())
        next
    }

    fun update(id: String, block: (ImageJob) -> ImageJob) = state.update { m -> m[id]?.let { m + (id to block(it)) } ?: m }

    fun parseId(toolText: String): String? = Regex("${Regex.escape(MARKER)}([A-Za-z0-9-]+)").find(toolText)?.groupValues?.get(1)
}
