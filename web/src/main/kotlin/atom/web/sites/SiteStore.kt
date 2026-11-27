package atom.web.sites

import atom.core.io.deleteTreeNoFollow
import atom.core.tool.ToolArgumentException
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SiteInfo(val id: String, val title: String, val files: Int, val bytes: Long, val modified: Long)

data class SiteFile(val path: String, val bytes: Long, val modified: Long)

/**
 * The website projects under [root] (the app's `files/sites`). Every path goes through [SitePaths], files are written
 * without following symbolic links and replaced atomically, so the preview never serves half a file. Pure JVM.
 */
class SiteStore(val root: File) {
    fun dir(id: String): File {
        if (!SitePaths.isId(id)) throw ToolArgumentException("$id is not a site id")
        return File(root, id)
    }

    fun exists(id: String): Boolean = SitePaths.isId(id) && SitePaths.isDirectory(File(root, id)) && !SitePaths.isLink(File(root, id))

    /** Creates the site for [name] with a placeholder page; an existing site of that name is kept as it is. */
    fun create(name: String): Pair<String, Boolean> {
        val title = name.trim()
        if (title.isEmpty()) throw ToolArgumentException("name is empty; pass a short name like \"Water tracker\"")
        if (title.length > 60) throw ToolArgumentException("name is longer than 60 characters")
        val id = SitePaths.idOf(title) ?: throw ToolArgumentException("Use letters or digits in the name, like \"Water tracker\"")
        if (exists(id)) return id to false
        if (all().size >= MAX_SITES) throw ToolArgumentException("There are already $MAX_SITES sites. Delete one in Settings > Sites first.")
        val d = File(root, id)
        if (SitePaths.isLink(root) || SitePaths.isLink(d)) throw IOException("The sites folder is not usable")
        if (!d.mkdirs() && !SitePaths.isDirectory(d)) throw IOException("Cannot create the folder for $id")
        write(id, "index.html", placeholder(title))
        originTag(id)
        return id to true
    }

