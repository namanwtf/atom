package atom.web.sites

import android.content.Context
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.web.BaseTool
import atom.web.Web
import atom.web.files.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Names of the website tools, in the order [SiteTools.create] returns them. */
object SiteToolNames {
    const val CREATE = "site_create"
    const val WRITE = "site_write"
    const val READ = "site_read"
    const val LIST = "site_list"
    const val DELETE_FILE = "site_delete_file"
    const val PREVIEW = "site_preview"

    val ALL: List<String> = listOf(CREATE, WRITE, READ, LIST, DELETE_FILE, PREVIEW)

    /** Sent once, with the first load of the site tools. */
    const val NOTE =
        "Websites: when the user asks you to make a website, page, web app or game, build a small static site with the " +
            "site_* tools, not file_write or the terminal: site_create, then site_write index.html (plain HTML, CSS and " +
            "JavaScript, inline or in style.css / app.js; no build step; keep the user's data in localStorage), then " +
            "site_preview to show it. For a change, site_read the file and site_write it whole; the open preview reloads by " +
            "itself. To check your work, browser_open the preview URL: the result notes the page's console errors."
}

/** What the website tools need from the app; a fake in unit tests. */
internal interface SiteBackend {
    val store: SiteStore

    fun conversationOf(taskId: String): String

    fun current(conversation: String): String?

    fun setCurrent(conversation: String, id: String)

    /** Files of [id] changed. */
    fun changed(id: String)

    /** Starts the server, opens the preview screen when the app is in front. Returns the URL and whether it opened. */
    suspend fun preview(id: String): Pair<String, Boolean>

    /** True when the preview of [id] is running (it then reloads on its own). */
    fun previewing(id: String): Boolean
}

internal class AndroidSiteBackend(private val context: Context) : SiteBackend {
    override val store: SiteStore get() = Sites.store(context)

    override fun conversationOf(taskId: String): String = Web.host.conversationOf(taskId) ?: taskId

    override fun current(conversation: String): String? = Sites.current(context, conversation)

    override fun setCurrent(conversation: String, id: String) = Sites.setCurrent(context, conversation, id)

    override fun changed(id: String) = Sites.changed(id)

    override suspend fun preview(id: String): Pair<String, Boolean> {
        val server = withContext(Dispatchers.IO) { Sites.serve(context, id) }
        server.touch()
        val opened = id in Sites.visible.value || Web.host.openScreen(SiteCards.previewRoute(id))
        return server.url to opened
    }

    override fun previewing(id: String): Boolean = Sites.running(id) != null
}

internal object SiteTools {
    fun create(context: Context): List<Tool> = create(AndroidSiteBackend(context.applicationContext))

    fun create(b: SiteBackend): List<Tool> = listOf(Create(b), Write(b), Read(b), ListFiles(b), DeleteFile(b), Preview(b))

    private const val MAX_READ_CHARS = 60_000

    /** The site a call is about: the one it names, else the conversation's current one, else the only one. */
    fun siteFor(args: JsonObject, ctx: ToolContext, b: SiteBackend): String {
        val conversation = b.conversationOf(ctx.taskId)
        val named = (args.optString("site") ?: args.optString("name"))?.trim()?.takeIf { it.isNotEmpty() }
        if (named != null) {
            val id = if (SitePaths.isId(named)) named else SitePaths.idOf(named)
            if (id == null || !b.store.exists(id)) {
                throw ToolArgumentException("There is no site \"$named\". ${sitesLine(b)} Create one with site_create.")
            }
            b.setCurrent(conversation, id)
            return id
        }
        b.current(conversation)?.let { return it }
        val all = b.store.all()
        return when (all.size) {
            0 -> throw ToolArgumentException("There is no site yet. Call site_create with a name first.")
            1 -> all[0].id.also { b.setCurrent(conversation, it) }
            else -> throw ToolArgumentException("Which site? Pass site=<id>. ${sitesLine(b)}")
        }
    }

    private fun sitesLine(b: SiteBackend): String {
        val all = b.store.all()
        return if (all.isEmpty()) "There are no sites yet." else "Sites: ${all.joinToString { it.id }}."
    }

    private fun filesLine(b: SiteBackend, id: String): String =
        b.store.files(id).joinToString { it.path }.ifEmpty { "none" }

