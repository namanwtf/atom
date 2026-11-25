package atom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Accent applied to primary actions, switches and selection. Monochrome (white on black, black on light) is the
 * default and the house style; the others are muted, low-chroma tones tuned separately for dark and light.
 * [System] follows the wallpaper (Monet) on Android 12+ and falls back to Monochrome below that.
 */
enum class AccentColor { Monochrome, Silver, Ember, Jade, Iris, Rose, System }

/** How the system (Monet) palette is turned into a scheme. Only used when the accent is [AccentColor.System]. */
enum class ColorStyle { TonalSpot, Neutral, Vibrant, Expressive, Monochrome }

/**
 * App-wide text size, applied on top of the system font size. [Small] is the default: Atom's type is
 * designed compact, so dense screens (settings, chips, cards) fit without truncation.
 */
enum class TextSize(val scale: Float) { XSmall(0.80f), Small(0.88f), Default(1.0f), Large(1.12f), XLarge(1.25f) }

/** Look of the blurred band behind top bars. */
enum class TopBarBlur { Gradient, Progressive }

/**
 * Every appearance option in one value. The defaults reproduce the original look exactly: dark, pure black,
 * monochrome, blur on.
 *
 * - [blur]: glass bars blur what scrolls under them. Off = a plain translucent tint (cheaper on the GPU).
 * - [topBarBlur] / [progressiveBlur]: style hints for top-bar glass; ignored when [blur] is off.
 * - [predictiveBack]: the app animates screen pops with the back gesture's progress when on.
 * - [swipeBackAnywhere]: a rightward swipe starting anywhere on a secondary screen goes back
 *   (see the [swipeBackAnywhere] modifier).
 */
@Immutable
data class Appearance(
    val mode: ThemeMode = ThemeMode.Dark,
    val pureBlack: Boolean = true,
    val accent: AccentColor = AccentColor.Monochrome,
    val colorStyle: ColorStyle = ColorStyle.TonalSpot,
    val blur: Boolean = true,
    val topBarBlur: TopBarBlur = TopBarBlur.Gradient,
    val progressiveBlur: Boolean = true,
    val predictiveBack: Boolean = true,
    val swipeBackAnywhere: Boolean = false,
    val textSize: TextSize = TextSize.Small,
)

/** The appearance [AtomTheme] was given. Components read flags (blur, gestures) from here. */
val LocalAppearance = staticCompositionLocalOf { Appearance() }

val MaterialTheme.appearance: Appearance
    @Composable get() = LocalAppearance.current

/** True when glass components should blur; false means a plain translucent tint. */
@Composable
fun glassBlurEnabled(): Boolean = LocalAppearance.current.blur
