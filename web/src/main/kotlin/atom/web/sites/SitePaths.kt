package atom.web.sites

import atom.core.tool.ToolArgumentException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.Normalizer
import java.util.Locale

/**
 * Names and paths of website projects. Pure (no Android), so the rules are unit tested.
 *
 * A site lives in `files/sites/<id>/`, where the id is a short slug of the name the agent gave it. Paths inside a
 * site are always relative, use "/" and never leave the site: no "..", no hidden names, no symlinks.
 */
object SitePaths {
    const val MAX_ID = 40
    private const val MAX_PATH = 200
    private const val MAX_DEPTH = 8
    private const val MAX_SEGMENT = 80

    private val ID = Regex("^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$")

    /** "Water Tracker!" → "water-tracker"; null when nothing usable is left. */
    fun idOf(name: String): String? {
        val plain = Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val id = plain.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_ID).trim('-')
        return id.takeIf { ID.matches(it) }
    }

    fun isId(s: String): Boolean = ID.matches(s)

    /**
     * A path the agent passed ("index.html", "css/style.css", "/app.js") as a clean relative path, or a
     * [ToolArgumentException] that says what to pass instead. A leading "/" means the site root, as in a URL.
     */
    fun clean(raw: String?): String {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) throw ToolArgumentException("path is empty; pass a file inside the site, like index.html or css/style.css")
        if (t.any { it == '\u0000' || it.isISOControl() }) throw ToolArgumentException("path contains control characters")
        if ('\\' in t) throw ToolArgumentException("Use / in paths, not \\")
        if (t.startsWith("~") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(t)) {
            throw ToolArgumentException("$t is not a path inside the site; pass a relative path like index.html")
        }
        if (t.startsWith("//")) throw ToolArgumentException("$t is not a path inside the site; pass a relative path like index.html")
        val rel = t.removePrefix("/").removePrefix("./")
        if (rel.isEmpty()) throw ToolArgumentException("path names the site folder itself; pass a file such as index.html")
        if (rel.length > MAX_PATH) throw ToolArgumentException("path is longer than $MAX_PATH characters")
        val parts = rel.split('/')
        if (parts.size > MAX_DEPTH) throw ToolArgumentException("path is nested more than $MAX_DEPTH folders deep")
        for (p in parts) {
            when {
                p.isEmpty() -> throw ToolArgumentException("path has an empty folder name (//)")
                p == "." || p == ".." -> throw ToolArgumentException("path must stay inside the site (no . or ..)")
                p.startsWith(".") -> throw ToolArgumentException("hidden names (starting with a dot) are not allowed: $p")
                p.length > MAX_SEGMENT -> throw ToolArgumentException("the name $p is longer than $MAX_SEGMENT characters")
                p.any { it in FORBIDDEN } -> throw ToolArgumentException("the name $p contains a character URLs cannot carry plainly (% ? # and similar)")
            }
        }
        return parts.joinToString("/")
    }

    private const val FORBIDDEN = "%?#*\"<>|:"

    /**
     * The file [clean] path names inside [root], or null when the path, or any folder on the way, is a symbolic link
     * or resolves outside [root]. The file itself need not exist.
     */
    fun inside(root: File, clean: String): File? {
        if (isLink(root)) return null
        var f = root
        for (part in clean.split('/')) {
            f = File(f, part)
            if (isLink(f)) return null
        }
        val base = root.canonicalPath
        val c = f.canonicalPath
        return f.takeIf { c == base || c.startsWith(base + File.separator) }
    }

    fun isLink(f: File): Boolean = runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)

    fun isRegularFile(f: File): Boolean = runCatching { Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)

    fun isDirectory(f: File): Boolean = runCatching { Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)

    /** Content type for a file name, with a UTF-8 charset for text types. Unknown types are binary. */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json; charset=utf-8"
        "txt", "md" -> "text/plain; charset=utf-8"
        "csv" -> "text/csv; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "bmp" -> "image/bmp"
        "wasm" -> "application/wasm"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    fun isHtml(name: String): Boolean = mimeOf(name).startsWith("text/html")
}