    /** Writes [content] to [path] in site [id]. Returns the stored size in bytes. */
    fun write(id: String, path: String, content: String): Long {
        val clean = SitePaths.clean(path)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE) throw ToolArgumentException("$clean would be ${bytes.size / 1024} KB; a site file can be at most ${MAX_FILE / 1024} KB. Split it into smaller files.")
        val base = dir(id)
        if (!exists(id)) throw ToolArgumentException("There is no site $id; call site_create first.")
        val target = SitePaths.inside(base, clean) ?: throw ToolArgumentException("$clean is not inside the site")
        if (SitePaths.isDirectory(target)) throw ToolArgumentException("$clean is a folder")
        val existing = if (SitePaths.isRegularFile(target)) target.length() else 0L
        val files = files(id)
        if (existing == 0L && files.size >= MAX_FILES) throw ToolArgumentException("A site can have at most $MAX_FILES files.")
        if (files.sumOf { it.bytes } - existing + bytes.size > MAX_SITE) throw ToolArgumentException("The site would be larger than ${MAX_SITE / (1024 * 1024)} MB.")
        val parent = target.parentFile ?: base
        // Create the folders one by one, refusing a symbolic link on the way.
        var d = base
        for (part in clean.split('/').dropLast(1)) {
            d = File(d, part)
            if (SitePaths.isLink(d)) throw ToolArgumentException("$clean is not inside the site")
            if (!SitePaths.isDirectory(d)) {
                if (d.exists()) throw ToolArgumentException("${d.name} is a file, not a folder")
                if (!d.mkdir()) throw IOException("Cannot create the folder ${d.name}")
            }
        }
        val tmp = File(parent, ".tmp-${System.nanoTime()}")
        try {
            Files.write(tmp.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
        return bytes.size.toLong()
    }

    /** The text of [path] in site [id]. */
    fun read(id: String, path: String): String {
        val clean = SitePaths.clean(path)
        if (!exists(id)) throw ToolArgumentException("There is no site $id.")
        val f = SitePaths.inside(dir(id), clean) ?: throw ToolArgumentException("$clean is not inside the site")
        if (!SitePaths.isRegularFile(f)) throw ToolArgumentException("$clean does not exist in $id. Files: ${files(id).joinToString { it.path }.ifEmpty { "none" }}")
        if (f.length() > MAX_FILE) throw ToolArgumentException("$clean is larger than ${MAX_FILE / 1024} KB")
        val bytes = Files.readAllBytes(f.toPath())
        if (bytes.take(4096).any { it == 0.toByte() }) throw ToolArgumentException("$clean is a binary file")
        return bytes.toString(Charsets.UTF_8)
    }

    /** Deletes one file (and folders it leaves empty). */
    fun deleteFile(id: String, path: String) {
        val clean = SitePaths.clean(path)
        if (!exists(id)) throw ToolArgumentException("There is no site $id.")
        val base = dir(id)
        val f = SitePaths.inside(base, clean) ?: throw ToolArgumentException("$clean is not inside the site")
        if (!SitePaths.isRegularFile(f)) throw ToolArgumentException("$clean does not exist in $id")
        Files.delete(f.toPath())
        var p = f.parentFile
        while (p != null && p != base && SitePaths.isDirectory(p) && p.list()?.isEmpty() == true) {
            Files.delete(p.toPath())
            p = p.parentFile
        }
    }

    /**
     * The random part of the site's web origin ([SiteOrigin]), made once when it is first needed and kept in the
     * site's hidden `.origin` file (hidden names are never served, listed, packed or writable by the tools). A site
     * made again after a delete gets a new one, so it starts with empty browser storage.
     */
    @Synchronized
    fun originTag(id: String): String {
        val d = dir(id)
        if (!exists(id)) throw ToolArgumentException("There is no site $id.")
        val f = File(d, ORIGIN_FILE)
        readTag(f)?.let { return it }
        if (SitePaths.isLink(f)) throw IOException("The site folder is not usable")
        val random = java.security.SecureRandom()
        val tag = String(CharArray(TAG_LENGTH) { TAG_CHARS[random.nextInt(TAG_CHARS.length)] })
        val tmp = File(d, ".tmp-origin-${System.nanoTime()}")
        try {
            Files.write(tmp.toPath(), tag.toByteArray(Charsets.US_ASCII), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
        return tag
    }

    /** The tag if it was made already; never creates one (for requests, which only read). */
    fun existingOriginTag(id: String): String? = if (exists(id)) readTag(File(dir(id), ORIGIN_FILE)) else null

    private fun readTag(f: File): String? {
        if (!SitePaths.isRegularFile(f)) return null
        val t = runCatching { Files.readAllBytes(f.toPath()).toString(Charsets.US_ASCII).trim() }.getOrNull() ?: return null
        return t.takeIf { it.length == TAG_LENGTH && it.all { c -> c in TAG_CHARS } }
    }

    /** Deletes the whole site, never following links. */
    fun delete(id: String): Boolean {
        val d = dir(id)
        if (!SitePaths.isDirectory(d) && !SitePaths.isLink(d)) return false
        return d.deleteTreeNoFollow()
    }

    /** Regular, visible files of a site, relative paths with "/", sorted. Links are skipped, never entered. */
    fun files(id: String): List<SiteFile> {
        val base = dir(id)
        if (!exists(id)) return emptyList()
        val out = ArrayList<SiteFile>()
        Files.walkFileTree(base.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (d != base.toPath() && d.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !f.fileName.toString().startsWith(".")) {
                    val rel = base.toPath().relativize(f).joinToString("/")
                    out += SiteFile(rel, attrs.size(), attrs.lastModifiedTime().toMillis())
                }
                return if (out.size > MAX_FILES * 2) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(f: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
        return out.sortedBy { it.path }
    }

    /** Every site, newest change first. */
    fun all(): List<SiteInfo> {
        val dirs = root.listFiles()?.filter { SitePaths.isId(it.name) && SitePaths.isDirectory(it) && !SitePaths.isLink(it) }.orEmpty()
        return dirs.map { d ->
            val files = files(d.name)
            SiteInfo(d.name, title(d.name) ?: d.name, files.size, files.sumOf { it.bytes }, files.maxOfOrNull { it.modified } ?: d.lastModified())
        }.sortedByDescending { it.modified }
    }

    /** The `<title>` of the site's index.html, if it has one. */
    fun title(id: String): String? {
        val f = SitePaths.inside(dir(id), "index.html") ?: return null
        if (!SitePaths.isRegularFile(f)) return null
        val head = runCatching { f.inputStream().use { s -> ByteArray(16 * 1024).let { b -> String(b, 0, s.read(b).coerceAtLeast(0), Charsets.UTF_8) } } }.getOrNull() ?: return null
        val t = Regex("<title[^>]*>([^<]{1,120})</title>", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.trim()
        return t?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
    }

    /** Packs the site's files into [out] (a .zip), links and hidden files left out. */
    fun zip(id: String, out: File): File {
        val base = dir(id)
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (f in files(id)) {
                val file = SitePaths.inside(base, f.path) ?: continue
                if (!SitePaths.isRegularFile(file)) continue
                zip.putNextEntry(ZipEntry("$id/${f.path}").apply { time = f.modified })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    companion object {
        const val MAX_FILE = 2 * 1024 * 1024
        const val MAX_SITE = 20L * 1024 * 1024
        const val MAX_FILES = 300
        const val MAX_SITES = 100
        const val TAG_LENGTH = 6
        private const val TAG_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val ORIGIN_FILE = ".origin"

        fun placeholder(title: String): String {
            val safe = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>$safe</title>
                <style>
                  :root { color-scheme: light dark; }
                  body { font-family: system-ui, sans-serif; margin: 0; min-height: 100vh; display: grid; place-items: center; }
                  p { opacity: .6; }
                </style>
                </head>
                <body>
                <main><h1>$safe</h1><p>Atom is building this page.</p></main>
                </body>
                </html>
            """.trimIndent() + "\n"
        }
    }
}
