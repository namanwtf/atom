package atom.linux

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxToolsTest {
    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true }
    }

    @Test
    fun envNamesMapToShells() {
        assertEquals(ShellEnv.Linux, LinuxTools.parseEnv(null))
        assertEquals(ShellEnv.Linux, LinuxTools.parseEnv("Linux"))
        assertEquals(ShellEnv.Android, LinuxTools.parseEnv("app"))
        assertEquals(ShellEnv.Root, LinuxTools.parseEnv("su"))
        assertNull(LinuxTools.parseEnv("windows"))
    }

    @Test
    fun capKeepsHeadAndTail() {
        val s = "A".repeat(500) + "MIDDLE" + "Z".repeat(500)
        val out = LinuxTools.cap(s, 200)
        assertTrue(out.startsWith("A".repeat(80)))
        assertTrue(out.endsWith("Z".repeat(120)))
        assertTrue("chars cut" in out)
        assertTrue("MIDDLE" !in out)
        assertEquals("short", LinuxTools.cap("short", 200))
    }

    @Test
    fun blockTextShowsCommandAndState() {
        assertEquals("$ ls\n(no output yet)", LinuxTools.blockText("ls", null))
    }

    @Test
    fun terminalTextSizeIsCompactAndBounded() {
        assertEquals(TerminalText.DEFAULT_SP, LinuxConfig().terminalFontSp)
        assertTrue(TerminalText.DEFAULT_SP <= 13f)
        assertEquals(TerminalText.MIN_SP, TerminalText.clamp(1f))
        assertEquals(TerminalText.MAX_SP, TerminalText.clamp(100f))
        assertEquals(TerminalText.DEFAULT_SP, TerminalText.clamp(Float.NaN))
        // Older config files without the field still load, with the default size.
        val old = LENIENT.decodeFromString(LinuxConfig.serializer(), """{"jobCapMinutes":0}""")
        assertEquals(0, old.jobCapMinutes)
        assertEquals(TerminalText.DEFAULT_SP, old.terminalFontSp)
    }
}
