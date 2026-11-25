package atom.web.files

import android.content.Context
import android.os.Environment
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.SchemaBuilder
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.web.BaseTool
import atom.web.PrivilegedFiles
import atom.web.PrivilegedScope
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Resolves the `scope` argument and relative paths for every file tool. */
internal class FileScopes(context: Context, private val priv: () -> PrivilegedFiles?) {
    private val app = context.applicationContext
    private val local = LocalFs()
    val workspace: File get() = File(app.filesDir, "atom4_workspace").apply { mkdirs() }

    suspend fun fs(args: JsonObject): Fs = when (val s = args.optString("scope")?.lowercase()?.trim()) {
        null, "", "app", "android" -> local
        "root", "linux" -> {
            val scope = if (s == "root") PrivilegedScope.Root else PrivilegedScope.Linux
            val p = priv() ?: throw ToolArgumentException("${s.replaceFirstChar { it.uppercase() }} file access is not set up in this app.")
            if (!p.available(scope)) {
                throw ToolArgumentException(
                    if (scope == PrivilegedScope.Root) "Root is not available or not granted. Ask the user to grant root in Settings > System enhancements."
                    else "The Linux environment is not installed or not running. It can be set up in Settings > Linux environment.",
                )
            }
            ShellFs(p, scope)
        }
        else -> throw ToolArgumentException("scope must be app, root or linux")
    }

    fun path(args: JsonObject, fs: Fs, key: String = "path", default: String? = null): String {
        val raw = (args.optString(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default
            ?: throw ToolArgumentException("Missing required argument '$key'"))
        if (fs !is LocalFs) return raw
        val expanded = when {
            raw == "~" -> workspace.path
            raw.startsWith("~/") -> File(workspace, raw.removePrefix("~/")).path
            raw.startsWith("/sdcard") -> raw.replaceFirst("/sdcard", Environment.getExternalStorageDirectory().path)
            raw.startsWith("/") -> raw
            else -> File(workspace, raw).path
        }
        return File(expanded).normalize().path
    }

    fun isShared(path: String): Boolean = path.startsWith("/storage/") || path.startsWith("/sdcard")

    fun isAppPrivate(path: String): Boolean = path.startsWith(app.filesDir.parent ?: app.filesDir.path) || path.startsWith(app.cacheDir.path)
}

internal object FileTools {
    private const val MAX_READ = 4 * 1024 * 1024

    private fun SchemaBuilder.scope() =
        string("scope", "app (default: Android storage as this app), root (needs root), or linux (inside the Linux environment)", enum = listOf("app", "root", "linux"))

    private fun deniedHint(e: Throwable, fs: Fs): String {
        val m = e.message.orEmpty()
        return if (fs is LocalFs && (m.contains("EACCES") || m.contains("Permission denied", true))) {
            "$m. The app cannot reach this path; try scope=root if root is available, or ask the user to allow All files access."
        } else m
    }

    fun create(context: Context, priv: () -> PrivilegedFiles?): List<Tool> {
        val s = FileScopes(context, priv)
        return listOf(Read(s), Write(s), Edit(s), Info(s), ListDir(s), Find(s), Grep(s))
    }

    private suspend fun readText(fs: Fs, path: String): String {
        val entry = fs.stat(path) ?: throw ToolArgumentException("No such file: $path")
        if (entry.isDir) throw ToolArgumentException("$path is a directory; use file_list.")
        if (entry.size > MAX_READ) throw ToolArgumentException("$path is ${Sizes.human(entry.size)}; files over 4 MB cannot be opened as text. Use file_grep to find lines.")
        val bytes = fs.read(path, MAX_READ)
        if (Sizes.looksBinary(bytes)) throw ToolArgumentException("$path is a binary file (${Sizes.human(entry.size)}). Use file_info, or view_image for pictures.")
        return bytes.toString(Charsets.UTF_8)
    }

    private class Read(private val s: FileScopes) : BaseTool(
        "file_read",
        "Read a text file with line numbers, in pages. Relative paths are in the agent workspace.",
        schema {
            string("path", "File path", required = true)
            integer("offset", "First line to show, 1-based (default 1)")
            integer("limit", "Lines to show (default 400)")
            scope()
        },
        Risk.Read,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val fs = s.fs(args)
            val path = s.path(args, fs)
            val text = runCatching { readText(fs, path) }.getOrElse { if (it is ToolArgumentException) throw it else return ToolResult.error(deniedHint(it, fs)) }
            val page = LinePaging.page(text, args.optInt("offset") ?: 1, (args.optInt("limit") ?: 400).coerceIn(1, 4000))
            if (page.totalLines == 0) return ToolResult.ok("$path is empty.")
            if (page.text.isEmpty()) return ToolResult.error("offset is past the end; the file has ${page.totalLines} lines.")
            val footer = if (page.hasMore) "\n[Lines ${page.firstLine}–${page.lastLine} of ${page.totalLines}. Continue with offset=${page.lastLine + 1}.]"
            else "\n[Lines ${page.firstLine}–${page.lastLine} of ${page.totalLines}.]"
            return ToolResult.ok("$path\n${page.text}$footer")
        }
    }

