package atom.device.screen

/** Screen rectangle in pixels. */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2
    val isEmpty get() = width <= 0 || height <= 0

    fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun clampTo(o: Box) = Box(maxOf(left, o.left), maxOf(top, o.top), minOf(right, o.right), minOf(bottom, o.bottom))
}

/**
 * Plain snapshot of one accessibility node, decoupled from the Android class so compaction is pure and
 * testable. [handle] indexes the live node kept by the reader.
 */
internal data class UiNode(
    val handle: Int,
    val bounds: Box,
    val className: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val visible: Boolean = true,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
    val showingHint: Boolean = false,
    val children: List<UiNode> = emptyList(),
) {
    val actionable get() = clickable || longClickable || editable || scrollable || checkable
}

internal class RenderResult(
    val text: String,
    /** Short id ("n12") to node handle, for the lines that made it into [text]. */
    val ids: Map<String, Int>,
    val shown: Int,
    val total: Int,
) {
    val truncated get() = shown < total
}

/**
 * Turns an accessibility tree into a compact, indented outline an LLM can act on:
 *
 *     n3 Button "Send" (980,2210) click
 *
 * Invisible and off-screen nodes are dropped, layout wrappers with nothing to say are collapsed (their
 * children move up a level), and an actionable row with no label of its own absorbs the text of its
 * plain descendants so a list item renders as one line instead of four.
 */
internal object ScreenRenderer {
    private const val LABEL_MAX = 100
    private const val ABSORB_MAX = 160
    private const val INDENT_MAX = 6

    private class Line(val body: String, val depth: Int, val handle: Int)

    /** True when a visible password field is anywhere in [node]'s tree. */
    fun showsPassword(node: UiNode): Boolean =
        node.visible && (node.password || node.children.any { showsPassword(it) })

    fun render(roots: List<UiNode>, screen: Box, maxChars: Int = 6_000): RenderResult {
        val lines = mutableListOf<Line>()
        val absorbed = HashSet<Int>()
        for (root in roots) walk(root, 0, screen, lines, absorbed)

        val out = StringBuilder()
        val ids = LinkedHashMap<String, Int>()
        var shown = 0
        for (line in lines) {
            val id = "n${shown + 1}"
            val rendered = "  ".repeat(minOf(line.depth, INDENT_MAX)) + id + " " + line.body
            if (out.length + rendered.length + 1 > maxChars) break
            out.append(rendered).append('\n')
            ids[id] = line.handle
            shown++
        }
        if (shown < lines.size) {
            out.append("… truncated: ${lines.size - shown} more elements not shown. Scroll, or read a narrower area.\n")
        }
        if (lines.isEmpty()) out.append("(no readable elements; the app may draw its own UI. Try screenshot=true.)\n")
        return RenderResult(out.toString().trimEnd(), ids, shown, lines.size)
    }

    private fun walk(node: UiNode, depth: Int, screen: Box, lines: MutableList<Line>, absorbed: MutableSet<Int>) {
        if (!node.visible || node.bounds.isEmpty || !node.bounds.intersects(screen)) return
        val own = ownLabel(node)
        val emits = (node.actionable || own.isNotEmpty()) && node.handle !in absorbed
        if (!emits) {
            // Layout wrapper: nothing to say, so its children take its place.
            for (c in node.children) walk(c, depth, screen, lines, absorbed)
            return
        }

        var label = own
        if (label.isEmpty() && !node.editable) {
            val parts = mutableListOf<String>()
            absorbLabels(node, screen, parts, absorbed)
            label = parts.joinToString(" · ").let { if (it.length > ABSORB_MAX) it.take(ABSORB_MAX - 1) + "…" else it }
            if (label.isNotEmpty()) label = quote(label)
        }
        lines += Line(describe(node, label, screen), depth, node.handle)
        for (c in node.children) walk(c, depth + 1, screen, lines, absorbed)
    }

    /** Collects text from plain (non-actionable) descendants, stopping at nested controls. */
    private fun absorbLabels(node: UiNode, screen: Box, parts: MutableList<String>, absorbed: MutableSet<Int>) {
        for (c in node.children) {
            if (!c.visible || c.bounds.isEmpty || !c.bounds.intersects(screen) || c.actionable) continue
            val t = plainText(c)
            if (t.isNotEmpty() && parts.size < 4) parts += t
            absorbed += c.handle
            absorbLabels(c, screen, parts, absorbed)
        }
    }

    private fun plainText(n: UiNode): String = clean(n.text?.takeUnless { n.showingHint }).ifEmpty { clean(n.desc) }

    /** Quoted text, desc and hint of the node itself, or "" if it has none. */
    private fun ownLabel(n: UiNode): String {
        val text = clean(n.text?.takeUnless { n.showingHint })
        val desc = clean(n.desc).takeIf { it != text }.orEmpty()
        val hint = clean(n.hint ?: n.text?.takeIf { n.showingHint }).takeIf { it != text && it != desc }.orEmpty()
        return buildList {
            if (text.isNotEmpty()) add(if (n.password) "\"•••\"" else quote(text))
            if (desc.isNotEmpty()) add("desc=" + quote(desc))
            if (hint.isNotEmpty()) add("hint=" + quote(hint))
        }.joinToString(" ")
    }

    private fun describe(n: UiNode, label: String, screen: Box): String {
        val parts = mutableListOf<String>()
        typeTag(n.className)?.let(parts::add)
        if (label.isNotEmpty()) {
            parts += label
        } else {
            n.viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }?.let { parts += "#$it" }
        }
        val visible = n.bounds.clampTo(screen)
        parts += "(${visible.centerX},${visible.centerY})"
        if (n.clickable) parts += "click"
        if (n.longClickable) parts += "long"
        if (n.editable) parts += "edit"
        if (n.scrollable) parts += "scroll"
        if (n.checkable) parts += if (n.checked) "checked" else "unchecked"
        if (n.selected) parts += "selected"
        if (n.focused) parts += "focused"
        if (!n.enabled) parts += "disabled"
        return parts.joinToString(" ")
    }

    private fun typeTag(className: String?): String? {
        val simple = className?.substringAfterLast('.') ?: return null
        return when {
            simple.endsWith("EditText") || simple.endsWith("AutoCompleteTextView") -> "Input"
            simple.endsWith("ImageButton") || simple == "FloatingActionButton" -> "IconButton"
            simple.endsWith("CheckBox") -> "Checkbox"
            simple.endsWith("RadioButton") -> "Radio"
            simple.endsWith("Switch") || simple.endsWith("SwitchCompat") || simple.endsWith("SwitchMaterial") -> "Switch"
            simple.endsWith("ToggleButton") -> "Toggle"
            simple.endsWith("Button") -> "Button"
            simple.endsWith("ImageView") -> "Image"
            simple.endsWith("SeekBar") || simple == "Slider" -> "Slider"
            simple.endsWith("Spinner") -> "Dropdown"
            simple.endsWith("ProgressBar") -> "Progress"
            simple == "WebView" -> "Web"
            else -> null
        }
    }

    private fun clean(s: CharSequence?): String {
        if (s.isNullOrBlank()) return ""
        val flat = s.toString().replace(Regex("\\s+"), " ").trim()
        return if (flat.length > LABEL_MAX) flat.take(LABEL_MAX - 1) + "…" else flat
    }

    private fun quote(s: String) = "\"" + s.replace('"', '\'') + "\""
}
