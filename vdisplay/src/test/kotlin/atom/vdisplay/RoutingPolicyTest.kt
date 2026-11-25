package atom.vdisplay

import atom.vdisplay.route.RouteInput
import atom.vdisplay.route.RouteReason
import atom.vdisplay.route.RoutingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutingPolicyTest {
    private val base = RouteInput(
        enabled = true,
        rootAvailable = true,
        supported = true,
        targetPackage = "com.shop",
        foregroundPackage = "com.chat",
        selfPackage = "io.atom",
        launcherPackages = setOf("com.launcher"),
    )

    private fun decide(i: RouteInput) = RoutingPolicy.decide(i)

    @Test fun backgroundAppGoesHidden() {
        val d = decide(base)
        assertTrue(d.hidden)
        assertEquals(RouteReason.Hidden, d.reason)
    }

    @Test fun appAlreadyInFrontStaysOnMain() =
        assertEquals(RouteReason.TargetInFront, decide(base.copy(foregroundPackage = "com.shop")).reason)

    @Test fun launcherAndSystemUiStayOnMain() {
        assertEquals(RouteReason.LauncherOrSystemUi, decide(base.copy(targetPackage = "com.launcher")).reason)
        assertEquals(RouteReason.LauncherOrSystemUi, decide(base.copy(targetPackage = "com.android.systemui")).reason)
        assertEquals(RouteReason.LauncherOrSystemUi, decide(base.copy(targetPackage = "android")).reason)
    }

    @Test fun switchOffRootlessUnsupportedSelfAndBlankStayOnMain() {
        assertEquals(RouteReason.Disabled, decide(base.copy(enabled = false)).reason)
        assertEquals(RouteReason.NoRoot, decide(base.copy(rootAvailable = false)).reason)
        assertEquals(RouteReason.Unsupported, decide(base.copy(supported = false)).reason)
        assertEquals(RouteReason.Self, decide(base.copy(targetPackage = "io.atom")).reason)
        assertEquals(RouteReason.NoTarget, decide(base.copy(targetPackage = "  ")).reason)
        assertEquals(RouteReason.NoTarget, decide(base.copy(targetPackage = null)).reason)
    }

    @Test fun unknownForegroundStillRoutesHidden() = assertTrue(decide(base.copy(foregroundPackage = null)).hidden)

    @Test fun inFrontWinsOverMissingRoot() {
        val d = decide(base.copy(foregroundPackage = "com.shop", rootAvailable = false))
        assertFalse(d.hidden)
        assertEquals(RouteReason.TargetInFront, d.reason)
    }
}
