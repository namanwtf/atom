package atom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.compose.material3.lightColorScheme as materialLight
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDark
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLight

/*
 * Atom draws with Miuix. The Miuix colours are the source of truth; the Material 3 scheme is derived from them
 * so screens still built from Material components look the same (primary = Miuix blue, pages = Miuix surface,
 * cards = Miuix surfaceContainer, secondary text = the Miuix summary grey).
 */

private val AtomTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Normal, fontSize = 32.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Normal),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.sp),
    )
}

private val AtomShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

enum class ThemeMode { Dark, Light, System }

/** Extra colours the Material scheme has no slot for. */
@Immutable
data class AtomExtras(val success: Color, val warning: Color, val mono: TextStyle)

val LocalAtomExtras = staticCompositionLocalOf {
    AtomExtras(Color(0xFF34A853), Color(0xFFF9AB00), TextStyle(fontFamily = FontFamily.Monospace))
}

/** Original call shape: mode only, every other appearance option at its default. */
@Composable
fun AtomTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) =
    AtomTheme(appearance = Appearance(mode = mode), content = content)

/**
 * Full appearance: Miuix colours for [appearance] (theme mode, Monet, accent, palette style, pure black), the
 * Material scheme derived from them, the text size, and the flags components read through [LocalAppearance].
 * Content can use both Miuix (`MiuixTheme.colorScheme`) and Material (`MaterialTheme.colorScheme`) tokens.
 */
@Composable
fun AtomTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = isDark(appearance.mode, isSystemInDarkTheme())
    val context = LocalContext.current
    val systemSeed = remember(context) { systemSeedColor(context) }
    val key = ThemeSpec.keyColor(appearance, systemSeed)
    val controller = remember(appearance.monet, appearance.accent, appearance.colorStyle, dark, key) {
        ThemeController(
            colorSchemeMode = ThemeSpec.schemeMode(appearance, dark),
            lightColors = miuixLight(),
            darkColors = miuixDark(),
            keyColor = key?.let { Color(it) },
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = ThemeSpec.paletteStyle(appearance.colorStyle),
            isDark = dark,
        )
    }
    val extras = AtomExtras(
        success = Color(0xFF34A853),
        warning = Color(0xFFF9AB00),
        mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
    )
    val density = LocalDensity.current
    val scaled = remember(density, appearance.textSize) { Density(density.density, density.fontScale * appearance.textSize.scale) }
    MiuixTheme(controller = controller) {
        // The controller's colours, then Atom's adjustments (pure black, monochrome accent) on top.
        val raw = MiuixTheme.colorScheme
        val colors = adjustColors(raw, dark, ThemeSpec.pureBlack(appearance, dark), ThemeSpec.usesMonochrome(appearance))
        val scheme = remember(colors, dark) { materialColorScheme(colors, dark) }
        MiuixTheme(colors = colors) {
            CompositionLocalProvider(LocalAtomExtras provides extras, LocalAppearance provides appearance, LocalDensity provides scaled) {
                MaterialTheme(colorScheme = scheme, typography = AtomTypography, shapes = AtomShapes, content = content)
            }
        }
    }
}

/**
 * Pure black: page background and surface become #000000 in dark mode; cards keep the library container colour.
 * Without pure black, a stock surface that is already black is lifted to a near-black grey so the switch is visible.
 * Monochrome: primaries become white-on-black (dark) or near-black-on-white (light).
 */
@Composable
private fun adjustColors(raw: Colors, dark: Boolean, pureBlack: Boolean, monochrome: Boolean): Colors =
    remember(raw.primary, raw.surface, raw.background, raw.surfaceContainer, dark, pureBlack, monochrome) {
        var c = raw
        if (dark) {
            c = if (pureBlack) {
                c.copy(background = Color.Black, surface = Color.Black)
            } else if (c.surface == Color.Black) {
                val grey = Color(AtomPalettes.Graphite)
                c.copy(surface = grey, background = if (c.background == Color.Black) grey else c.background)
            } else {
                c
            }
        }
        if (monochrome) {
            val t = if (dark) AtomPalettes.Monochrome.dark else AtomPalettes.Monochrome.light
            val p = Color(t.primary)
            val disabled = lerp(p, c.surfaceContainer, 0.6f)
            c = c.copy(
                primary = p,
                onPrimary = Color(t.onPrimary),
                primaryVariant = p,
                primaryContainer = Color(t.primaryContainer),
                onPrimaryContainer = Color(t.onPrimaryContainer),
                tertiaryContainer = Color(t.primaryContainer),
                onTertiaryContainer = p,
                disabledPrimary = disabled,
                disabledPrimaryButton = disabled,
                disabledPrimarySlider = disabled,
                sliderKeyPointForeground = p,
            )
        }
        c
    }

private fun opaque(fg: Color, bg: Color): Color = if (fg.alpha < 1f) fg.compositeOver(bg) else fg

/**
 * Material 3 scheme derived from Miuix [c], for screens and components still built with Material.
 * Translucent Miuix text colours are flattened over the surface they normally sit on.
 */
fun materialColorScheme(c: Colors, dark: Boolean): ColorScheme {
    val page = c.surface
    val card = c.surfaceContainer
    val base = if (dark) darkColorScheme() else materialLight()
    return base.copy(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.tertiaryContainer,
        onPrimaryContainer = opaque(c.onTertiaryContainer, c.tertiaryContainer),
        inversePrimary = c.primaryVariant,
        secondary = opaque(c.onBackgroundVariant, page),
        onSecondary = page,
        secondaryContainer = c.surfaceContainerHighest,
        onSecondaryContainer = opaque(c.onSurfaceContainer, c.surfaceContainerHighest),
        tertiary = c.primaryVariant,
        onTertiary = c.onPrimary,
        tertiaryContainer = c.tertiaryContainer,
        onTertiaryContainer = opaque(c.onTertiaryContainer, c.tertiaryContainer),
        error = c.error,
        onError = c.onError,
        errorContainer = c.errorContainer,
        onErrorContainer = opaque(c.onErrorContainer, c.errorContainer),
        background = page,
        onBackground = opaque(c.onSurface, page),
        surface = page,
        onSurface = opaque(c.onSurface, page),
        surfaceVariant = c.surfaceContainerHigh,
        onSurfaceVariant = opaque(c.onSurfaceVariantSummary, card),
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = page,
        surfaceContainerLow = lerp(page, card, 0.5f),
        surfaceContainer = card,
        surfaceContainerHigh = c.surfaceContainerHigh,
        surfaceContainerHighest = c.surfaceContainerHighest,
        surfaceBright = c.surfaceContainerHighest,
        surfaceDim = page,
        outline = c.outline,
        outlineVariant = c.dividerLine,
        inverseSurface = opaque(c.onSurface, page),
        inverseOnSurface = page,
        scrim = Color.Black,
    )
}

val MaterialTheme.extras: AtomExtras
    @Composable get() = LocalAtomExtras.current

fun isDark(mode: ThemeMode, systemDark: Boolean) = when (mode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> systemDark
}
