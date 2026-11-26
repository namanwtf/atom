package atom.app.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApprovalTextTest {
    @Test fun aShortSingleCommandCanBeAllowedFromTheNotification() {
        assertFalse(ApprovalText.needsFullView("shell: ls -la /sdcard/Download", taskGrantable = false))
    }

    /** An innocent first line, blank lines, then something else: never allowed blind from the shade. */
    @Test fun hiddenLinesMustBeReadInTheApp() {
        assertTrue(ApprovalText.needsFullView("shell: ls\n\n\n\nrm -rf /sdcard/DCIM", taskGrantable = false))
    }

    @Test fun longRequestsMustBeReadInTheApp() {
        assertTrue(ApprovalText.needsFullView("shell: " + "a".repeat(ApprovalText.MAX_CHARS), taskGrantable = false))
    }

    @Test fun requestsThatCanGrantTheWholeTaskAreAnsweredInTheApp() {
        assertTrue(ApprovalText.needsFullView("shell: ls", taskGrantable = true))
    }

    /** Unicode separators render as line breaks too, so they can hide a second command just as well. */
    @Test fun unicodeLineBreaksCount() {
        for (sep in listOf("\u2028", "\u2029", "\u0085", "\r")) {
            assertTrue(ApprovalText.needsFullView("shell: ls${sep}${sep}${sep}rm -rf /sdcard/DCIM", taskGrantable = false), "U+%04X".format(sep[0].code))
        }
        assertEquals(1, ApprovalText.lineCount("ls -la"))
        assertEquals(2, ApprovalText.lineCount("a\r\nb"))
    }

    @Test fun theHeadlineShowsTheCommandFirst() {
        assertEquals("ls -la /data — Run in a root shell", ApprovalText.headline("Run in a root shell:\nls -la /data"))
        assertEquals("send_sms(to=+1555)", ApprovalText.headline("send_sms(to=+1555)"))
        val long = ApprovalText.headline("Run in the phone's shell:\n" + "x".repeat(500))
        assertTrue(long.length <= ApprovalText.HEADLINE_CHARS && long.endsWith("…") && long.startsWith("xxx"))
    }
}
