package atom.vdisplay

import atom.vdisplay.route.DisplayBackend
import atom.vdisplay.route.DisplayInfo
import atom.vdisplay.route.LeaseManager
import atom.vdisplay.route.RouteReason
import atom.vdisplay.system.BackgroundUse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundUseTest {
    @Test fun settingIsOnUnlessTheUserTurnedItOff() {
        assertTrue(BackgroundUse.storedSetting(chosenByUser = false, stored = null), "new install")
        assertTrue(BackgroundUse.storedSetting(chosenByUser = false, stored = false), "an off the user never chose")
        assertTrue(BackgroundUse.storedSetting(chosenByUser = true, stored = true))
        assertFalse(BackgroundUse.storedSetting(chosenByUser = true, stored = false), "the user's own off is kept")
    }

    @Test fun usableOnlyWithTheSettingAndRoot() {
        assertTrue(BackgroundUse.usable(settingOn = true, rootGranted = true))
        assertFalse(BackgroundUse.usable(settingOn = true, rootGranted = false))
        assertFalse(BackgroundUse.usable(settingOn = false, rootGranted = true))
        assertFalse(BackgroundUse.usable(settingOn = false, rootGranted = false))
    }

    @Test fun hintTellsWhenToUseTheHiddenScreenAndWhenToHandItBack() {
        val h = BackgroundUse.PROMPT_HINT
        listOf("background_open_app", "instead of open_app", "background_look", "background_act", "present_to_main_screen", "payment", "QR code", "background_close")
            .forEach { assertTrue(it in h, "missing: $it") }
    }

    private class Backend : DisplayBackend {
        override fun create() = DisplayInfo(9, 720, 1600, 320)
        override fun destroy(id: Int) = Unit
    }

    @Test fun capsuleSeesARunWorkingOnlyOnTheHiddenDisplay() {
        val m = LeaseManager(Backend())
        assertFalse(m.hiddenHeldBy(listOf("run-1", "conv-1")))
        m.acquireHidden("com.shop", "run-1")
        assertTrue(m.hiddenHeldBy(listOf("run-1", "conv-1")))
        assertFalse(m.hiddenHeldBy(listOf("run-2", "conv-2")), "another run's display")
        m.onDisplayRemoved(9)
        assertTrue(m.hiddenHeldBy(listOf("run-1")), "a lost display still shows, as unavailable")
        m.releaseAll()
        assertFalse(m.hiddenHeldBy(listOf("run-1")))
        m.acquireMain("com.shop", "run-1", RouteReason.TargetInFront, 1080, 2400, 420)
        assertFalse(m.hiddenHeldBy(listOf("run-1")), "a main-screen lease is not the hidden display")
    }
}
