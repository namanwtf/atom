package atom.vdisplay

import atom.vdisplay.io.Commands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandsTest {
    @Test fun startOnDisplay() = assertEquals(
        "am start --display 5 --windowingMode 1 -f 0x10000000 -n 'com.shop/.Main'",
        Commands.startComponent(5, "com.shop/.Main"),
    )

    @Test fun viewQuotesUriAndPackage() = assertEquals(
        "am start --display 5 --windowingMode 1 -f 0x10000000 -a android.intent.action.VIEW -d 'https://x.y/?a=1&b='\\''2'\\''' -p 'com.shop'",
        Commands.startView(5, "https://x.y/?a=1&b='2'", "com.shop"),
    )

    @Test fun input() {
        assertEquals("input -d 5 tap 10 20", Commands.tap(5, 10, 20))
        assertEquals("input -d 5 swipe 1 2 3 4 300", Commands.swipe(5, 1, 2, 3, 4, 300))
        assertEquals("input -d 5 swipe 9 9 9 9 650", Commands.longPress(5, 9, 9))
        assertEquals("input -d 5 swipe 1 2 3 4 10000", Commands.swipe(5, 1, 2, 3, 4, 99_999), "duration capped")
        assertEquals("input -d 5 keyevent 4", Commands.key(5, Commands.KEY_BACK))
    }

    @Test fun textEscapesSpacesAndQuotes() {
        assertEquals("input -d 2 text 'it'\\''s%sfine'", Commands.text(2, "it's fine"))
        assertNull(Commands.text(2, "naïve"), "non-ASCII goes through the clipboard")
        assertNull(Commands.text(2, "a\nb"))
        assertNull(Commands.text(2, ""))
    }

    @Test fun presentCommands() {
        assertEquals("am display move-stack 12 0", Commands.moveStack(12, 0))
        assertEquals("am start --display 0 --activity-reorder-to-front -n 'com.shop/.Pay'", Commands.reorderToFront(0, "com.shop/.Pay"))
    }

    @Test fun helperRunsFromApk() = assertEquals(
        "CLASSPATH='/data/app/x/base.apk' app_process /system/bin atom.vdisplay.display.RootHelper 'ime-policy' '5' '2'",
        Commands.helper("/data/app/x/base.apk", "ime-policy", "5", "2"),
    )
}
