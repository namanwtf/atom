package atom.ui.theme

/*
 * Accent tones as plain ARGB so they can be checked for contrast on the JVM without Compose. Each accent has a
 * dark and a light variant; every pair is verified to reach at least 4.5:1 (WCAG AA body text) for
 * primary/onPrimary, primaryContainer/onPrimaryContainer and primary against every background it sits on.
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
    /** Today's monochrome primaries (white on black / near-black on light). */
    val Monochrome = AccentPalette(
        dark = AccentTones(0xFFF4F4F6, 0xFF0A0A0B, 0xFF26262A, 0xFFF4F4F6),
        light = AccentTones(0xFF111113, 0xFFFFFFFF, 0xFFE6E6EA, 0xFF111113),
    )
    /** Cool brushed metal. */
    val Silver = AccentPalette(
        dark = AccentTones(0xFFC9CDD4, 0xFF16181B, 0xFF23262B, 0xFFE3E6EB),
        light = AccentTones(0xFF575D66, 0xFFFFFFFF, 0xFFE2E5EA, 0xFF1D2026),
    )
    /** Warm copper, like a coal's last glow. */
    val Ember = AccentPalette(
        dark = AccentTones(0xFFE6A882, 0xFF2B1407, 0xFF33221A, 0xFFF6D9C6),
        light = AccentTones(0xFF94481F, 0xFFFFFFFF, 0xFFF5E1D4, 0xFF3A1A08),
    )
    /** Soft green stone. */
    val Jade = AccentPalette(
        dark = AccentTones(0xFF93CFB4, 0xFF07251A, 0xFF16291F, 0xFFCDEBDC),
        light = AccentTones(0xFF1F6A50, 0xFFFFFFFF, 0xFFD6ECE1, 0xFF062419),
    )
    /** Dusky violet (deliberately not blue). */
    val Iris = AccentPalette(
        dark = AccentTones(0xFFCBB9EC, 0xFF1E1430, 0xFF262033, 0xFFE6DDF7),
        light = AccentTones(0xFF684D96, 0xFFFFFFFF, 0xFFE8E0F4, 0xFF231640),
    )
    /** Muted dusty rose. */
    val Rose = AccentPalette(
        dark = AccentTones(0xFFE9AABB, 0xFF2E0F18, 0xFF33202A, 0xFFF7DAE2),
        light = AccentTones(0xFF99405A, 0xFFFFFFFF, 0xFFF6E0E6, 0xFF3A1020),
    )

    /** Fixed palette for [accent]; null for [AccentColor.System], which comes from the wallpaper. */
    fun of(accent: AccentColor): AccentPalette? = when (accent) {
        AccentColor.Monochrome -> Monochrome
        AccentColor.Silver -> Silver
        AccentColor.Ember -> Ember
        AccentColor.Jade -> Jade
        AccentColor.Iris -> Iris
        AccentColor.Rose -> Rose
        AccentColor.System -> null
    }

    // Backgrounds accents are drawn on, for contrast checks and for the scheme builder.
    const val PureBlack = 0xFF000000
    const val Graphite = 0xFF0E0E10
    val DarkSurfaces = listOf(PureBlack, Graphite, 0xFF121214, 0xFF19191C, 0xFF1B1B1E)
    val LightSurfaces = listOf(0xFFF6F6F8, 0xFFFFFFFF, 0xFFEDEDF0)
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
