package atom.vdisplay

import atom.vdisplay.route.DisplayBackend
import atom.vdisplay.route.DisplayInfo
import atom.vdisplay.route.DisplayUnavailable
import atom.vdisplay.route.LeaseManager
import atom.vdisplay.route.LeaseState
import atom.vdisplay.route.RouteReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LeaseManagerTest {
    private class FakeBackend(var fail: Boolean = false) : DisplayBackend {
        var created = 0
        val destroyed = mutableListOf<Int>()
        var nextId = 7

        override fun create(): DisplayInfo {
            if (fail) throw DisplayUnavailable("no")
            created++
            return DisplayInfo(nextId++, 720, 1600, 320)
        }

        override fun destroy(id: Int) {
            destroyed += id
        }
    }

    @Test fun hiddenLeaseCreatesOnceAndReleaseDestroys() {
        val b = FakeBackend()
        val m = LeaseManager(b)
        val a = m.acquireHidden("com.a", "c1")
        val again = m.acquireHidden("com.b", "c1")
        assertEquals(1, b.created, "the display is reused")
        assertEquals(a.displayId, again.displayId)
        assertFalse(a.main)
        assertSame(again, m.current)
        again.release()
        assertEquals(listOf(7), b.destroyed)
        assertIs<LeaseState.Idle>(m.state.value)
    }

    @Test fun staleHandleCannotReleaseNewerLease() {
        val b = FakeBackend()
        val m = LeaseManager(b)
        val old = m.acquireHidden("com.a", null)
        val new = m.acquireHidden("com.a", null)
        old.release()
        assertSame(new, m.current)
        assertTrue(b.destroyed.isEmpty())
    }

    @Test fun mainLeaseReleasesHiddenDisplay() {
        val b = FakeBackend()
        val m = LeaseManager(b)
        m.acquireHidden("com.a", null)
        val main = m.acquireMain("com.home", null, RouteReason.LauncherOrSystemUi, 1080, 2400, 480)
        assertTrue(main.main)
        assertEquals(0, main.displayId)
        assertEquals(listOf(7), b.destroyed)
    }

    @Test fun removedDisplayIsLostAndNeverReplayed() {
        val b = FakeBackend()
        val m = LeaseManager(b)
        val lease = m.acquireHidden("com.a", null)
        m.onDisplayRemoved(lease.displayId)
        val lost = assertIs<LeaseState.Lost>(m.state.value)
        assertSame(lease, lost.lease)
        val e = assertFailsWith<DisplayUnavailable> { m.require(lease) }
        assertTrue(e.message!!.startsWith("Display unavailable"))
        assertTrue("main screen" in e.message!!)
        assertNull(m.current)
        lease.release()
        assertIs<LeaseState.Idle>(m.state.value)
        assertTrue(b.destroyed.isEmpty(), "a display the system removed is not destroyed again")
    }

    @Test fun removalOfAnotherDisplayIsIgnored() {
        val m = LeaseManager(FakeBackend())
        val lease = m.acquireHidden("com.a", null)
        m.onDisplayRemoved(99)
        assertSame(lease, m.require(lease))
    }

    @Test fun releasedLeaseIsUnavailable() {
        val m = LeaseManager(FakeBackend())
        val lease = m.acquireHidden("com.a", null)
        m.releaseAll()
        assertFailsWith<DisplayUnavailable> { m.require(lease) }
    }

    @Test fun backendFailureLeavesIdle() {
        val m = LeaseManager(FakeBackend(fail = true))
        assertFailsWith<DisplayUnavailable> { m.acquireHidden("com.a", null) }
        assertIs<LeaseState.Idle>(m.state.value)
    }

    @Test fun newDisplayAfterRelease() {
        val b = FakeBackend()
        val m = LeaseManager(b)
        m.acquireHidden("com.a", null).release()
        val second = m.acquireHidden("com.a", null)
        assertEquals(2, b.created)
        assertEquals(8, second.displayId)
    }
}
