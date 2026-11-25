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

    /** Marker of the card the chat shows while generate_image / edit_image is still running. */
    const val LIVE_MARKER = "image_live:"

    /** What a running image call's card needs: whether it edits, how many images, and when the call started. */
    data class LiveSpec(val edit: Boolean, val count: Int, val sinceMs: Long)

    /**
     * Card text for a running call of [tool] (null for other tools), from its JSON arguments and start time, so the
     * chat shows "Generating N images" with a ticking timer before the tool returns.
     */
    fun liveCardText(tool: String, argsJson: String, sinceMs: Long?): String? {
        val edit = when (tool) {
            "generate_image" -> false
            "edit_image" -> true
            else -> return null
        }
        val count = Regex("\"count\"\\s*:\\s*\"?(\\d+)").find(argsJson)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 4) ?: 1
        return "$LIVE_MARKER${if (edit) 1 else 0}:$count:${sinceMs ?: 0}"
    }

    fun parseLive(text: String): LiveSpec? {
        if (!text.startsWith(LIVE_MARKER)) return null
        val parts = text.removePrefix(LIVE_MARKER).split(':')
        if (parts.size < 3) return null
        return LiveSpec(parts[0] == "1", parts[1].toIntOrNull()?.coerceIn(1, 4) ?: 1, parts[2].toLongOrNull() ?: 0)
    }

    /** The job a live card stands for: the newest one of that kind started since the call began. */
    fun liveJob(spec: LiveSpec, jobs: Collection<ImageJob>): ImageJob? =
        jobs.filter { it.edit == spec.edit && (spec.sinceMs <= 0 || it.startedAt >= spec.sinceMs - 2_000) }.maxByOrNull { it.startedAt }

    /** Shown until the generator registers its job: the right title and a timer from the call's start. */
    fun placeholder(spec: LiveSpec): ImageJob =
        ImageJob("live", null, spec.edit, spec.count, "", "", if (spec.sinceMs > 0) spec.sinceMs else System.currentTimeMillis())
}
