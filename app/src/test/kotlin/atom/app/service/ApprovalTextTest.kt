package atom.app.service

import kotlin.test.Test
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
}
