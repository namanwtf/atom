package atom.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import atom.app.data.ThemeMode

/** Electric blue: the one accent the app is recognised by. */
val AtomBlue = Color(0xFF4F8BFF)

private val Dark = darkColorScheme(
    primary = AtomBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF16264A),
    onPrimaryContainer = Color(0xFFD9E3FF),
    secondary = Color(0xFF9DB6E8),
    onSecondary = Color(0xFF0B1A33),
    secondaryContainer = Color(0xFF1C2638),
    onSecondaryContainer = Color(0xFFD6E1F7),
    tertiary = Color(0xFF5FD4B0),
    onTertiary = Color(0xFF00382A),
    tertiaryContainer = Color(0xFF0F3B30),
    onTertiaryContainer = Color(0xFFB4F1DC),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0C11),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF0A0C11),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFF9AA1B0),
    surfaceContainerLowest = Color(0xFF07090D),
    surfaceContainerLow = Color(0xFF101319),
    surfaceContainer = Color(0xFF141821),
    surfaceContainerHigh = Color(0xFF1A1F29),
    surfaceContainerHighest = Color(0xFF222834),
    outline = Color(0xFF3A4150),
    outlineVariant = Color(0xFF262C37),
    inverseSurface = Color(0xFFE6E8EE),
    inverseOnSurface = Color(0xFF14171D),
    inversePrimary = Color(0xFF2E62D6),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2B66E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0B2257),
    secondary = Color(0xFF4F5E7D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9F6),
    onSecondaryContainer = Color(0xFF17233A),
    tertiary = Color(0xFF117A5E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC9F2E3),
    onTertiaryContainer = Color(0xFF00382A),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF14171D),
    surface = Color(0xFFF7F8FB),
    onSurface = Color(0xFF14171D),
    surfaceVariant = Color(0xFFE3E6ED),
    onSurfaceVariant = Color(0xFF5A6170),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F3F8),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E9F0),
    surfaceContainerHighest = Color(0xFFE0E3EB),
    outline = Color(0xFFB4BAC6),
    outlineVariant = Color(0xFFD5D9E1),
)

private val AtomTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    )
}

private val AtomShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Extra colours the Material scheme has no slot for. */
@Immutable
data class AtomExtras(val success: Color, val warning: Color, val mono: TextStyle)

val LocalAtomExtras = staticCompositionLocalOf {
    AtomExtras(Color(0xFF5FD4B0), Color(0xFFFFB547), TextStyle(fontFamily = FontFamily.Monospace))
}

@Composable
fun AtomTheme(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val scheme: ColorScheme = if (dark) Dark else Light
    val extras = AtomExtras(
        success = if (dark) Color(0xFF5FD4B0) else Color(0xFF117A5E),
        warning = if (dark) Color(0xFFFFB547) else Color(0xFFB26A00),
        mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalAtomExtras provides extras) {
        MaterialTheme(colorScheme = scheme, typography = AtomTypography, shapes = AtomShapes, content = content)
    }
}

val MaterialTheme.extras: AtomExtras
    @Composable get() = LocalAtomExtras.current

fun isDark(mode: ThemeMode, systemDark: Boolean) = when (mode) {
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
    ThemeMode.System -> systemDark
}
