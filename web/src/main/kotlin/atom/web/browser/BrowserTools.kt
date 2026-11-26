package atom.web.browser

import android.content.Context
import atom.core.llm.ContentPart
import atom.core.tool.Risk
import atom.core.tool.SchemaBuilder
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.web.BaseTool
import atom.web.Web
import atom.web.image.ImageCodec
import atom.web.search.JsonPath
import atom.web.search.Paging
import atom.web.search.Readable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Helpers for the chat card: every browser step's result carries a "Preview: <path>" line. */
object BrowserCards {
    const val PREVIEW = "Preview: "

    fun previewPath(toolText: String): String? =
        toolText.lineSequence().lastOrNull { it.startsWith(PREVIEW) }?.removePrefix(PREVIEW)?.trim()

    /** Route of the take-over screen for [conversationId]. */
    fun takeOverRoute(conversationId: String) = "web/browser/session/$conversationId"
}

/** Names of the built-in agent browser's tools, in the order [BrowserTools.create] returns them. */
object BrowserToolNames {
    const val OPEN = "browser_open"
    const val EXTRACT = "browser_extract"
    const val FIND = "browser_find"
    const val CLICK = "browser_click"
    const val TYPE = "browser_type"
    const val SUBMIT = "browser_submit"
    const val SCROLL = "browser_scroll"
    const val SCREENSHOT = "browser_screenshot"
    const val NAVIGATE = "browser_navigate"
    const val WAIT = "browser_wait"
    const val CLOSE = "browser_close"

    val ALL: List<String> = listOf(OPEN, EXTRACT, FIND, CLICK, TYPE, SUBMIT, SCROLL, SCREENSHOT, NAVIGATE, WAIT, CLOSE)
}

internal object BrowserTools {
    fun create(context: Context, gate: () -> String?): List<Tool> {
        val c = context.applicationContext
        return listOf(
            Open(c, gate), Extract(c, gate), Find(c, gate), Click(c, gate), Type(c, gate), Submit(c, gate),
            Scroll(c, gate), Screenshot(c, gate), Navigate(c, gate), Wait(c, gate), Close(c, gate),
        )
    }

    private fun key(ctx: ToolContext) = Web.host.conversationOf(ctx.taskId) ?: ctx.taskId

    private fun SchemaBuilder.target() {
        integer("id", "Element id from browser_find")
        string("selector", "CSS selector (alternative to id)")
        string("label", "Visible text or label of the element (alternative to id)")
    }

    private fun targetArgs(args: JsonObject) = buildJsonObject {
        args.optInt("id")?.let { put("id", it) }
        args.optString("selector")?.takeIf { it.isNotBlank() }?.let { put("selector", it) }
        args.optString("label")?.takeIf { it.isNotBlank() }?.let { put("text", it) }
    }

    private abstract class Step(
        protected val context: Context,
        name: String,
        description: String,
        params: JsonObject,
        risk: Risk,
        gate: () -> String?,
        private val creates: Boolean = false,
    ) : BaseTool(name, description, params, risk, gate) {
        final override suspend fun exec(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.Main) {
            val k = key(ctx)
            val s = if (creates) BrowserSessions.obtain(context, k) else BrowserSessions.get(k)?.takeIf { !it.dead }?.also { BrowserSessions.touch(it) }
                ?: return@withContext ToolResult.error("No page is open in this conversation's browser. Call browser_open first.")
            if (s.attached) return@withContext ToolResult.error("The user has taken over the browser. Wait until they hand it back.")
            val r = step(s, args)
            finish(s, r)
        }

        protected abstract suspend fun step(s: BrowserSession, args: JsonObject): ToolResult

        private suspend fun finish(s: BrowserSession, r: ToolResult): ToolResult {
            if (s.dead) return ToolResult.error("The page crashed and the browser was closed. Open it again with browser_open.")
            if (BrowserSessions.get(s.key) !== s) return r
            val sb = StringBuilder(r.text)
            val notes = s.takeNotices()
            if (notes.isNotEmpty()) sb.append("\n\n").append(notes.joinToString("\n") { "Note: $it" })
            val preview = runCatching { s.preview(context.cacheDir) }.getOrNull()
            if (preview != null) sb.append("\n").append(BrowserCards.PREVIEW).append(preview)
            return r.copy(text = sb.toString())
        }
    }

