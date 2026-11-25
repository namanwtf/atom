package atom.web.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageParityTest {
    @Test
    fun errorTexts() {
        val old = ImagePresets.find(ImageBackend.OpenAI, "gpt-image-1")!!
        assertNotNull(old)
        assertTrue(ImageErrors.retired(old).startsWith("gpt-image-1 has been retired by OpenAI"))
        assertTrue(ImageErrors.noEditing("Kolors").startsWith("The current model (Kolors) does not support image editing"))
        assertTrue(ImageErrors.tooManyReferences("Grok Imagine 2.0", 5, 7).contains("at most 5 reference images; 7 were given"))
        assertTrue(ImageErrors.REDIRECT_BLOCKED.contains("API key"))
        assertEquals("OpenAI: HTTP 429 – rate limit or quota reached; wait a moment or check your plan – slow down", ImageErrors.http("OpenAI", 429, "slow down"))
        assertTrue(ImageErrors.http("Gemini", 401, null).endsWith("check it in Settings > AI image generation"))
        assertEquals("the image service had a problem; try again later", ImageErrors.explain(503))
    }

    @Test
    fun liveCardWhileTheCallRuns() {
        assertNull(ImageJobs.liveCardText("web_search", "{}", 5))
        val text = ImageJobs.liveCardText("generate_image", "{\n  \"prompt\": \"cat\",\n  \"count\": 3\n}", 1_000)!!
        val spec = ImageJobs.parseLive(text)!!
        assertEquals(ImageJobs.LiveSpec(edit = false, count = 3, sinceMs = 1_000), spec)
        assertEquals(ImageJobs.LiveSpec(true, 1, 0), ImageJobs.parseLive(ImageJobs.liveCardText("edit_image", "{}", null)!!))
        assertEquals("Generating 3 images", ImageJobs.placeholder(spec).title)

        val call = ImageJobs.LiveSpec(edit = false, count = 3, sinceMs = 10_000)
        val older = ImageJob("a", null, false, 1, "p", "m", startedAt = 100)
        val mine = ImageJob("b", null, false, 3, "p", "m", startedAt = 10_200)
        val edit = ImageJob("c", null, true, 1, "p", "m", startedAt = 10_500)
        assertEquals("b", ImageJobs.liveJob(call, listOf(older, mine, edit))?.id)
        assertNull(ImageJobs.liveJob(call, listOf(older, edit)))
    }
}
