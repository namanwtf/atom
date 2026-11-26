package atom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Accent used when Monet colours are on. [System] follows the wallpaper; the fixed accents seed the Monet
 * palette with their colour. [Monochrome] is Atom's own white-on-black (black-on-light) house style, kept as
 * an extra choice. With Monet off the accent is ignored and the stock Miuix blue is used.
 *
 * [seed] is the key colour (ARGB) for fixed accents; null for [System] and [Monochrome].
 */
enum class AccentColor(val seed: Long?) {
    System(null),
    Blue(0xFF4285F4),
    Purple(0xFF7C4DFF),
    Pink(0xFFE91E63),
    Red(0xFFEA4335),
    Orange(0xFFFF5722),
    Yellow(0xFFFBBC04),
    Green(0xFF34A853),
    Teal(0xFF00BCD4),
    Monochrome(null),
}

/** How a Monet palette is derived from its key colour, in menu order. Mapped 1:1 onto the Miuix palette styles. */
enum class ColorStyle { TonalSpot, Neutral, Vibrant, Expressive, Rainbow, FruitSalad, Monochrome, Fidelity, Content, Cmf }

/** Component family the app is drawn with. Only Miuix exists today; another style may follow. */
enum class UiStyle { Miuix }

/**
 * App-wide text size, applied on top of the system font size. [Default] keeps the library type scale as
 * designed (17sp row titles, 14sp summaries).
 */
enum class TextSize(val scale: Float) { XSmall(0.80f), Small(0.88f), Default(1.0f), Large(1.12f), XLarge(1.25f) }

/**
 * Look of the frosted band behind top bars (and the chat composer). [Gradient]: a strong band of the page colour
 * over a light blur. [Progressive]: a stronger blur under a thinner tint. See atom.ui.components.glassBand.
 */
enum class TopBarBlur { Gradient, Progressive }

/**
 * Every appearance option in one value. The defaults are the Atom look: follow the system theme, stock Miuix
 * colours (Monet off), pure black in dark mode, blur and both back gestures on.
 *
 * - [monet]: derive the colours from [accent] / the wallpaper in [colorStyle]. Off = stock Miuix blue.
 * - [blur]: glass bars blur what scrolls under them. Off = a plain translucent tint (cheaper on the GPU).
 * - [topBarBlur]: band-first or blur-first look of the frosted bars; ignored when [blur] is off.
 * - [progressiveBlur]: the blur radius ramps down across a bar's fading edge; off, a uniform blur is faded with a
 *   mask (cheaper). Ignored when [blur] is off.
 * - [predictiveBack]: the app animates screen pops with the back gesture's progress when on.
 * - [swipeBackAnywhere]: a horizontal swipe starting anywhere on a secondary screen goes back
 *   (see the [swipeBackAnywhere] modifier).
 *
 * New fields are added at the end so positional construction keeps working.
 */
@Immutable
data class Appearance(
    val mode: ThemeMode = ThemeMode.System,
    val pureBlack: Boolean = true,
    val accent: AccentColor = AccentColor.System,
    val colorStyle: ColorStyle = ColorStyle.TonalSpot,
    val blur: Boolean = true,
    val topBarBlur: TopBarBlur = TopBarBlur.Gradient,
    val progressiveBlur: Boolean = true,
    val predictiveBack: Boolean = true,
    val swipeBackAnywhere: Boolean = true,
    val textSize: TextSize = TextSize.Default,
    val monet: Boolean = false,
    val uiStyle: UiStyle = UiStyle.Miuix,
)

/** The appearance [AtomTheme] was given. Components read flags (blur, gestures) from here. */
val LocalAppearance = staticCompositionLocalOf { Appearance() }

val MaterialTheme.appearance: Appearance
    @Composable get() = LocalAppearance.current

/** True when glass components should blur; false means a plain translucent tint. */
@Composable
fun glassBlurEnabled(): Boolean = LocalAppearance.current.blur
