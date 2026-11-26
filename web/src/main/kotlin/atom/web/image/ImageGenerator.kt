package atom.web.image

import android.content.Context
import atom.web.ProviderKeys
import atom.web.SecretStore
import atom.web.net.Http
import atom.web.search.WebServices
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/** Resolves credentials, calls the chosen backend and stores the results in the gallery. */
class ImageGenerator(
    private val context: Context,
    private val config: () -> ImageConfig,
    private val keys: ProviderKeys,
    private val secrets: SecretStore,
) {
    suspend fun target(cfg: ImageConfig = config()): ImageTarget {
        val b = cfg.backend
        val vertex = b == ImageBackend.Gemini && cfg.geminiVertex
        val region = when (b) {
            ImageBackend.Qwen -> cfg.qwenRegion
            ImageBackend.Seedream -> cfg.seedreamRegion
            ImageBackend.SiliconFlow -> cfg.siliconRegion
            else -> Region.International
        }
        val preset = cfg.preset(b)
        if (preset?.retired == true) throw ImageApiException(ImageErrors.retired(preset))
        var model = cfg.model(b)
        if (b == ImageBackend.Seedream && region == Region.International) preset?.intlId?.let { model = it }
        val default = ImageEndpoints.defaultBase(b, region, vertex)
        return when (cfg.credential) {
            CredentialSource.Separate -> {
                if (secrets.isUnreadable(ImageConfig.secretName(b))) throw ImageApiException("The saved ${b.label} key can no longer be read. Enter it again in Settings > AI image generation.")
                val key = secrets.get(ImageConfig.secretName(b))?.takeIf { it.isNotBlank() }
                    ?: throw ImageApiException("No ${b.label} key. Enter one in Settings > AI image generation.")
                ImageTarget(b, model, cfg.baseUrls[b]?.takeIf { it.isNotBlank() } ?: default, key, vertex, cfg.vertexProject, cfg.vertexLocation, region)
            }
            CredentialSource.Auto -> {
                val all = keys.all()
                val p = all.firstOrNull { c ->
                    if (vertex) c.isVertex || WebServices.host(c.baseUrl).contains("aiplatform")
                    else !c.isVertex && ImageEndpoints.matches(b, WebServices.host(c.baseUrl), region, false)
                } ?: throw ImageApiException(
                    "No ${b.label} provider with a key was found. Add one under Model providers, or choose \"Separate key\" in Settings > AI image generation.",
                )
                val base = when {
                    vertex -> p.baseUrl.ifBlank { default }
                    b == ImageBackend.Gemini -> default
                    b == ImageBackend.Qwen -> "https://" + WebServices.host(p.baseUrl)
                    else -> p.baseUrl.ifBlank { default }
                }
                ImageTarget(
                    b, model, base, p.apiKey, vertex,
                    if (vertex) p.vertexProject.ifBlank { cfg.vertexProject } else "",
                    if (vertex) p.vertexLocation.ifBlank { cfg.vertexLocation } else "global",
                    region,
                )
            }
        }
    }

    fun request(prompt: String, count: Int, aspect: String?, resolution: String?, quality: Quality?, refs: List<RefImage>, cfg: ImageConfig = config()) =
        ImageRequest(
            prompt = prompt,
            count = count.coerceIn(1, 4),
            aspect = aspect?.takeIf { it in ImagePresets.ASPECTS } ?: cfg.aspect,
            quality = quality ?: cfg.quality,
            resolution = resolution?.uppercase()?.takeIf { it in listOf("1K", "2K", "4K") } ?: cfg.resolution,
            refs = refs,
            seedreamWatermark = cfg.seedreamWatermark,
            qwenPromptExtend = cfg.qwenPromptExtend,
            qwenWatermark = cfg.qwenWatermark,
        )

    /** Runs [req] as a tracked job. Never throws for API errors: the job ends Failed with a reason. */
    suspend fun run(req: ImageRequest, conversationId: String?, save: Boolean = true): ImageJob {
        val cfg = config()
        val id = UUID.randomUUID().toString()
        val job = ImageJob(id, conversationId, req.edit, req.count, req.prompt, cfg.model(), System.currentTimeMillis())
        ImageJobs.put(job)
        return try {
            val t = target(cfg)
            val maxRefs = cfg.preset()?.maxRefs
            val label = cfg.preset()?.label ?: t.model
            if (req.edit && maxRefs == 0) throw ImageApiException(ImageErrors.noEditing(label))
            if (maxRefs != null && req.refs.size > maxRefs) throw ImageApiException(ImageErrors.tooManyReferences(label, maxRefs, req.refs.size))
            val r = req
            val outs = coroutineScope {
                ImageRequests.build(r, t).map { spec ->
                    async {
                        val res = Http.execute(spec, Http.slowClient, maxBytes = 64 * 1024 * 1024)
                        // Redirects are not followed: the key must never go to another address.
                        if (res.code in 300..399) throw ImageApiException(ImageErrors.REDIRECT_BLOCKED)
                        if (!res.ok) throw ImageApiException(ImageErrors.http(t.backend.label, res.code, ImageResponses.error(res.text)))
                        ImageResponses.parse(t.backend, res.text)
                    }
                }.awaitAll().flatten()
            }
            val bytes = outs.take(req.count.coerceAtLeast(1)).map { o ->
                when (o) {
                    is ImageOut.Inline -> java.util.Base64.getMimeDecoder().decode(o.base64)
                    is ImageOut.Remote -> {
                        val d = Http.get(o.url, maxBytes = 48 * 1024 * 1024)
                        if (!d.ok) throw ImageApiException("Could not download the generated image (HTTP ${d.code}).")
                        d.body
                    }
                }
            }
            val items = if (save) bytes.map { ImageGallery.add(context, it, req.prompt, t.model, t.backend, req.edit) } else {
                bytes.mapIndexed { i, b ->
                    val f = java.io.File(context.cacheDir, "image_test_$i.png").apply { writeBytes(b) }
                    GalleryItem(f.path, req.prompt, t.model, t.backend.label, System.currentTimeMillis(), bytes = b.size.toLong())
                }
            }
            val done = job.copy(state = JobState.Done, finishedAt = System.currentTimeMillis(), images = items)
            ImageJobs.put(done)
            done
        } catch (e: kotlinx.coroutines.CancellationException) {
            ImageJobs.put(job.copy(state = JobState.Failed, finishedAt = System.currentTimeMillis(), error = "Stopped"))
            throw e
        } catch (e: Exception) {
            val msg = when (e) {
                is ImageApiException -> e.message
                is java.io.IOException -> "Network error: ${e.message}"
                else -> e.message
            } ?: e.javaClass.simpleName
            val failed = job.copy(state = JobState.Failed, finishedAt = System.currentTimeMillis(), error = msg)
            ImageJobs.put(failed)
            failed
        }
    }
}
