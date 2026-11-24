package atom.models

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** Turns user-supplied links into [CatalogModel]s. Pure JVM so it is unit-testable. */
internal object ModelUrls {

    private val HF_HOSTS = setOf("huggingface.co", "www.huggingface.co", "hf.co")
    private val SPLIT_GGUF = Regex("""-\d{5}-of-\d{5}\.gguf$""", RegexOption.IGNORE_CASE)

    /** True if a bearer token may be sent to [host] (Hugging Face only, never mirrors or CDNs). */
    fun isHuggingFaceHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h == "huggingface.co" || h.endsWith(".huggingface.co") || h == "hf.co"
    }

    /**
     * Accepts:
     *  - `hf:<org>/<repo>/<path/to/file>`
     *  - `https://huggingface.co/<org>/<repo>/(blob|resolve)/<rev>/<path/to/file>`
     *  - any other `https://` link that ends in `.litertlm` or `.gguf`
     */
    fun parse(input: String, curated: List<CatalogModel> = ModelCatalog.curated): Result<CatalogModel> = runCatching {
        val raw = input.trim()
        require(raw.isNotEmpty()) { "Paste a link to a .litertlm or .gguf file." }
        require(raw.none { it.isWhitespace() }) { "The link contains spaces; paste just the file URL." }

        val model = if (raw.startsWith("hf:", ignoreCase = true)) parseHfShorthand(raw.substring(3)) else parseHttp(raw)
        // A pasted link to a model we already curate keeps the verified size and checksum.
        curated.firstOrNull { it.url == model.url } ?: model
    }

    private fun parseHfShorthand(spec: String): CatalogModel {
        val parts = spec.trim('/').split('/')
        require(parts.size >= 3 && parts.all { it.isNotEmpty() }) {
            "Use hf:<org>/<repo>/<file>, for example hf:Qwen/Qwen3-4B-GGUF/Qwen3-4B-Q4_K_M.gguf"
        }
        val (org, repo) = parts
        val filePath = parts.drop(2)
        return hfModel(org, repo, "main", filePath)
    }

    private fun parseHttp(raw: String): CatalogModel {
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("That doesn't look like a valid link.")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "Only https:// links (or hf:org/repo/file) are supported." }
        require(scheme == "https") { "For safety only https:// links are allowed." }
        val host = uri.host?.lowercase()
        require(!host.isNullOrEmpty()) { "That link has no host." }
        require(uri.userInfo == null) { "Links with a user name or password are not allowed." }

        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { decode(it) }

        if (host in HF_HOSTS) {
            // <org>/<repo>/(blob|resolve)/<rev>/<file...>
            require(segments.size >= 5 && (segments[2] == "blob" || segments[2] == "resolve")) {
                "Link to a single file on Hugging Face (open the file, then copy its link), not the repo page."
            }
            return hfModel(segments[0], segments[1], segments[3], segments.drop(4))
        }

        require(segments.isNotEmpty()) { "The link must point to a .litertlm or .gguf file." }
        checkSegments(segments)
        val fileName = segments.last()
        val format = formatOf(fileName)
        val cleanUrl = raw.substringBefore('#')
        val stem = fileName.substringBeforeLast('.')
        return linkModel(
            id = "url-" + slug(stem, 60) + "-" + shortHash(cleanUrl),
            name = stem,
            format = format,
            url = cleanUrl,
        )
    }

    private fun hfModel(org: String, repo: String, rev: String, filePath: List<String>): CatalogModel {
        checkSegments(listOf(org, repo, rev) + filePath)
        val fileName = filePath.last()
        val format = formatOf(fileName)
        val url = "https://huggingface.co/" + (listOf(org, repo, "resolve", rev) + filePath).joinToString("/") { encode(it) }
        val stem = fileName.substringBeforeLast('.')
        return linkModel(
            id = "hf-" + slug("$org-$repo-$stem", 90) + if (rev == "main") "" else "-" + shortHash(url),
            name = stem,
            format = format,
            url = url,
        )
    }

    private fun linkModel(id: String, name: String, format: ModelFormat, url: String) = CatalogModel(
        id = id,
        name = name,
        format = format,
        url = url,
        sizeBytes = 0L,
        sha256 = null,
        minRamGb = 0,
        license = "Unknown",
        gated = false,
        summary = "Added from a link.",
        supportsVision = false,
    )

    private fun checkSegments(segments: List<String>) {
        require(segments.none { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }) {
            "That link has an invalid path."
        }
    }

    private fun formatOf(fileName: String): ModelFormat {
        require(!SPLIT_GGUF.containsMatchIn(fileName)) {
            "Split GGUF files (…-00001-of-0000N.gguf) aren't supported; choose a single-file quant."
        }
        return ModelFormat.fromFileName(fileName)
            ?: throw IllegalArgumentException("Unsupported file type. Pick a .litertlm or .gguf file.")
    }

    private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") // String overload: API 30 safe

    private fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch.isLetterOrDigit() && c < 0x80 || ch in "-._~") append(ch) else append('%').append("%02X".format(c))
        }
    }

    internal fun slug(s: String, max: Int): String =
        s.lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-', '.').take(max).trimEnd('-', '.').ifEmpty { "model" }

    private fun shortHash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
}
