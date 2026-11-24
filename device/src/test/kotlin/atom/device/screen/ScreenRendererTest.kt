package atom.device.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenRendererTest {
    private val screen = Box(0, 0, 1080, 2400)
    private var next = 0
    private fun node(
        bounds: Box = Box(0, 0, 1080, 2400),
        cls: String? = "android.widget.FrameLayout",
        text: String? = null,
        desc: String? = null,
        children: List<UiNode> = emptyList(),
        tweak: UiNode.() -> UiNode = { this },
    ) = UiNode(handle = next++, bounds = bounds, className = cls, text = text, desc = desc, children = children).tweak()

    @Test
    fun wrappersCollapseAndControlsRender() {
        val send = node(Box(900, 2200, 1060, 2300), "android.widget.Button", text = "Send") { copy(clickable = true) }
        val root = node(children = listOf(node(cls = "android.widget.LinearLayout", children = listOf(send))))
        val r = ScreenRenderer.render(listOf(root), screen)
        assertEquals("n1 Button \"Send\" (980,2250) click", r.text)
        assertEquals(mapOf("n1" to send.handle), r.ids)
    }

    @Test
    fun clickableRowAbsorbsPlainTextButKeepsNestedControls() {
        val title = node(Box(40, 300, 600, 350), "android.widget.TextView", text = "Wi-Fi")
        val sub = node(Box(40, 350, 600, 400), "android.widget.TextView", text = "Connected")
        val toggle = node(Box(900, 320, 1040, 380), "android.widget.Switch") { copy(checkable = true, checked = true, clickable = true) }
        val row = node(Box(0, 280, 1080, 420), "android.widget.LinearLayout", children = listOf(title, sub, toggle)) { copy(clickable = true) }
        val r = ScreenRenderer.render(listOf(node(children = listOf(row))), screen)
        val lines = r.text.lines()
        assertEquals(2, lines.size, r.text)
        assertEquals("n1 \"Wi-Fi · Connected\" (540,350) click", lines[0])
        assertEquals("  n2 Switch (970,350) click checked", lines[1])
    }

    @Test
    fun invisibleAndOffscreenNodesAreDropped() {
        val hidden = node(Box(0, 0, 100, 100), text = "hidden") { copy(visible = false) }
        val below = node(Box(0, 3000, 100, 3100), text = "below the fold")
        val empty = node(Box(10, 10, 10, 10), text = "zero size")
        val shown = node(Box(0, 0, 200, 100), text = "Hello")
        val r = ScreenRenderer.render(listOf(node(children = listOf(hidden, below, empty, shown))), screen)
        assertEquals("n1 \"Hello\" (100,50)", r.text)
    }

    @Test
    fun editableShowsHintAndMasksPasswords() {
        val search = node(Box(0, 100, 1080, 200), "android.widget.EditText", text = "Search") {
            copy(editable = true, clickable = true, showingHint = true, focused = true)
        }
        val pin = node(Box(0, 300, 1080, 400), "android.widget.EditText", text = "1234") { copy(editable = true, password = true) }
        val r = ScreenRenderer.render(listOf(node(children = listOf(search, pin))), screen)
        val lines = r.text.lines()
        assertEquals("n1 Input hint=\"Search\" (540,150) click edit focused", lines[0])
        assertEquals("n2 Input \"•••\" (540,350) edit", lines[1])
    }

    @Test
    fun unlabeledIconFallsBackToViewIdAndDescDedupes() {
        val icon = node(Box(0, 0, 100, 100), "android.widget.ImageButton") { copy(clickable = true, viewId = "com.app:id/attach") }
        val same = node(Box(100, 0, 200, 100), "android.widget.TextView", text = "Menu", desc = "Menu")
        val r = ScreenRenderer.render(listOf(node(children = listOf(icon, same))), screen)
        assertEquals(listOf("n1 IconButton #attach (50,50) click", "n2 \"Menu\" (150,50)"), r.text.lines())
    }

    @Test
    fun nestedMeaningfulNodesIndent() {
        val item = node(Box(0, 0, 500, 100), text = "Item") { copy(clickable = true) }
        val list = node(Box(0, 0, 1080, 2000), "androidx.recyclerview.widget.RecyclerView", children = listOf(item)) { copy(scrollable = true) }
        val r = ScreenRenderer.render(listOf(node(children = listOf(list))), screen)
        assertEquals(listOf("n1 (540,1000) scroll", "  n2 \"Item\" (250,50) click"), r.text.lines())
    }

    @Test
    fun boundsCenterIsClampedToTheVisiblePart() {
        val tall = node(Box(0, 2000, 1080, 4000), text = "Footer")
        val r = ScreenRenderer.render(listOf(tall), screen)
        assertEquals("n1 \"Footer\" (540,2200)", r.text)
    }

    @Test
    fun truncatesWithNoteAndOnlyMapsShownIds() {
        val items = (0 until 200).map { i -> node(Box(0, i * 10, 1080, i * 10 + 10), text = "Row number $i") { copy(clickable = true) } }
        val r = ScreenRenderer.render(listOf(node(children = items)), screen, maxChars = 500)
        assertTrue(r.truncated)
        assertTrue(r.text.length <= 600, "length ${r.text.length}")
        assertTrue(r.text.endsWith("Scroll, or read a narrower area."), r.text)
        assertEquals(r.shown, r.ids.size)
        assertEquals(200, r.total)
        assertTrue(r.text.contains("${200 - r.shown} more elements"))
        assertEquals(items[0].handle, r.ids["n1"])
    }

    @Test
    fun longTextIsFlattenedAndCut() {
        val long = node(Box(0, 0, 100, 100), text = "line one\nline two " + "x".repeat(300))
        val r = ScreenRenderer.render(listOf(long), screen)
        assertFalse(r.text.contains('\n'))
        assertTrue(r.text.contains("\"line one line two xxx"))
        assertTrue(r.text.contains("…\""))
    }

    @Test
    fun emptyScreenSaysSo() {
        val r = ScreenRenderer.render(listOf(node()), screen)
        assertTrue(r.text.startsWith("(no readable elements"))
        assertTrue(r.ids.isEmpty())
    }
}
