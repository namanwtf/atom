package atom.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One glass style for every frosted surface (chat top bar, composer band, side pane): the same blur radius and the
 * same tint strength per theme. With blur unavailable (Android 11, Appearance > Blur off, battery saver, low-RAM
 * devices) the same surfaces use [fallbackAlpha] of the same colour: a translucent tint, never a different look.
 */
object AtomGlass {
    /** Blur radius of every glass surface. */
    val BlurRadius: Dp = 24.dp

    /** No grain: the old app's frosted surfaces were smooth. */
    const val Noise: Float = 0f

    /** Hairline edge (outline variant at this alpha) where a glass surface meets the content. */
    const val EdgeAlpha: Float = 0.42f

    /** Tint over the blur: enough to keep text on the glass legible, little enough to see what is behind. */
    fun tintAlpha(color: Color): Float = if (color.isDark()) 0.74f else 0.66f

    /** The same tint without blur behind it. */
    fun fallbackAlpha(color: Color): Float = if (color.isDark()) 0.96f else 0.95f

    private fun Color.isDark(): Boolean = luminance() < 0.5f
}
