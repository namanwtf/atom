package atom.general

import atom.general.update.SemVer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {
    private fun v(s: String) = assertNotNull(SemVer.parse(s), s)

    @Test fun parsesTagsLeniently() {
        assertEquals(SemVer(1, 2, 3), v("v1.2.3"))
        assertEquals(SemVer(1, 4, 0), v("1.4"))
        assertEquals(SemVer(2, 0, 0), v("V2"))
        assertEquals(SemVer(1, 0, 0, listOf("beta", "2")), v("1.0.0-beta.2+build.7"))
        assertEquals("1.0.0-rc.1", v(" v1.0.0-rc.1 ").toString())
    }

    @Test fun rejectsGarbage() {
        listOf("", "v", "1.2.3.4", "a.b.c", "1..2", "1.2.3-", "1.2.3-01", "1.2.3-be ta", "-1.2.3").forEach {
            assertNull(SemVer.parse(it), it)
        }
    }

    @Test fun precedenceFollowsSemverSpec() {
        // Ordering example from semver.org section 11.
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
            "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0",
        ).map(::v)
        for (i in 0 until ordered.size - 1) {
            assertTrue(ordered[i] < ordered[i + 1], "${ordered[i]} < ${ordered[i + 1]}")
        }
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
    }

    @Test fun numericComparisonNotLexical() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("0.10.0") > v("0.2.0"))
        assertEquals(0, v("1.2.3+a").compareTo(v("1.2.3+b")))
    }
}