    private class Create(private val b: SiteBackend) : BaseTool(
        SiteToolNames.CREATE,
        "Start a website, web app or game the user asked for (\"make me a website / page / app / game\"). Creates the site " +
            "with a placeholder index.html and returns its id; an existing site with that name is reopened. Next: site_write " +
            "the files, then site_preview to show it.",
        schema { string("name", "Short name, e.g. \"Water tracker\"", required = true) },
        Risk.Act,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val name = args.string("name")
            val (id, created) = withContext(Dispatchers.IO) { b.store.create(name) }
            b.setCurrent(b.conversationOf(ctx.taskId), id)
            b.changed(id)
            return ToolResult.ok(
                if (created) "Created site $id with a placeholder index.html. Write its files with site_write, then show it with site_preview."
                else "Site $id already exists; it is now the current site. Files: ${filesLine(b, id)}.",
            )
        }
    }

    private class Write(private val b: SiteBackend) : BaseTool(
        SiteToolNames.WRITE,
        "Create or replace one file of the site (index.html, style.css, app.js, ...) with its complete content. Plain HTML, " +
            "CSS and JavaScript, no build step. An open preview reloads by itself.",
        schema {
            string("path", "File in the site, e.g. index.html or css/style.css", required = true)
            string("content", "The whole file content", required = true)
            string("site", "Site id (default: the current site)")
        },
        Risk.Act,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = SitePaths.clean(args.optString("path"))
            val content = args.string("content")
            val id = siteFor(args, ctx, b)
            val size = withContext(Dispatchers.IO) { b.store.write(id, path, content) }
            b.changed(id)
            val next = if (b.previewing(id)) "The preview reloads." else "Show it with site_preview."
            return ToolResult.ok("Wrote $path (${Sizes.human(size)}) in site $id. $next")
        }
    }

    private class Read(private val b: SiteBackend) : BaseTool(
        SiteToolNames.READ,
        "Read one file of the site, e.g. before changing it.",
        schema {
            string("path", "File in the site, e.g. index.html", required = true)
            string("site", "Site id (default: the current site)")
        },
        Risk.Read,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = SitePaths.clean(args.optString("path"))
            val id = siteFor(args, ctx, b)
            val text = withContext(Dispatchers.IO) { b.store.read(id, path) }
            val shown = if (text.length > MAX_READ_CHARS) text.take(MAX_READ_CHARS) + "\n[Cut at $MAX_READ_CHARS of ${text.length} characters.]" else text
            return ToolResult.ok("$id/$path\n$shown")
        }
    }

    private class ListFiles(private val b: SiteBackend) : BaseTool(
        SiteToolNames.LIST,
        "List the files of a site, or every site when none is chosen yet.",
        schema { string("site", "Site id (default: the current site)") },
        Risk.Read,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
            val id = runCatching { siteFor(args, ctx, b) }.getOrElse { e ->
                val all = b.store.all()
                if (all.isEmpty() || args.optString("site") != null) throw e
                return@withContext ToolResult.ok("Sites:\n" + all.joinToString("\n") { "- ${it.id}: ${it.title} (${it.files} files)" })
            }
            val files = b.store.files(id)
            ToolResult.ok(
                "Site $id (${files.size} files)\n" + files.joinToString("\n") { "${it.path}  ${Sizes.human(it.bytes)}" }.ifEmpty { "(empty)" },
            )
        }
    }

    private class DeleteFile(private val b: SiteBackend) : BaseTool(
        SiteToolNames.DELETE_FILE,
        "Delete one file from the site.",
        schema {
            string("path", "File in the site", required = true)
            string("site", "Site id (default: the current site)")
        },
        Risk.Act,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = SitePaths.clean(args.optString("path"))
            val id = siteFor(args, ctx, b)
            withContext(Dispatchers.IO) { b.store.deleteFile(id, path) }
            b.changed(id)
            return ToolResult.ok("Deleted $path from site $id. Files: ${filesLine(b, id)}.")
        }
    }

    private class Preview(private val b: SiteBackend) : BaseTool(
        SiteToolNames.PREVIEW,
        "Show the site to the user in a live preview on the phone, and get its local URL. The preview reloads when files " +
            "change. To check the page yourself, open the URL with browser_open.",
        schema { string("name", "Site id or name (default: the current site)") },
        Risk.Read,
    ) {
        override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult {
            val id = siteFor(args, ctx, b)
            val (url, opened) = b.preview(id)
            val title = withContext(Dispatchers.IO) { b.store.title(id) } ?: id
            return ToolResult.ok(
                buildString {
                    append(if (opened) "The live preview of \"$title\" is open on the phone." else "The preview of \"$title\" is ready; the user can open it from the card in the chat.")
                    append("\nURL: ").append(url).append(" (reachable only on this phone; it stops about a minute after the preview closes, call site_preview again then)")
                    append("\nFiles: ").append(filesLine(b, id))
                    append("\nTo check it yourself, browser_open the URL.")
                    append('\n').append(SiteCards.MARKER).append(id)
                },
            )
        }
    }
}
