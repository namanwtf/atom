package atom.device.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppMatcherTest {
    private val apps = listOf(
        AppEntry("WhatsApp", "com.whatsapp"),
        AppEntry("WhatsApp Business", "com.whatsapp.w4b"),
        AppEntry("Maps", "com.google.android.apps.maps"),
        AppEntry("Settings", "com.android.settings"),
        AppEntry("Chrome", "com.android.chrome"),
        AppEntry("Google Pay", "com.google.android.apps.nbu.paisa.user"),
        AppEntry("Café Lite", "org.cafe.lite"),
        AppEntry("YouTube Music", "com.google.android.apps.youtube.music"),
        AppEntry("YouTube", "com.google.android.youtube"),
    )

    private fun best(q: String) = AppMatcher.best(q, apps)?.app?.packageName

    @Test fun exactLabelBeatsLongerLabels() = assertEquals("com.whatsapp", best("whatsapp"))
    @Test fun spacesAndCaseIgnored() = assertEquals("com.whatsapp", best("Whats App"))
    @Test fun exactPackageWins() = assertEquals("com.whatsapp.w4b", best("com.whatsapp.w4b"))
    @Test fun prefix() = assertEquals("com.android.settings", best("sett"))
    @Test fun tokenPrefixes() = assertEquals("com.google.android.apps.nbu.paisa.user", best("g pay"))
    @Test fun typoTolerance() = assertEquals("com.android.chrome", best("chorme"))
    @Test fun diacriticsFolded() = assertEquals("org.cafe.lite", best("cafe lite"))
    @Test fun shorterLabelPreferredOnTie() = assertEquals("com.google.android.youtube", best("youtube"))
    @Test fun packageSegment() = assertEquals("com.google.android.apps.nbu.paisa.user", best("paisa"))
    @Test fun nonsenseMatchesNothing() = assertNull(best("zzqx"))
    @Test fun blankMatchesNothing() = assertNull(best("  "))

    @Test
    fun rankingOrder() {
        val ranked = AppMatcher.rank("youtube music", apps).map { it.app.label }
        assertEquals("YouTube Music", ranked.first())
    }

    @Test
    fun levenshtein() {
        assertEquals(0, AppMatcher.levenshtein("abc", "abc"))
        assertEquals(1, AppMatcher.levenshtein("chrome", "chorme")) // transposition counts once
        assertEquals(3, AppMatcher.levenshtein("kitten", "sitting"))
        assertEquals(3, AppMatcher.levenshtein("", "abc"))
    }
}
