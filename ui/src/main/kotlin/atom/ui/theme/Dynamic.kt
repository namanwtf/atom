package atom.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color

/** True when the device can supply wallpaper (Monet) colours. */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * One system tonal palette, indexed by the framework's steps (0 = white ... 1000 = black). Steps used here:
 * 0, 10, 50, 100, 200, ... 900, 1000.
 */
class TonalPalette(private val byStep: Map<Int, Long>) {
    operator fun get(step: Int): Long = byStep[step] ?: error("no step $step")

    /** Same palette with every tone's saturation scaled by [factor] (lightness kept, so contrast barely moves). */
    fun saturate(factor: Float): TonalPalette =
        if (factor == 1f) this else TonalPalette(byStep.mapValues { scaleSaturation(it.value, factor) })
}

/** The five wallpaper palettes the system exposes (accent 1-3, neutral 1-2). */
class SystemPalettes(
    val accent1: TonalPalette,
    val accent2: TonalPalette,
    val accent3: TonalPalette,
    val neutral1: TonalPalette,
    val neutral2: TonalPalette,
)

private val STEPS = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

@RequiresApi(Build.VERSION_CODES.S)
private val IDS: Array<IntArray> = arrayOf(
    intArrayOf(
        android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
        android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
        android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
        android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
        android.R.color.system_accent1_1000,
    ),
    intArrayOf(
        android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50,
        android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300,
        android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600,
        android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900,
        android.R.color.system_accent2_1000,
    ),
    intArrayOf(
        android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50,
        android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300,
        android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600,
        android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900,
        android.R.color.system_accent3_1000,
    ),
    intArrayOf(
        android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
        android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
        android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
        android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
        android.R.color.system_neutral1_1000,
    ),
    intArrayOf(
        android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50,
        android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
        android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600,
        android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
        android.R.color.system_neutral2_1000,
    ),
)

@RequiresApi(Build.VERSION_CODES.S)
fun readSystemPalettes(context: Context): SystemPalettes {
    val p = IDS.map { ids ->
        TonalPalette(STEPS.indices.associate { i -> STEPS[i] to (context.getColor(ids[i]).toLong() and 0xFFFFFFFFL) })
    }
    return SystemPalettes(p[0], p[1], p[2], p[3], p[4])
}

/** Wallpaper-based scheme in the chosen [style]. TonalSpot is exactly the platform's own dynamic scheme. */
@RequiresApi(Build.VERSION_CODES.S)
internal fun dynamicScheme(context: Context, dark: Boolean, style: ColorStyle): ColorScheme {
    val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    if (style == ColorStyle.TonalSpot) return base
    return styledScheme(base, readSystemPalettes(context), dark, style)
}

/** Re-maps the primary/secondary/tertiary roles of [base] from [sys] according to [style]. Pure; testable. */
fun styledScheme(base: ColorScheme, sys: SystemPalettes, dark: Boolean, style: ColorStyle): ColorScheme {
    val (p, s, t) = when (style) {
        ColorStyle.TonalSpot -> return base
        ColorStyle.Neutral -> Triple(sys.accent2.saturate(0.55f), sys.neutral2, sys.accent3.saturate(0.55f))
        ColorStyle.Vibrant -> Triple(sys.accent1.saturate(1.35f), sys.accent2.saturate(1.4f), sys.accent3.saturate(1.3f))
        ColorStyle.Expressive -> Triple(sys.accent3, sys.accent1.saturate(0.85f), sys.accent2.saturate(1.3f))
        ColorStyle.Monochrome -> {
            val grey = sys.neutral1.saturate(0f)
            Triple(grey, grey, grey)
        }
    }
    fun c(v: Long) = Color(v)
    var out = if (dark) {
        base.copy(
            primary = c(p[200]), onPrimary = c(p[800]), primaryContainer = c(p[700]), onPrimaryContainer = c(p[100]),
            inversePrimary = c(p[600]),
            secondary = c(s[200]), onSecondary = c(s[800]), secondaryContainer = c(s[700]), onSecondaryContainer = c(s[100]),
            tertiary = c(t[200]), onTertiary = c(t[800]), tertiaryContainer = c(t[700]), onTertiaryContainer = c(t[100]),
        )
    } else {
        base.copy(
            primary = c(p[600]), onPrimary = c(p[0]), primaryContainer = c(p[100]), onPrimaryContainer = c(p[900]),
            inversePrimary = c(p[200]),
            secondary = c(s[600]), onSecondary = c(s[0]), secondaryContainer = c(s[100]), onSecondaryContainer = c(s[900]),
            tertiary = c(t[600]), onTertiary = c(t[0]), tertiaryContainer = c(t[100]), onTertiaryContainer = c(t[900]),
        )
    }
    if (style == ColorStyle.Monochrome) out = out.desaturateSurfaces()
    return out
}

private fun ColorScheme.desaturateSurfaces(): ColorScheme {
    fun g(c: Color) = Color(scaleSaturation(c.toArgbLong(), 0f))
    return copy(
        background = g(background), onBackground = g(onBackground), surface = g(surface), onSurface = g(onSurface),
        surfaceVariant = g(surfaceVariant), onSurfaceVariant = g(onSurfaceVariant),
        surfaceContainerLowest = g(surfaceContainerLowest), surfaceContainerLow = g(surfaceContainerLow),
        surfaceContainer = g(surfaceContainer), surfaceContainerHigh = g(surfaceContainerHigh),
        surfaceContainerHighest = g(surfaceContainerHighest), surfaceBright = g(surfaceBright), surfaceDim = g(surfaceDim),
        outline = g(outline), outlineVariant = g(outlineVariant), inverseSurface = g(inverseSurface),
        inverseOnSurface = g(inverseOnSurface),
    )
}

internal fun Color.toArgbLong(): Long {
    val a = (alpha * 255f + 0.5f).toInt()
    val r = (red * 255f + 0.5f).toInt()
    val g = (green * 255f + 0.5f).toInt()
    val b = (blue * 255f + 0.5f).toInt()
    return (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
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
