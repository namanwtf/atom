package atom.device.screen

import android.content.Context
import android.graphics.BitmapFactory
import atom.device.ShellExecutor
import atom.device.ShellTier
import atom.device.control.shQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root/Shizuku fallbacks for when the accessibility service is not connected or its screenshot fails: a system UI
 * dump (uiautomator) rendered exactly like read_screen, taps by `input`, and `screencap`. Files go through Atom's
 * own external cache directory and are deleted straight after reading.
 */
internal object ShellUi {
    @Volatile
    private var boxes: Map<String, Box> = emptyMap()

    /** Ids of the latest dump whose element is clickable (or long-clickable). */
    @Volatile
    private var actionable: Set<String> = emptySet()

    /** The screen size of the latest dump, so tap areas are clipped to it. */
    @Volatile
    var lastScreen: Box? = null
        private set

    private fun cacheDir(context: Context): File? = context.externalCacheDir?.also { it.mkdirs() }

    fun usable(sh: ShellExecutor?): Boolean = sh != null && sh.tier != ShellTier.App

    suspend fun read(context: Context, sh: ShellExecutor, maxChars: Int): String {
        val dir = cacheDir(context) ?: throw UiError("No external cache directory for the UI dump.")
        val file = File(dir, "atom_ui_${System.nanoTime()}.xml")
        try {
            val r = sh.run("uiautomator dump --compressed ${shQuote(file.absolutePath)}", 20_000)
            val xml = withContext(Dispatchers.IO) { if (file.exists()) file.readText() else "" }
            if (xml.isBlank()) throw UiError("The system UI dump failed: ${(r.stderr.ifBlank { r.stdout }).take(200)}")
            val parsed = UiDump.parse(xml)
            val screen = parsed.screen
            lastScreen = screen.takeUnless { it.isEmpty }
            val result = ScreenRenderer.render(parsed.roots, screen, maxChars)
            boxes = result.ids.mapValues { (_, handle) -> parsed.bounds[handle] ?: Box(0, 0, 0, 0) }
            val clickable = HashSet<Int>()
            fun walk(n: UiNode) {
                if (n.clickable || n.longClickable) clickable += n.handle
                n.children.forEach(::walk)
            }
            parsed.roots.forEach(::walk)
            actionable = result.ids.filterValues { it in clickable }.keys
            return buildString {
                append("app: ").append(parsed.packageName ?: "unknown")
                append("  screen: ").append(screen.width).append('x').append(screen.height)
                append("  (system UI dump via ").append(if (sh.tier == ShellTier.Root) "root" else "Shizuku")
                append("; accessibility is off, so ids are tapped by coordinates)")
                append('\n').append(result.text)
            }
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
            sh.run("rm -f ${shQuote(file.absolutePath)}", 5_000)
        }
    }

    fun boxFor(id: String): Box? {
        val key = id.trim().lowercase().removePrefix("#").let { if (it.startsWith("n")) it else "n$it" }
        return boxes[key]
    }

    /** Elements of the latest dump, for tap by area. */
    fun candidates(): List<TapArea.Candidate> = boxes.map { (id, box) -> TapArea.Candidate(id, box, clickable = id in actionable) }

    suspend fun tap(sh: ShellExecutor, x: Int, y: Int, long: Boolean): Boolean {
        val cmd = if (long) "input swipe $x $y $x $y 700" else "input tap $x $y"
        return sh.run(cmd, 10_000).ok
    }

    suspend fun swipe(sh: ShellExecutor, x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean =
        sh.run("input swipe $x1 $y1 $x2 $y2 $ms", 10_000).ok

    suspend fun screenshot(context: Context, sh: ShellExecutor, fullResolution: Boolean = false): Screenshots.Shot {
        val dir = cacheDir(context) ?: throw UiError("No external cache directory for the screenshot.")
        val file = File(dir, "atom_shot_${System.nanoTime()}.png")
        try {
            val r = sh.run("screencap -p ${shQuote(file.absolutePath)}", 20_000)
            val bmp = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
                ?: throw UiError("screencap failed: ${(r.stderr.ifBlank { r.stdout }).take(200)}")
            val shot = withContext(Dispatchers.Default) { Screenshots.encodeBitmap(bmp, fullResolution) }
            return Screenshots.Shot(shot.image, shot.note.replace("attached", "attached (taken by ${if (sh.tier == ShellTier.Root) "root" else "Shizuku"})"))
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
            sh.run("rm -f ${shQuote(file.absolutePath)}", 5_000)
        }
    }
}

/** Parses uiautomator's XML dump into [UiNode]s without an XML library, so it is unit-testable. */
internal object UiDump {
    class Parsed(val roots: List<UiNode>, val bounds: Map<Int, Box>, val screen: Box, val packageName: String?)

    private val tag = Regex("<(/?)node\\b([^>]*?)(/?)>")
    private val attr = Regex("([\\w:-]+)=\"([^\"]*)\"")
    private val boundsRe = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]")

    private class Builder(val handle: Int, val attrs: Map<String, String>) {
        val children = mutableListOf<UiNode>()
    }

    fun parse(xml: String): Parsed {
        val stack = ArrayDeque<Builder>()
        val roots = mutableListOf<UiNode>()
        val bounds = HashMap<Int, Box>()
        var next = 0
        var pkg: String? = null
        var maxRight = 0
        var maxBottom = 0
        fun finish(b: Builder) {
            val node = toNode(b)
            bounds[b.handle] = node.bounds
            maxRight = maxOf(maxRight, node.bounds.right)
            maxBottom = maxOf(maxBottom, node.bounds.bottom)
            if (stack.isEmpty()) roots += node else stack.last().children += node
        }
        for (m in tag.findAll(xml)) {
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.groupValues[3] == "/"
            if (closing) {
                stack.removeLastOrNull()?.let(::finish)
                continue
            }
            val attrs = attr.findAll(m.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            if (pkg == null) attrs["package"]?.takeIf { it.isNotBlank() }?.let { pkg = it }
            val b = Builder(next++, attrs)
            if (selfClosing) finish(b) else stack.addLast(b)
        }
        while (stack.isNotEmpty()) finish(stack.removeLast())
        return Parsed(roots, bounds, Box(0, 0, maxRight, maxBottom), pkg)
    }

    private fun toNode(b: Builder): UiNode {
        val a = b.attrs
        fun flag(k: String) = a[k] == "true"
        val box = a["bounds"]?.let { boundsRe.find(it) }?.groupValues?.let {
            Box(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt())
        } ?: Box(0, 0, 0, 0)
        val password = flag("password")
        return UiNode(
            handle = b.handle,
            bounds = box,
            className = a["class"],
            text = if (password) a["text"]?.let { if (it.isEmpty()) null else "•" } else a["text"]?.takeIf { it.isNotEmpty() },
            desc = a["content-desc"]?.takeIf { it.isNotEmpty() },
            hint = a["hint"]?.takeIf { it.isNotEmpty() },
            viewId = a["resource-id"]?.takeIf { it.isNotEmpty() },
            visible = a["visible-to-user"]?.let { it == "true" } ?: true,
            clickable = flag("clickable"),
            longClickable = flag("long-clickable"),
            editable = a["class"]?.endsWith("EditText") == true,
            scrollable = flag("scrollable"),
            checkable = flag("checkable"),
            checked = flag("checked"),
            selected = flag("selected"),
            focused = flag("focused"),
            enabled = a["enabled"]?.let { it == "true" } ?: true,
            password = password,
            children = b.children.toList(),
        )
    }

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "").replace("&amp;", "&")
}
