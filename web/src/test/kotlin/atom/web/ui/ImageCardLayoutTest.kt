package atom.web.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the finished image card's layout without a device: the prompt block must be a direct child of the
 * `JobState.Done` branch (a sibling of the thumbnails' Row), never inside that Row, where a full-width child
 * leaves the weighted thumbnails zero width.
 */
class ImageCardLayoutTest {
    @Test
    fun promptIsNotInsideTheThumbnailRow() {
        val src = File("src/main/kotlin/atom/web/ui/ImageViews.kt").readText()
        val done = src.indexOf("JobState.Done -> {")
        assertTrue(done >= 0, "Done branch not found")
        val prompt = src.indexOf("if (job.prompt.isNotBlank())", done)
        assertTrue(prompt > done, "prompt block not found in the Done branch")
        // Brace depth from just inside the Done branch's "{" to the prompt block: 0 means a direct child.
        var depth = 0
        for (ch in src.substring(done + "JobState.Done -> {".length, prompt)) {
            if (ch == '{') depth++ else if (ch == '}') depth--
        }
        assertEquals(0, depth, "the prompt block must sit directly in the Done branch, after the thumbnail Row")
    }
}
