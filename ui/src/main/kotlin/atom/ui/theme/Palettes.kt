package atom.ui.theme

import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/*
 * Plain ARGB values and pure decisions, so they can be checked on the JVM without Compose.
 */

/** The four colours an accent sets. */
data class AccentTones(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
)

data class AccentPalette(val dark: AccentTones, val light: AccentTones)

object AtomPalettes {
    /** The monochrome house style: white on black in dark mode, near-black on light. */
    val Monochrome = AccentPalette(
        dark = AccentTones(0xFFF4F4F6, 0xFF0A0A0B, 0xFF26262A, 0xFFF4F4F6),
        light = AccentTones(0xFF111113, 0xFFFFFFFF, 0xFFE6E6EA, 0xFF111113),
    )

    /** Fixed tones for [accent], or null when the accent seeds a Monet palette instead. */
    fun of(accent: AccentColor): AccentPalette? = if (accent == AccentColor.Monochrome) Monochrome else null

    /** Seed used for [AccentColor.System] when the device has no wallpaper colours (and the Blue accent). */
    const val FallbackSeed = 0xFF4285F4

    // Page backgrounds: pure black, the dark grey used when pure black is off, and the light page.
    const val PureBlack = 0xFF000000
    const val Graphite = 0xFF121212

    /** The Miuix card colour in dark mode; cards stay this colour with pure black on. */
    const val DarkCard = 0xFF242424
    val DarkSurfaces = listOf(PureBlack, Graphite, DarkCard)
    val LightSurfaces = listOf(0xFFF7F7F7, 0xFFFFFFFF)
}

/**
 * How an [Appearance] maps onto the Miuix theme controller. Pure, so the choices are unit-tested.
 */
object ThemeSpec {
    /** Miuix scheme mode: Monet variants only when Monet is on and the accent is not the monochrome house style. */
    fun schemeMode(a: Appearance, dark: Boolean): ColorSchemeMode = when {
        usesMonet(a) -> if (dark) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight
        dark -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.Light
    }

    fun usesMonet(a: Appearance): Boolean = a.monet && a.accent != AccentColor.Monochrome

    /** True when the monochrome primaries replace the stock blue. */
    fun usesMonochrome(a: Appearance): Boolean = a.monet && a.accent == AccentColor.Monochrome

    /**
     * Key colour for the Monet palette, or null to let the library read the wallpaper palette itself.
     * System + Tonal Spot passes no key (the platform palette is already tonal spot); System with any other
     * style is seeded by the wallpaper's primary ([systemSeed], or the blue fallback without one).
     * Note: with no key, Miuix uses the system's own palette (on Android 13+ including the system's chosen style),
     * which is what "System colors" means.
     */
    fun keyColor(a: Appearance, systemSeed: Long?): Long? {
        if (!usesMonet(a)) return null
        return when (a.accent) {
            AccentColor.System -> if (a.colorStyle == ColorStyle.TonalSpot) null else systemSeed ?: AtomPalettes.FallbackSeed
            else -> a.accent.seed ?: AtomPalettes.FallbackSeed
        }
    }

    /** Every [ColorStyle] has a Miuix palette style of the same name. */
    fun paletteStyle(s: ColorStyle): ThemePaletteStyle = when (s) {
        ColorStyle.TonalSpot -> ThemePaletteStyle.TonalSpot
        ColorStyle.Neutral -> ThemePaletteStyle.Neutral
        ColorStyle.Vibrant -> ThemePaletteStyle.Vibrant
        ColorStyle.Expressive -> ThemePaletteStyle.Expressive
        ColorStyle.Rainbow -> ThemePaletteStyle.Rainbow
        ColorStyle.FruitSalad -> ThemePaletteStyle.FruitSalad
        ColorStyle.Monochrome -> ThemePaletteStyle.Monochrome
        ColorStyle.Fidelity -> ThemePaletteStyle.Fidelity
        ColorStyle.Content -> ThemePaletteStyle.Content
        ColorStyle.Cmf -> ThemePaletteStyle.Cmf
    }

    /** Pure black applies only in dark mode. */
    fun pureBlack(a: Appearance, dark: Boolean): Boolean = dark && a.pureBlack
}

/** WCAG relative luminance of an opaque ARGB colour. */
fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/** WCAG contrast ratio between two opaque ARGB colours (1.0 to 21.0). */
fun contrastRatio(a: Long, b: Long): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** Scales HSL saturation of an ARGB colour by [factor] (clamped to 0..1), keeping hue and lightness. */
fun scaleSaturation(argb: Long, factor: Float): Long {
    val a = (argb shr 24) and 0xFF
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return argb
    var s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = when (max) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f)) / 6f
        g -> ((b - r) / d + 2f) / 6f
        else -> ((r - g) / d + 4f) / 6f
    }
    s = (s * factor).coerceIn(0f, 1f)
    fun hue2rgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    val (nr, ng, nb) = if (s == 0f) {
        Triple(l, l, l)
    } else {
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        Triple(hue2rgb(p, q, h + 1f / 3f), hue2rgb(p, q, h), hue2rgb(p, q, h - 1f / 3f))
    }
    fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
    return (a shl 24) or (ch(nr) shl 16) or (ch(ng) shl 8) or ch(nb)
}