    private class Write(private val s: FileScopes) : BaseTool(
        "file_write",
        "Create, overwrite or append to a text file. Folders are created as needed.",
        schema {
            string("path", "File path", required = true)
            string("content", "Text to write", required = true)
            string("mode", "overwrite (default), append, or create (fails if the file exists)", enum = listOf("overwrite", "append", "create"))
            scope()
        },
        Risk.Act,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val fs = s.fs(args)
            val path = s.path(args, fs)
            val content = args.string("content")
            val mode = args.optString("mode")?.lowercase() ?: "overwrite"
            val existing = fs.stat(path)
            if (existing?.isDir == true) return ToolResult.error("$path is a directory.")
            if (mode == "create" && existing != null) return ToolResult.error("$path already exists. Use mode=overwrite or append.")
            val needsOk = fs.label == "root" || (mode == "overwrite" && existing != null && fs is LocalFs && !s.isAppPrivate(path))
            if (needsOk && !ctx.confirm("${if (existing != null && mode != "append") "Overwrite" else "Write"} $path${if (fs.label == "root") " as root" else ""}")) {
                return ToolResult.error("The user declined writing $path.")
            }
            runCatching { fs.write(path, content.toByteArray(), append = mode == "append") }.onFailure { return ToolResult.error(deniedHint(it, fs)) }
            val verb = when {
                mode == "append" -> "Appended ${content.length} characters to"
                existing != null -> "Overwrote"
                else -> "Created"
            }
            val size = fs.stat(path)?.size
            return ToolResult.ok("$verb $path${size?.let { " (${Sizes.human(it)})" } ?: ""}.")
        }
    }

    private class Edit(private val s: FileScopes) : BaseTool(
        "file_edit",
        "Replace an exact piece of text in a file. old_text must match exactly once (copy it from file_read, without line numbers) unless replace_all is true.",
        schema {
            string("path", "File path", required = true)
            string("old_text", "Exact text to replace", required = true)
            string("new_text", "Replacement text (may be empty)", required = true)
            boolean("replace_all", "Replace every occurrence")
            scope()
        },
        Risk.Act,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val fs = s.fs(args)
            val path = s.path(args, fs)
            val text = runCatching { readText(fs, path) }.getOrElse { if (it is ToolArgumentException) throw it else return ToolResult.error(deniedHint(it, fs)) }
            return when (val r = TextEdit.apply(text, args.string("old_text"), args.optString("new_text") ?: "", args.optBoolean("replace_all") == true)) {
                is EditOutcome.Failed -> ToolResult.error(r.reason)
                is EditOutcome.Applied -> {
                    if (fs.label == "root" && !ctx.confirm("Edit $path as root")) return ToolResult.error("The user declined editing $path.")
                    runCatching { fs.write(path, r.content.toByteArray(), append = false) }.onFailure { return ToolResult.error(deniedHint(it, fs)) }
                    ToolResult.ok("Edited $path: ${r.replacements} replacement${if (r.replacements == 1) "" else "s"}, starting at line ${r.firstLine}.")
                }
            }
        }
    }

    private class Info(private val s: FileScopes) : BaseTool(
        "file_info",
        "Show whether a path exists, its type, size, modified time and permissions.",
        schema {
            string("path", "Path", required = true)
            scope()
        },
        Risk.Read,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val fs = s.fs(args)
            val path = s.path(args, fs)
            val e = fs.stat(path) ?: return ToolResult.ok("$path does not exist (scope ${fs.label}).")
            val kind = when {
                e.isLink -> "symbolic link"
                e.isDir -> "directory"
                else -> "file"
            }
            val sb = StringBuilder("$path\nType: $kind")
            if (!e.isDir) sb.append("\nSize: ").append(Sizes.human(e.size)).append(" (").append(e.size).append(" bytes)")
            if (e.isDir) runCatching { fs.list(path).size }.getOrNull()?.let { sb.append("\nEntries: ").append(it) }
            sb.append("\nModified: ").append(date(e.modified))
            sb.append("\nReadable: ").append(e.readable).append(", writable: ").append(e.writable)
            mime(e.name)?.let { sb.append("\nType guess: ").append(it) }
            return ToolResult.ok(sb.toString())
        }
    }

    private class ListDir(private val s: FileScopes) : BaseTool(
        "file_list",
        "List a directory (folders first), in pages.",
        schema {
            string("path", "Directory (default: the agent workspace)")
            integer("offset", "Entries to skip (default 0)")
            integer("limit", "Entries to show (default 200)")
            boolean("show_hidden", "Include dot files")
            scope()
        },
        Risk.Read,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val fs = s.fs(args)
            val path = s.path(args, fs, default = if (fs is LocalFs) s.workspace.path else "/")
            val entries = runCatching { fs.list(path) }.getOrElse { return ToolResult.error(deniedHint(it, fs)) }
                .filter { args.optBoolean("show_hidden") == true || !it.name.startsWith(".") }
                .sortedWith(compareBy<FileEntry> { !it.isDir }.thenBy { it.name.lowercase() })
            if (entries.isEmpty()) return ToolResult.ok("$path is empty.")
            val offset = (args.optInt("offset") ?: 0).coerceAtLeast(0)
            val limit = (args.optInt("limit") ?: 200).coerceIn(1, 1000)
            val page = entries.drop(offset).take(limit)
            val sb = StringBuilder("$path (${entries.size} entries)\n")
            for (e in page) {
                sb.append(if (e.isDir) "[dir]  " else "       ").append(e.name).append(if (e.isDir) "/" else "")
                if (!e.isDir) sb.append("  ").append(Sizes.human(e.size))
                sb.append("  ").append(date(e.modified)).append('\n')
            }
            if (offset + page.size < entries.size) sb.append("[Showing ${offset + 1}–${offset + page.size} of ${entries.size}. Continue with offset=${offset + page.size}.]")
            return ToolResult.ok(sb.toString().trimEnd())
        }
    }

    private class Find(private val s: FileScopes) : BaseTool(
        "file_find",
        "Find files by glob pattern under a folder. '*.pdf' matches names at any depth; 'src/**/*.kt' matches relative paths.",
        schema {
            string("pattern", "Glob pattern", required = true)
            string("root", "Folder to search (default: the agent workspace)")
            integer("limit", "Maximum matches (default 200)")
            scope()
        },
        Risk.Read,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val fs = s.fs(args)
            val root = s.path(args, fs, "root", default = if (fs is LocalFs) s.workspace.path else "/")
            val match = Glob.matcher(args.string("pattern"))
            val limit = (args.optInt("limit") ?: 200).coerceIn(1, 2000)
            val all = runCatching { fs.files(root, 24, 50_000) }.getOrElse { return ToolResult.error(deniedHint(it, fs)) }
            val hits = all.filter(match)
            if (hits.isEmpty()) return ToolResult.ok("No files under $root match ${args.string("pattern")}.")
            val sb = StringBuilder("${hits.size} match${if (hits.size == 1) "" else "es"} under $root:\n")
            hits.take(limit).forEach { sb.append(it).append('\n') }
            if (hits.size > limit) sb.append("[${hits.size - limit} more; narrow the pattern.]")
            if (all.size >= 50_000) sb.append("\n[Stopped after scanning 50000 files.]")
            return ToolResult.ok(sb.toString().trimEnd())
        }
    }

    private class Grep(private val s: FileScopes) : BaseTool(
        "file_grep",
        "Search file contents for literal text (not a regex). Returns path:line: text.",
        schema {
            string("text", "Text to look for", required = true)
            string("root", "File or folder to search (default: the agent workspace)")
            string("glob", "Only files whose name matches this glob, e.g. '*.md'")
            boolean("ignore_case", "Case-insensitive")
            integer("limit", "Maximum matching lines (default 100)")
            scope()
        },
        Risk.Read,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val fs = s.fs(args)
            val root = s.path(args, fs, "root", default = if (fs is LocalFs) s.workspace.path else "/")
            val text = args.string("text")
            if (text.isEmpty()) return ToolResult.error("text is empty")
            val limit = (args.optInt("limit") ?: 100).coerceIn(1, 1000)
            val glob = args.optString("glob")?.takeIf { it.isNotBlank() }?.let { Glob.matcher(it) }
            val hits = runCatching { fs.grep(root, text, args.optBoolean("ignore_case") == true, limit * if (glob != null) 5 else 1) }
                .getOrElse { return ToolResult.error(deniedHint(it, fs)) }
                .filter { glob == null || glob(it.path) }
                .take(limit)
            if (hits.isEmpty()) return ToolResult.ok("No matches for \"$text\" under $root.")
            val sb = StringBuilder()
            hits.forEach { sb.append(it.path).append(':').append(it.line).append(": ").append(it.text.trim()).append('\n') }
            if (hits.size >= limit) sb.append("[Limit of $limit lines reached.]")
            return ToolResult.ok(sb.toString().trimEnd())
        }
    }

    private fun date(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    fun mime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heif"
        "pdf" -> "application/pdf"
        "txt", "log", "md" -> "text/plain"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> null
    }
}
