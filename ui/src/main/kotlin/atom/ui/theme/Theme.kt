package atom.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Monochrome, matched to the silver emblem. White is the only "accent": primary actions are white with black
 * text, everything else is graphite on pure black. Colour is reserved for meaning (success, warning, error).
 */

private val Dark = darkColorScheme(
    primary = Color(0xFFF4F4F6),
    onPrimary = Color(0xFF0A0A0B),
    primaryContainer = Color(0xFF26262A),
    onPrimaryContainer = Color(0xFFF4F4F6),
    secondary = Color(0xFFB8B8C0),
    onSecondary = Color(0xFF0A0A0B),
    secondaryContainer = Color(0xFF1E1E22),
    onSecondaryContainer = Color(0xFFE8E8EC),
    tertiary = Color(0xFF7FD6AE),
    onTertiary = Color(0xFF00281A),
    tertiaryContainer = Color(0xFF102A20),
    onTertiaryContainer = Color(0xFFB8F0D4),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF2B0505),
    errorContainer = Color(0xFF2C1312),
    onErrorContainer = Color(0xFFFFD9D5),
    // Pitch black: every #000000 pixel on OLED is off. Containers are graphite, just enough to separate.
    background = Color.Black,
    onBackground = Color(0xFFF4F4F6),
    surface = Color.Black,
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF1A1A1D),
    onSurfaceVariant = Color(0xFF8E8E97),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1B1B1E),
    surfaceContainerHighest = Color(0xFF242428),
    surfaceBright = Color(0xFF2C2C31),
    surfaceDim = Color.Black,
    outline = Color(0xFF3A3A41),
    outlineVariant = Color(0xFF1F1F23),
    inverseSurface = Color(0xFFF4F4F6),
    inverseOnSurface = Color(0xFF121214),
    inversePrimary = Color(0xFF2A2A2E),
    scrim = Color.Black,
)

private val Light = lightColorScheme(
    primary = Color(0xFF111113),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6EA),
    onPrimaryContainer = Color(0xFF111113),
    secondary = Color(0xFF55555C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E9ED),
    onSecondaryContainer = Color(0xFF1B1B1E),
    tertiary = Color(0xFF117A55),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEFDF),
    onTertiaryContainer = Color(0xFF00281A),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F6F8),
    onBackground = Color(0xFF111113),
    surface = Color(0xFFF6F6F8),
    onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFE6E6EA),
    onSurfaceVariant = Color(0xFF64646C),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F1F4),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDEDF0),
    surfaceContainerHighest = Color(0xFFE5E5E9),
    outline = Color(0xFFB9B9C1),
    outlineVariant = Color(0xFFE2E2E7),
)

private val AtomTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Normal, fontSize = 34.sp, letterSpacing = (-0.6).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    )
}

private val AtomShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

enum class ThemeMode { Dark, Light, System }

/** Extra colours the Material scheme has no slot for. */
@Immutable
data class AtomExtras(val success: Color, val warning: Color, val mono: TextStyle)

val LocalAtomExtras = staticCompositionLocalOf {
    AtomExtras(Color(0xFF7FD6AE), Color(0xFFF2B65A), TextStyle(fontFamily = FontFamily.Monospace))
}

/** Original call shape: mode only, every other appearance option at its default. */
@Composable
fun AtomTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) =
    AtomTheme(appearance = Appearance(mode = mode), content = content)

/**
 * Full appearance: mode, pure black, accent / Monet and style, plus the flags components read through
 * [LocalAppearance] (blur, gestures). `AtomTheme(Appearance())` looks exactly like `AtomTheme()`.
 */
@Composable
fun AtomTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = isDark(appearance.mode, isSystemInDarkTheme())
    val context = LocalContext.current
    val scheme: ColorScheme = remember(appearance.accent, appearance.colorStyle, appearance.pureBlack, dark) {
        atomColorScheme(context, appearance, dark)
    }
    val extras = AtomExtras(
        success = if (dark) Color(0xFF7FD6AE) else Color(0xFF117A55),
        warning = if (dark) Color(0xFFF2B65A) else Color(0xFFA86400),
        mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
    )
    val density = LocalDensity.current
    val scaled = remember(density, appearance.textSize) { Density(density.density, density.fontScale * appearance.textSize.scale) }
    CompositionLocalProvider(LocalAtomExtras provides extras, LocalAppearance provides appearance, LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, typography = AtomTypography, shapes = AtomShapes, content = content)
    }
}

/** Dark background when pure black is off: graphite, with containers lifted to keep the same separation. */
private val GraphiteDark = Dark.copy(
    background = Color(AtomPalettes.Graphite),
    surface = Color(AtomPalettes.Graphite),
    surfaceContainerLowest = Color(AtomPalettes.Graphite),
    surfaceContainerLow = Color(0xFF141417),
    surfaceContainer = Color(0xFF19191C),
    surfaceContainerHigh = Color(0xFF212125),
    surfaceContainerHighest = Color(0xFF2A2A2F),
    surfaceVariant = Color(0xFF1F1F23),
    surfaceBright = Color(0xFF323238),
    surfaceDim = Color(AtomPalettes.Graphite),
    outlineVariant = Color(0xFF26262B),
    inverseOnSurface = Color(0xFF19191C),
)

/** Builds the colour scheme for [appearance]; defaults return the original monochrome schemes untouched. */
fun atomColorScheme(context: Context, appearance: Appearance, dark: Boolean): ColorScheme {
    if (appearance.accent == AccentColor.System && supportsDynamicColor) {
        val dyn = dynamicScheme(context, dark, appearance.colorStyle)
        return if (dark) dyn.withDarkBackground(appearance.pureBlack) else dyn
    }
    val base = when {
        !dark -> Light
        appearance.pureBlack -> Dark
        else -> GraphiteDark
    }
    val palette = AtomPalettes.of(appearance.accent) ?: AtomPalettes.Monochrome
    if (palette === AtomPalettes.Monochrome) return base
    val t = if (dark) palette.dark else palette.light
    return base.copy(
        primary = Color(t.primary),
        onPrimary = Color(t.onPrimary),
        primaryContainer = Color(t.primaryContainer),
        onPrimaryContainer = Color(t.onPrimaryContainer),
        inversePrimary = Color(if (dark) palette.light.primary else palette.dark.primary),
    )
}

/** Monet surfaces, with the background forced to true black (or graphite) to keep the house look. */
private fun ColorScheme.withDarkBackground(pureBlack: Boolean): ColorScheme {
    val bg = Color(if (pureBlack) AtomPalettes.PureBlack else AtomPalettes.Graphite)
    return copy(background = bg, surface = bg, surfaceContainerLowest = bg, surfaceDim = bg)
}

val MaterialTheme.extras: AtomExtras
    @Composable get() = LocalAtomExtras.current

fun isDark(mode: ThemeMode, systemDark: Boolean) = when (mode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> systemDark
}
