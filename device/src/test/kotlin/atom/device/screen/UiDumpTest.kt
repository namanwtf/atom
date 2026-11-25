package atom.device.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiDumpTest {
    private val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">""" +
        """<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,2400]">""" +
        """<node index="0" text="Sign in &amp; go" resource-id="com.example:id/go" class="android.widget.Button" package="com.example" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[100,2000][980,2140]" />""" +
        """<node index="1" text="hunter2" resource-id="com.example:id/pw" class="android.widget.EditText" package="com.example" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="true" scrollable="false" long-clickable="false" password="true" selected="false" bounds="[100,1700][980,1840]" />""" +
        """</node></hierarchy>"""

    @Test
    fun parsesTreeBoundsAndPackage() {
        val p = UiDump.parse(xml)
        assertEquals("com.example", p.packageName)
        assertEquals(1, p.roots.size)
        assertEquals(2, p.roots[0].children.size)
        assertEquals(Box(0, 0, 1080, 2400), p.screen)
        val button = p.roots[0].children[0]
        assertEquals("Sign in & go", button.text)
        assertTrue(button.clickable)
        assertEquals(Box(100, 2000, 980, 2140), p.bounds[button.handle])
    }

    @Test
    fun passwordTextNeverSurvives() {
        val p = UiDump.parse(xml)
        val field = p.roots[0].children[1]
        assertTrue(field.password)
        assertTrue(field.editable)
        assertFalse(field.text.orEmpty().contains("hunter2"))
        val rendered = ScreenRenderer.render(p.roots, p.screen).text
        assertFalse(rendered.contains("hunter2"))
        assertTrue(rendered.contains("Sign in & go"))
    }
}
