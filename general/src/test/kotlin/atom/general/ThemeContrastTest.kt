package atom.general

import atom.general.appearance.AppearanceBackup
import atom.general.appearance.AppearanceCodec
import atom.ui.theme.AccentColor
import atom.ui.theme.AccentTones
import atom.ui.theme.Appearance
import atom.ui.theme.AtomPalettes
import atom.ui.theme.ColorStyle
import atom.ui.theme.TextSize
import atom.ui.theme.ThemeMode
import atom.ui.theme.ThemeSpec
import atom.ui.theme.TopBarBlur
import atom.ui.theme.UiStyle
import atom.ui.theme.contrastRatio
import atom.ui.theme.scaleSaturation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemeContrastTest {

    private fun check(name: String, t: AccentTones, backgrounds: List<Long>) {
        fun aa(label: String, a: Long, b: Long) {
            val r = contrastRatio(a, b)
            assertTrue(r >= 4.5, "$name $label contrast %.2f < 4.5".format(r))
        }
        aa("primary/onPrimary", t.primary, t.onPrimary)
        aa("container/onContainer", t.primaryContainer, t.onPrimaryContainer)
        backgrounds.forEach { bg -> aa("primary on %08X".format(bg), t.primary, bg) }
    }

    @Test fun monochromeAccentMeetsAa() {
        check("mono dark", AtomPalettes.Monochrome.dark, AtomPalettes.DarkSurfaces)
        check("mono light", AtomPalettes.Monochrome.light, AtomPalettes.LightSurfaces)
        assertNotNull(AtomPalettes.of(AccentColor.Monochrome))
        assertNull(AtomPalettes.of(AccentColor.Blue))
    }

    @Test fun defaultsMatchTheAtomLook() {
        val d = Appearance()
        assertEquals(ThemeMode.System, d.mode)
        assertFalse(d.monet)
        assertTrue(d.pureBlack)
        assertEquals(AccentColor.System, d.accent)
        assertEquals(ColorStyle.TonalSpot, d.colorStyle)
        assertEquals(UiStyle.Miuix, d.uiStyle)
        assertTrue(d.blur)
        assertTrue(d.progressiveBlur)
        assertEquals(TopBarBlur.Gradient, d.topBarBlur)
        assertTrue(d.swipeBackAnywhere)
        assertTrue(d.predictiveBack)
        assertEquals(TextSize.Default, d.textSize)
        assertEquals(0xFF000000, AtomPalettes.PureBlack)
    }

    @Test fun accentSeedsAndMenuOrder() {
        assertEquals(
            listOf("System", "Blue", "Purple", "Pink", "Red", "Orange", "Yellow", "Green", "Teal", "Monochrome"),
            AccentColor.entries.map { it.name },
        )
        assertEquals(0xFF4285F4, AccentColor.Blue.seed)
        assertEquals(0xFF7C4DFF, AccentColor.Purple.seed)
        assertEquals(0xFFE91E63, AccentColor.Pink.seed)
        assertEquals(0xFFEA4335, AccentColor.Red.seed)
        assertEquals(0xFFFF5722, AccentColor.Orange.seed)
        assertEquals(0xFFFBBC04, AccentColor.Yellow.seed)
        assertEquals(0xFF34A853, AccentColor.Green.seed)
        assertEquals(0xFF00BCD4, AccentColor.Teal.seed)
        assertNull(AccentColor.System.seed)
        assertNull(AccentColor.Monochrome.seed)
        assertEquals(
            listOf("TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow", "FruitSalad", "Monochrome", "Fidelity", "Content", "Cmf"),
            ColorStyle.entries.map { it.name },
        )
        ColorStyle.entries.forEach { assertEquals(it.name, ThemeSpec.paletteStyle(it).name) }
        assertEquals(ThemePaletteStyle.Cmf, ThemeSpec.paletteStyle(ColorStyle.Cmf))
    }

    @Test fun schemeModeFollowsMonetAndDarkness() {
        val off = Appearance()
        assertEquals(ColorSchemeMode.Dark, ThemeSpec.schemeMode(off, dark = true))
        assertEquals(ColorSchemeMode.Light, ThemeSpec.schemeMode(off, dark = false))
        // The accent is ignored while Monet is off.
        assertEquals(ColorSchemeMode.Dark, ThemeSpec.schemeMode(off.copy(accent = AccentColor.Red), dark = true))
        val on = off.copy(monet = true)
        assertEquals(ColorSchemeMode.MonetDark, ThemeSpec.schemeMode(on, dark = true))
        assertEquals(ColorSchemeMode.MonetLight, ThemeSpec.schemeMode(on, dark = false))
        // Monochrome is the house style on top of the stock scheme, not a Monet palette.
        val mono = on.copy(accent = AccentColor.Monochrome)
        assertEquals(ColorSchemeMode.Dark, ThemeSpec.schemeMode(mono, dark = true))
        assertTrue(ThemeSpec.usesMonochrome(mono))
        assertFalse(ThemeSpec.usesMonochrome(off.copy(accent = AccentColor.Monochrome)))
    }

    @Test fun keyColorRules() {
        val on = Appearance(monet = true)
        // Monet off: no key at all.
        assertNull(ThemeSpec.keyColor(Appearance(accent = AccentColor.Blue), systemSeed = 0xFF112233))
        // System + Tonal Spot: the library reads the wallpaper itself.
        assertNull(ThemeSpec.keyColor(on, systemSeed = 0xFF112233))
        // System + other styles: the wallpaper primary, or blue without one.
        assertEquals(0xFF112233, ThemeSpec.keyColor(on.copy(colorStyle = ColorStyle.Vibrant), systemSeed = 0xFF112233))
        assertEquals(AtomPalettes.FallbackSeed, ThemeSpec.keyColor(on.copy(colorStyle = ColorStyle.Vibrant), systemSeed = null))
        // Fixed accents seed with their own colour in every style.
        assertEquals(0xFFE91E63, ThemeSpec.keyColor(on.copy(accent = AccentColor.Pink), systemSeed = 0xFF112233))
        assertEquals(0xFF00BCD4, ThemeSpec.keyColor(on.copy(accent = AccentColor.Teal, colorStyle = ColorStyle.Cmf), null))
        assertNull(ThemeSpec.keyColor(on.copy(accent = AccentColor.Monochrome), null))
    }

    @Test fun pureBlackOnlyInDark() {
        assertTrue(ThemeSpec.pureBlack(Appearance(), dark = true))
        assertFalse(ThemeSpec.pureBlack(Appearance(), dark = false))
        assertFalse(ThemeSpec.pureBlack(Appearance(pureBlack = false), dark = true))
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
        val a = Appearance(
            ThemeMode.Dark, false, AccentColor.Teal, ColorStyle.FruitSalad, false, TopBarBlur.Progressive, false, false, false,
            TextSize.Large, monet = true, uiStyle = UiStyle.Miuix,
        )
        assertEquals(a, AppearanceCodec.decode(AppearanceCodec.encode(a)))
        assertEquals(Appearance(), AppearanceCodec.decode(mapOf("accent" to "Ultraviolet", "blur" to "maybe", "uiStyle" to "Other")))
        assertEquals(Appearance(), AppearanceCodec.decode(emptyMap()))
        val json = AppearanceBackup.toJson(AppearanceCodec.encode(a))
        assertNull(AppearanceBackup.problem(json))
        assertEquals(a, AppearanceCodec.decode(AppearanceBackup.fromJson(json)))
        assertNotNull(AppearanceBackup.problem(JsonPrimitive("x")))
        assertNotNull(AppearanceBackup.problem(JsonObject(mapOf("mode" to JsonObject(emptyMap())))))
    }

    @Test fun migratesValuesSavedByEarlierBuilds() {
        // Retired accents fall back to the default accent; Monet stays off.
        listOf("Silver", "Ember", "Jade", "Iris", "Rose").forEach { old ->
            val m = AppearanceCodec.decode(mapOf("accent" to old, "colorStyle" to "Neutral"))
            assertEquals(AccentColor.System, m.accent, old)
            assertFalse(m.monet, old)
            assertEquals(ColorStyle.Neutral, m.colorStyle)
        }
        // The old default accent (Monochrome) becomes the default accent, so turning Monet on gives wallpaper
        // colours; stock colours stay on (Monet off) and the stored mode is kept.
        val mono = AppearanceCodec.decode(mapOf("accent" to "Monochrome", "mode" to "Dark"))
        assertEquals(AccentColor.System, mono.accent)
        assertFalse(mono.monet)
        assertEquals(ThemeMode.Dark, mono.mode)
        // Once the Monet switch exists in the saved data, Monochrome is a deliberate choice and is kept.
        val chosen = AppearanceCodec.decode(mapOf("accent" to "Monochrome", "monet" to "true"))
        assertEquals(AccentColor.Monochrome, chosen.accent)
        assertTrue(chosen.monet)
        // Picking "System" used to mean wallpaper colours: keep that on.
        val wall = AppearanceCodec.decode(mapOf("accent" to "System", "colorStyle" to "Vibrant"))
        assertTrue(wall.monet)
        assertEquals(AccentColor.System, wall.accent)
        assertEquals(ColorStyle.Vibrant, wall.colorStyle)
        // Once the Monet switch has been saved, it wins.
        assertFalse(AppearanceCodec.decode(mapOf("accent" to "System", "monet" to "false")).monet)
        // Old text size names still decode.
        assertEquals(TextSize.Small, AppearanceCodec.decode(mapOf("textSize" to "Small")).textSize)
    }
}
