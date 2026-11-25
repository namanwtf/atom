package atom.web.image

import atom.web.browser.UrlInput
import atom.web.search.JsonPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageRequestsTest {
    private val ref = RefImage("image/png", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))

    private fun target(b: ImageBackend, model: String, base: String = ImageEndpoints.defaultBase(b, Region.China), region: Region = Region.China) =
        ImageTarget(b, model, base, "KEY", region = region)

    private fun body(spec: atom.web.net.HttpSpec) = JsonPath.parse(spec.body!!)

    @Test
    fun geminiAiStudio() {
        val specs = ImageRequests.build(ImageRequest("a cat", count = 2, aspect = "16:9", resolution = "2K", refs = listOf(ref)), target(ImageBackend.Gemini, "gemini-3-pro-image"))
        assertEquals(2, specs.size)
        val s = specs[0]
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro-image:generateContent", s.url)
        assertEquals("KEY", s.headers["x-goog-api-key"])
        val b = body(s)
        assertEquals("image/png", JsonPath.string(b, "contents[0].parts[0].inlineData.mimeType"))
        assertEquals("a cat", JsonPath.string(b, "contents[0].parts[1].text"))
        assertEquals("16:9", JsonPath.string(b, "generationConfig.imageConfig.aspectRatio"))
        assertEquals("2K", JsonPath.string(b, "generationConfig.imageConfig.imageSize"))
        assertEquals("IMAGE", JsonPath.string(b, "generationConfig.responseModalities[1]"))
    }

    @Test
    fun geminiFixedSizeModelAndVertexUrls() {
        val s = ImageRequests.gemini(ImageRequest("x", aspect = "auto", resolution = "4K"), target(ImageBackend.Gemini, "gemini-2.5-flash-image"))
        assertNull(JsonPath.first(body(s), "generationConfig.imageConfig"))
        val express = ImageTarget(ImageBackend.Gemini, "m", "https://aiplatform.googleapis.com", "K", vertex = true)
        assertEquals("https://aiplatform.googleapis.com/v1/publishers/google/models/m:generateContent", ImageRequests.geminiUrl(express))
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/p1/locations/global/publishers/google/models/m:generateContent",
            ImageRequests.geminiUrl(express.copy(vertexProject = "p1")),
        )
        assertEquals(
            "https://europe-west4-aiplatform.googleapis.com/v1/projects/p1/locations/europe-west4/publishers/google/models/m:generateContent",
            ImageRequests.geminiUrl(express.copy(vertexProject = "p1", vertexLocation = "europe-west4")),
        )
        val oauth = ImageRequests.gemini(ImageRequest("x"), express.copy(apiKey = "ya29.token"))
        assertEquals("Bearer ya29.token", oauth.headers["Authorization"])
    }

    @Test
    fun openAiGenerateAndEdit() {
        val gen = ImageRequests.build(ImageRequest("p", count = 3, aspect = "3:2", quality = Quality.High), target(ImageBackend.OpenAI, "gpt-image-2.6-flare")).single()
        assertEquals("https://api.openai.com/v1/images/generations", gen.url)
        assertEquals("Bearer KEY", gen.headers["Authorization"])
        val b = body(gen)
        assertEquals("1536x1024", JsonPath.string(b, "size"))
        assertEquals("high", JsonPath.string(b, "quality"))
        assertEquals("3", JsonPath.string(b, "n"))
        val edit = ImageRequests.build(ImageRequest("p", refs = listOf(ref, ref)), target(ImageBackend.OpenAI, "gpt-image-2.6-sunburst")).single()
        assertEquals("https://api.openai.com/v1/images/edits", edit.url)
        assertEquals(2, edit.multipart.count { it.name == "image[]" })
        assertEquals("p", edit.multipart.first { it.name == "prompt" }.value)
    }

    @Test
    fun openAiSizes() {
        assertEquals("auto", ImageSizes.openAi("gpt-image-2.6-flare", "auto", "2K"))
        assertEquals("1024x1536", ImageSizes.openAi("gpt-image-1.5", "9:16", "4K"))
        assertEquals("2048x1152", ImageSizes.openAi("gpt-image-2.6-flare", "16:9", "2K"))
        assertEquals("3840x2160", ImageSizes.openAi("gpt-image-2.5-flare", "16:9", "4K"))
        val (w, h) = ImageSizes.openAi("gpt-image-2.6-flare", "1:1", "4K").split('x').map(String::toInt)
        assertTrue(w.toLong() * h <= 3840L * 2160 && w % 16 == 0 && w == h)
    }

    @Test
    fun xaiGenerateAndEdit() {
        val gen = ImageRequests.xai(ImageRequest("p", count = 2, aspect = "9:16", resolution = "2K"), target(ImageBackend.Xai, "grok-imagine-image-2.0"))
        assertEquals("https://api.x.ai/v1/images/generations", gen.url)
        val b = body(gen)
        assertEquals("b64_json", JsonPath.string(b, "response_format"))
        assertEquals("9:16", JsonPath.string(b, "aspect_ratio"))
        assertEquals("2k", JsonPath.string(b, "resolution"))
        val one = ImageRequests.xai(ImageRequest("p", refs = listOf(ref)), target(ImageBackend.Xai, "grok-imagine-image"))
        assertEquals("https://api.x.ai/v1/images/edits", one.url)
        assertEquals(ref.dataUri, JsonPath.string(body(one), "image.url"))
        val many = ImageRequests.xai(ImageRequest("p", refs = List(7) { ref }), target(ImageBackend.Xai, "grok-imagine-image-2.0"))
        assertEquals(5, JsonPath.items(body(many), "images").size)
    }

    @Test
    fun seedream() {
        val t = target(ImageBackend.Seedream, "doubao-seedream-4-5-251128")
        val seq = ImageRequests.seedream(ImageRequest("p", count = 3, aspect = "auto", resolution = "1K", refs = listOf(ref, ref)), t)
        assertEquals(1, seq.size)
        assertEquals("https://ark.cn-beijing.volces.com/api/v3/images/generations", seq[0].url)
        val b = body(seq[0])
        assertEquals("2K", JsonPath.string(b, "size"))
        assertEquals("auto", JsonPath.string(b, "sequential_image_generation"))
        assertEquals("3", JsonPath.string(b, "sequential_image_generation_options.max_images"))
        assertEquals("false", JsonPath.string(b, "watermark"))
        assertEquals(2, JsonPath.items(b, "image").size)

        val pro = ImageRequests.seedream(ImageRequest("p", count = 2, aspect = "16:9", resolution = "2K", refs = listOf(ref)), target(ImageBackend.Seedream, "doubao-seedream-5-5-261028"))
        assertEquals(2, pro.size)
        val pb = body(pro[0])
        assertNull(JsonPath.first(pb, "sequential_image_generation"))
        assertEquals(ref.dataUri, JsonPath.string(pb, "image"))
        val (w, h) = JsonPath.string(pb, "size")!!.split('x').map(String::toInt)
        assertTrue(w > h && w.toLong() * h in 921_600..4_624_220, "$w x $h")
        assertEquals("https://ark.ap-southeast.bytepluses.com/api/v3", ImageEndpoints.defaultBase(ImageBackend.Seedream, Region.International))
    }

    @Test
    fun qwen() {
        val s = ImageRequests.qwen(ImageRequest("p", count = 2, aspect = "16:9", resolution = "4K", refs = listOf(ref), qwenPromptExtend = false), target(ImageBackend.Qwen, "qwen-image-2.0-pro"))
        assertEquals("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", s.url)
        val b = body(s)
        assertEquals(ref.dataUri, JsonPath.string(b, "input.messages[0].content[0].image"))
        assertEquals("p", JsonPath.string(b, "input.messages[0].content[1].text"))
        assertEquals("false", JsonPath.string(b, "parameters.prompt_extend"))
        assertEquals("false", JsonPath.string(b, "parameters.watermark"))
        val (w, h) = JsonPath.string(b, "parameters.size")!!.split('*').map(String::toInt)
        assertTrue(w % 16 == 0 && h % 16 == 0 && w.toLong() * h <= 2048L * 2048 && w > h)
        val viaProvider = ImageRequests.qwen(ImageRequest("p"), target(ImageBackend.Qwen, "qwen-image-2.0", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"))
        assertEquals("https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation", viaProvider.url)
        assertEquals("1024*1024", JsonPath.string(body(viaProvider), "parameters.size"))
        val editAuto = ImageRequests.qwen(ImageRequest("p", refs = listOf(ref)), target(ImageBackend.Qwen, "qwen-image-2.0"))
        assertNull(JsonPath.first(body(editAuto), "parameters.size"))
    }

    @Test
    fun siliconFlow() {
        val q = ImageRequests.siliconFlow(ImageRequest("p", count = 3, aspect = "16:9"), target(ImageBackend.SiliconFlow, "Qwen/Qwen-Image")).single()
        assertEquals("https://api.siliconflow.cn/v1/images/generations", q.url)
        assertEquals("1664x928", JsonPath.string(body(q), "image_size"))
        assertEquals("3", JsonPath.string(body(q), "batch_size"))
        val flux = ImageRequests.siliconFlow(ImageRequest("p", count = 2, aspect = "9:16"), target(ImageBackend.SiliconFlow, "black-forest-labs/FLUX.1-schnell"))
        assertEquals(2, flux.size)
        assertEquals("576x1024", JsonPath.string(body(flux[0]), "image_size"))
        val edit = ImageRequests.siliconFlow(ImageRequest("p", refs = listOf(ref)), target(ImageBackend.SiliconFlow, "Qwen/Qwen-Image-Edit")).single()
        assertEquals(ref.dataUri, JsonPath.string(body(edit), "image"))
        assertNull(JsonPath.first(body(edit), "image_size"))
    }

    @Test
    fun parsesResponses() {
        val g = ImageResponses.parse(ImageBackend.Gemini, """{"candidates":[{"content":{"parts":[{"text":"Here"},{"inlineData":{"mimeType":"image/jpeg","data":"AAA"}}]}}]}""")
        assertEquals(ImageOut.Inline("image/jpeg", "AAA"), g.single())
        val refusal = assertFailsWith<ImageApiException> {
            ImageResponses.parse(ImageBackend.Gemini, """{"candidates":[{"content":{"parts":[{"text":"I can't draw that."}]}}]}""")
        }
        assertTrue("I can't draw that." in refusal.message!!)
        val o = ImageResponses.parse(ImageBackend.OpenAI, """{"data":[{"b64_json":"iVBORw0"},{"url":"https://u/x.png"}],"output_format":"png"}""")
        assertEquals(ImageOut.Inline("image/png", "iVBORw0"), o[0])
        assertEquals(ImageOut.Remote("https://u/x.png"), o[1])
        val q = ImageResponses.parse(ImageBackend.Qwen, """{"output":{"choices":[{"message":{"content":[{"image":"https://q/1.png"},{"image":"https://q/2.png"}]}}]}}""")
        assertEquals(2, q.size)
        val sf = ImageResponses.parse(ImageBackend.SiliconFlow, """{"images":[{"url":"https://s/1.png"}],"seed":1}""")
        assertIs<ImageOut.Remote>(sf.single())
        val err = assertFailsWith<ImageApiException> { ImageResponses.parse(ImageBackend.Seedream, """{"error":{"message":"quota exceeded"}}""") }
        assertTrue("quota exceeded" in err.message!!)
        assertEquals("bad key", ImageResponses.error("""{"error":{"message":"bad key"}}"""))
    }

    @Test
    fun presetsAndMatching() {
        ImageBackend.entries.forEach { assertTrue(ImagePresets.default(it).recommended) }
        assertTrue(ImagePresets.find(ImageBackend.OpenAI, "gpt-image-1")!!.retired)
        assertEquals("seedream-4-5-251128", ImagePresets.find(ImageBackend.Seedream, "doubao-seedream-4-5-251128")!!.intlId)
        assertTrue(ImageEndpoints.matches(ImageBackend.Qwen, "dashscope.aliyuncs.com", Region.China, false))
        assertTrue(!ImageEndpoints.matches(ImageBackend.Qwen, "dashscope.aliyuncs.com", Region.International, false))
        assertTrue(ImageEndpoints.matches(ImageBackend.SiliconFlow, "api.siliconflow.com", Region.International, false))
        assertEquals("gemini-nano-banana-2.1", ImageConfig().model())
    }

    @Test
    fun addressBarInput() {
        assertEquals("https://example.com/a", UrlInput.resolve("example.com/a"))
        assertEquals("https://x.org", UrlInput.resolve("https://x.org"))
        assertEquals("https://www.google.com/search?q=paper+boats", UrlInput.resolve("paper boats"))
        assertEquals("https://www.google.com/search?q=kotlin", UrlInput.resolve("kotlin"))
    }
}
