package atom.general

import atom.general.appearance.AppearanceBackup
import atom.general.appearance.AppearanceCodec
import atom.ui.theme.AccentColor
import atom.ui.theme.AccentTones
import atom.ui.theme.Appearance
import atom.ui.theme.AtomPalettes
import atom.ui.theme.ColorStyle
import atom.ui.theme.ThemeMode
import atom.ui.theme.TopBarBlur
import atom.ui.theme.contrastRatio
import atom.ui.theme.scaleSaturation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeContrastTest {
    private val fixed = AccentColor.entries.filter { it != AccentColor.System }

    private fun check(name: String, t: AccentTones, backgrounds: List<Long>) {
        fun aa(label: String, a: Long, b: Long) {
            val r = contrastRatio(a, b)
            assertTrue(r >= 4.5, "$name $label contrast %.2f < 4.5".format(r))
        }
        aa("primary/onPrimary", t.primary, t.onPrimary)
        aa("container/onContainer", t.primaryContainer, t.onPrimaryContainer)
        backgrounds.forEach { bg -> aa("primary on %08X".format(bg), t.primary, bg) }
    }

    @Test fun everyAccentMeetsAaInDark() {
        fixed.forEach { a -> check("$a dark", assertNotNull(AtomPalettes.of(a)).dark, AtomPalettes.DarkSurfaces) }
    }

    @Test fun everyAccentMeetsAaInLight() {
        fixed.forEach { a -> check("$a light", assertNotNull(AtomPalettes.of(a)).light, AtomPalettes.LightSurfaces) }
    }

    @Test fun defaultsMatchTheOriginalLook() {
        val d = Appearance()
        assertEquals(ThemeMode.Dark, d.mode)
        assertTrue(d.pureBlack)
        assertEquals(AccentColor.Monochrome, d.accent)
        assertTrue(d.blur)
        assertEquals(0xFF000000, AtomPalettes.PureBlack)
        assertEquals(0xFFF4F4F6, AtomPalettes.Monochrome.dark.primary) // white accent, never blue
        assertNull(AtomPalettes.of(AccentColor.System))
    }

    @Test fun contrastMath() {
        assertEquals(21.0, contrastRatio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, contrastRatio(0xFF777777, 0xFF777777), 0.0001)
    }

    @Test fun saturationScaling() {
        assertEquals(0xFF808080, scaleSaturation(0xFF808080, 2f)) // grey stays grey
        val grey = scaleSaturation(0xFFCC3333, 0f)
        assertEquals((grey shr 16) and 0xFF, grey and 0xFF)
        assertEquals((grey shr 8) and 0xFF, grey and 0xFF)
        assertEquals(0xFFCC3333, scaleSaturation(0xFFCC3333, 1f))
    }

    @Test fun appearanceCodecRoundTripsAndToleratesJunk() {
        val a = Appearance(ThemeMode.System, false, AccentColor.Ember, ColorStyle.Vibrant, false, TopBarBlur.Progressive, false, false, true)
        assertEquals(a, AppearanceCodec.decode(AppearanceCodec.encode(a)))
        assertEquals(Appearance(), AppearanceCodec.decode(mapOf("accent" to "Blue", "blur" to "maybe")))
        val json = AppearanceBackup.toJson(AppearanceCodec.encode(a))
        assertNull(AppearanceBackup.problem(json))
        assertEquals(a, AppearanceCodec.decode(AppearanceBackup.fromJson(json)))
        assertNotNull(AppearanceBackup.problem(JsonPrimitive("x")))
        assertNotNull(AppearanceBackup.problem(JsonObject(mapOf("mode" to JsonObject(emptyMap())))))
    }
}