    private suspend fun header(s: BrowserSession): String {
        val p = s.page.value
        val url = s.url()
        return buildString {
            if (p.title.isNotBlank()) append("Title: ").append(p.title).append('\n')
            append("URL: ").append(url ?: p.url)
            s.lastError?.let { append("\nLoad error: ").append(it) }
        }
    }

    private fun obj(json: String?): JsonObject? = json?.let { runCatching { JsonPath.parse(it) as? JsonObject }.getOrNull() }

    private class Open(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.OPEN,
        "Open a URL or search in Atom's built-in agent browser (also called the Atom browser): Atom's own browser for reading " +
            "and acting on web pages, with a live preview in the chat that the user can watch and take over. Use it whenever the " +
            "user asks for the agent browser or to browse, open or look at a website, and for pages that need JavaScript, sign-in, " +
            "clicking or forms. Shares logins with the user's browser. Returns a text summary; then browser_extract / browser_find / " +
            "browser_click / browser_type.",
        schema { string("url", "http(s) URL, or search terms (searched on Google)", required = true) },
        Risk.Read, g, creates = true,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val raw = args.string("url").trim()
            val url = UrlInput.resolve(raw)
            if (!WebViewSecurity.isWeb(url)) throw ToolArgumentException("Only http(s) pages can be opened.")
            s.load(url)
            val html = s.eval(BrowserScripts.HTML).orEmpty()
            // Read the URL here, on the main thread: inside the Default block it would touch the WebView off it.
            val base = s.url()
            val doc = withContext(Dispatchers.Default) { Readable.extract(html, base) }
            val summary = Paging.page(doc.text, 0, 1500)
            return ToolResult.ok(
                header(s) + "\n\n" + summary.text.ifBlank { "(No readable text yet.)" } +
                    (if (summary.hasMore) "\n…\n[browser_extract for the full text; browser_find for buttons, links and fields.]" else ""),
            )
        }
    }

    private class Extract(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.EXTRACT,
        "Get the current page's content: article (main readable text, default), text (all visible text) or links. Paged.",
        schema {
            string("mode", "article, text or links", enum = listOf("article", "text", "links"))
            integer("offset", "Character offset to continue from")
            integer("max_chars", "Characters per page (default 12000)")
        },
        Risk.Read, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val limit = (args.optInt("max_chars") ?: 12_000).coerceIn(1000, 40_000)
            val offset = args.optInt("offset") ?: 0
            val text = when (args.optString("mode") ?: "article") {
                "links" -> {
                    val list = runCatching { JsonPath.parse(s.eval(BrowserScripts.LINKS) ?: "[]") }.getOrNull()
                    JsonPath.items(list ?: return ToolResult.error("Could not read links."), "").joinToString("\n") {
                        "- " + (JsonPath.string(it, "t") ?: "") + " — " + (JsonPath.string(it, "h") ?: "")
                    }
                }
                "text" -> obj(s.eval(BrowserScripts.TEXT))?.let { JsonPath.string(it, "text") }.orEmpty()
                else -> {
                    val html = s.eval(BrowserScripts.HTML).orEmpty()
                    val base = s.url()
                    withContext(Dispatchers.Default) { Readable.extract(html, base).text }
                }
            }
            val p = Paging.page(text, offset, limit)
            return ToolResult.ok(header(s) + "\n\n" + p.text.ifBlank { "(Nothing found.)" } + p.footer())
        }
    }

    private class Find(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.FIND,
        "List visible interactive elements (links, buttons, inputs) with ids for click/type. Filter by text or CSS selector.",
        schema {
            string("query", "Text to match in labels, names, placeholders or links")
            string("selector", "CSS selector to list instead of the default interactive set")
            integer("limit", "Maximum elements (default 60)")
        },
        Risk.Read, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val a = buildJsonObject {
                args.optString("query")?.takeIf { it.isNotBlank() }?.let { put("query", it) }
                args.optString("selector")?.takeIf { it.isNotBlank() }?.let { put("selector", it) }
                put("limit", (args.optInt("limit") ?: 60).coerceIn(1, 200))
            }
            val r = obj(s.eval(BrowserScripts.find(a.toString()))) ?: return ToolResult.error("Could not inspect the page.")
            JsonPath.string(r, "error")?.let { return ToolResult.error(it) }
            val items = JsonPath.items(r, "items")
            if (items.isEmpty()) return ToolResult.ok(header(s) + "\n\nNo matching visible elements. Try browser_scroll or a different query.")
            val sb = StringBuilder(header(s)).append("\n\n")
            for (it in items) {
                val type = JsonPath.string(it, "type").orEmpty()
                sb.append('[').append(JsonPath.string(it, "id")).append("] ").append(JsonPath.string(it, "tag"))
                if (type.isNotBlank() && type != JsonPath.string(it, "tag")) sb.append('(').append(type).append(')')
                sb.append(' ').append('"').append(JsonPath.string(it, "label").orEmpty()).append('"')
                JsonPath.string(it, "value")?.let { v -> sb.append(" value=\"").append(v).append('"') }
                if (JsonPath.string(it, "checked") == "true") sb.append(" checked")
                if (JsonPath.string(it, "disabled") == "true") sb.append(" disabled")
                JsonPath.string(it, "href")?.let { h -> sb.append(" → ").append(h) }
                if (JsonPath.string(it, "inView") == "false") sb.append(" (off-screen)")
                sb.append('\n')
            }
            val matched = JsonPath.string(r, "matched")?.toIntOrNull() ?: items.size
            if (matched > items.size) sb.append("[${matched - items.size} more; narrow with query.]")
            return ToolResult.ok(sb.toString().trimEnd())
        }
    }

    private class Click(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.CLICK,
        "Click an element (by id from browser_find, CSS selector, or visible label). Waits for any page load it causes.",
        schema { target() },
        Risk.Act, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val r = obj(s.eval(BrowserScripts.click(targetArgs(args).toString()))) ?: return ToolResult.error("Click failed.")
            if (JsonPath.string(r, "ok") != "true") return ToolResult.error(JsonPath.string(r, "error") ?: "Click failed.")
            s.settleAfterAction()
            return ToolResult.ok("Clicked ${JsonPath.string(r, "tag")} \"${JsonPath.string(r, "label").orEmpty()}\".\n" + header(s))
        }
    }

    private class Type(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.TYPE,
        "Type into a field (input, textarea, contenteditable), choose a <select> option, or set a checkbox (value true/false).",
        schema {
            target()
            string("value", "Text to type, option to choose, or true/false", required = true)
            boolean("clear", "Replace existing text (default true)")
            boolean("submit", "Press Enter / submit the form afterwards")
        },
        Risk.Act, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val a = JsonObject(
                targetArgs(args) + mapOf(
                    "value" to JsonPrimitive(args.string("value")),
                    "clear" to JsonPrimitive(args.optBoolean("clear") ?: true),
                    "submit" to JsonPrimitive(args.optBoolean("submit") == true),
                ),
            )
            val r = obj(s.eval(BrowserScripts.type(a.toString()))) ?: return ToolResult.error("Typing failed.")
            if (JsonPath.string(r, "ok") != "true") return ToolResult.error(JsonPath.string(r, "error") ?: "Typing failed.")
            if (JsonPath.string(r, "submitted") == "true") s.settleAfterAction() else delay(200)
            val what = if (JsonPath.string(r, "masked") == "true") "the password" else "\"${args.string("value").take(80)}\""
            return ToolResult.ok("Entered $what into \"${JsonPath.string(r, "label").orEmpty()}\"${if (JsonPath.string(r, "submitted") == "true") " and submitted" else ""}.\n" + header(s))
        }
    }

    private class Submit(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.SUBMIT,
        "Submit the form containing an element (or the focused field). Waits for the result page.",
        schema { target() },
        Risk.Act, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val r = obj(s.eval(BrowserScripts.submit(targetArgs(args).toString()))) ?: return ToolResult.error("Submit failed.")
            if (JsonPath.string(r, "ok") != "true") return ToolResult.error(JsonPath.string(r, "error") ?: "Submit failed.")
            s.settleAfterAction()
            return ToolResult.ok("Submitted.\n" + header(s))
        }
    }

    private class Scroll(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.SCROLL,
        "Scroll the page (down/up by screens, top, bottom) or scroll an element into view.",
        schema {
            string("direction", "down (default), up, top or bottom", enum = listOf("down", "up", "top", "bottom"))
            integer("amount", "Screens to scroll (default 1)")
            target()
        },
        Risk.Read, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val a = JsonObject(
                targetArgs(args) + mapOf(
                    "direction" to JsonPrimitive(args.optString("direction") ?: "down"),
                    "amount" to JsonPrimitive((args.optInt("amount") ?: 1).coerceIn(1, 20)),
                ),
            )
            val r = obj(s.eval(BrowserScripts.scroll(a.toString()))) ?: return ToolResult.error("Scroll failed.")
            if (JsonPath.string(r, "ok") != "true") return ToolResult.error(JsonPath.string(r, "error") ?: "Scroll failed.")
            delay(350)
            val y = JsonPath.string(r, "y")
            val h = JsonPath.string(r, "height")
            return ToolResult.ok("Scrolled to $y of $h px${if (JsonPath.string(r, "atBottom") == "true") " (bottom of page)" else ""}.\n" + header(s))
        }
    }

    private class Screenshot(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.SCREENSHOT,
        "Take a screenshot of the browser's visible area, to look at layout, images or charts.",
        schema(),
        Risk.Read, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val bmp = s.captureOnMain(ImageCodec.MAX_SIDE) ?: return ToolResult.error("Nothing to capture yet.")
            val enc = withContext(Dispatchers.Default) { try { ImageCodec.encode(bmp, 80) } finally { bmp.recycle() } }
            return ToolResult(header(s) + "\nScreenshot ${enc.width}×${enc.height}.", images = listOf(ContentPart.Image(enc.mime, enc.base64)), sensitive = true)
        }
    }

    private class Navigate(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.NAVIGATE,
        "Go back, forward, reload or stop loading.",
        schema { string("action", "back, forward, reload or stop", required = true, enum = listOf("back", "forward", "reload", "stop")) },
        Risk.Read, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val action = args.string("action")
            if (action !in setOf("back", "forward", "reload", "stop")) throw ToolArgumentException("action must be back, forward, reload or stop")
            val moved = s.onMain { w ->
                when (action) {
                    "back" -> w.canGoBack().also { if (it) w.goBack() }
                    "forward" -> w.canGoForward().also { if (it) w.goForward() }
                    "reload" -> true.also { w.reload() }
                    else -> true.also { w.stopLoading() }
                }
            }
            if (!moved) return ToolResult.error(if (action == "back") "There is no previous page." else "There is no next page.")
            if (action == "stop") return ToolResult.ok("Stopped.\n" + header(s))
            s.settleAfterAction()
            return ToolResult.ok(header(s))
        }
    }

    private class Wait(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.WAIT,
        "Wait until an element (CSS selector) or text appears, up to timeout_seconds (max 30).",
        schema {
            string("selector", "CSS selector to wait for")
            string("text", "Text to wait for")
            integer("timeout_seconds", "Default 10")
        },
        Risk.Read, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            val a = buildJsonObject {
                args.optString("selector")?.takeIf { it.isNotBlank() }?.let { put("selector", it) }
                args.optString("text")?.takeIf { it.isNotBlank() }?.let { put("text", it) }
            }.toString()
            val deadline = System.currentTimeMillis() + (args.optInt("timeout_seconds") ?: 10).coerceIn(1, 30) * 1000L
            while (true) {
                val r = obj(s.eval(BrowserScripts.present(a)))
                JsonPath.string(r ?: JsonObject(emptyMap()), "error")?.let { return ToolResult.error(it) }
                if (r != null && JsonPath.string(r, "found") == "true") return ToolResult.ok("Found.\n" + header(s))
                if (System.currentTimeMillis() > deadline || s.dead) return ToolResult.error("Timed out waiting.\n" + header(s))
                delay(400)
            }
        }
    }

    private class Close(c: Context, g: () -> String?) : Step(
        c, BrowserToolNames.CLOSE,
        "Close this conversation's browser when you are done with it (saves battery).",
        schema(),
        Risk.Read, g,
    ) {
        override suspend fun step(s: BrowserSession, args: JsonObject): ToolResult {
            BrowserSessions.close(s.key)
            return ToolResult.ok("Browser closed.")
        }
    }
}

/** Turns address-bar input into a URL: URLs as typed, everything else as a Google search. */
object UrlInput {
    private val hostLike = Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/.*)?$")

    fun resolve(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") || t == "about:blank" -> t
            !t.contains(' ') && hostLike.matches(t) -> "https://$t"
            t.startsWith("localhost") -> "http://$t"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
    }
}
