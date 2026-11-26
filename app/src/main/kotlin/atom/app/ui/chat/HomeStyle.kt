package atom.app.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The home screen's colour recipes: "ink" is onSurface at a low alpha and "accent" is primary at a low alpha, each
 * with one value for dark and one for light backgrounds. Everything derives from the current colour scheme, so the
 * accent and the pure-black background come from the theme.
 */
@Immutable
internal class HomeInk(val dark: Boolean, private val ink: Color, private val accentColor: Color, private val base: Color) {
    fun ink(dark: Float, light: Float): Color = ink.copy(alpha = if (this.dark) dark else light)
    fun accent(dark: Float, light: Float): Color = accentColor.copy(alpha = if (this.dark) dark else light)

    /** [ink] composited over the page, for opaque surfaces that sit over scrolling content. */
    fun solidInk(dark: Float, light: Float): Color = ink(dark, light).compositeOver(base)

    // Recipes used in several places.
    val muted: Color get() = ink(0.56f, 0.52f)
    val chevron: Color get() = ink(0.46f, 0.42f)
    val cardFill: Color get() = ink(0.055f, 0.028f)
    val cardBorder: Color get() = ink(0.10f, 0.065f)
    val glassButton: Color get() = solidInk(0.095f, 0.05f)
    val glassEdge: Color get() = ink(0.12f, 0.07f)
    val capsule: Color get() = solidInk(0.15f, 0.06f)
}

@Composable
internal fun homeInk(): HomeInk {
    val c = MaterialTheme.colorScheme
    return HomeInk(c.background.luminance() < 0.5f, c.onSurface, c.primary, c.background)
}

/** A round "glass" button for the home top bar and the drawer: an opaque lifted disc with a faint edge. */
@Composable
internal fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
) {
    val ink = homeInk()
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = ink.glassButton,
        modifier = modifier.size(size).border(0.5.dp, ink.glassEdge, CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Line glyphs for the home screen, drawn on a 24 grid with round caps. */
internal object HomeGlyphs {
    /** A window with a side pane: opens the conversation list. */
    val Sidebar: ImageVector by lazy {
        stroke("Sidebar", "M6,4h12a3,3 0,0 1,3,3v10a3,3 0,0 1,-3,3H6a3,3 0,0 1,-3,-3V7a3,3 0,0 1,3,-3zM9.5,4v16M5.8,8h1.2M5.8,11h1.2", 1.7f)
    }

    /** A page with a pencil: a new conversation. */
    val NewMessage: ImageVector by lazy {
        stroke("NewMessage", "M11,4.5H7a3,3 0,0 0,-3,3v9.5a3,3 0,0 0,3,3h9.5a3,3 0,0 0,3,-3v-4M18,3.6l2.4,2.4l-8.2,8.2l-3.2,0.8l0.8,-3.2z", 1.7f)
    }

    val Lightbulb: ImageVector by lazy {
        stroke("Lightbulb", "M9,18h6M10,21h4M12,3a6,6 0,0 0,-3.6,10.8c0.6,0.5 1.1,1.3 1.1,2.2h5c0,-0.9 0.5,-1.7 1.1,-2.2A6,6 0,0 0,12 3z", 1.7f)
    }

    val Checklist: ImageVector by lazy {
        stroke("Checklist", "M4,6.5l1.5,1.5l3,-3M4,13.5l1.5,1.5l3,-3M11.5,7h8.5M11.5,14h8.5M11.5,19.5h8.5", 1.7f)
    }

    /** Thin line set for the answer actions (16 grid scaled to 24). */
    val Copy: ImageVector by lazy { stroke("CopyLine", "M9,8.5h9a1.5,1.5 0,0 1,1.5,1.5v9a1.5,1.5 0,0 1,-1.5,1.5H9a1.5,1.5 0,0 1,-1.5,-1.5v-9A1.5,1.5 0,0 1,9 8.5zM16.5,5.5V5a1.5,1.5 0,0 0,-1.5,-1.5H6A1.5,1.5 0,0 0,4.5 5v9a1.5,1.5 0,0 0,1.5,1.5h0.5", 1.6f) }
    val Check: ImageVector by lazy { stroke("CheckLine", "M5,12.5l4.5,4.5L19,7.5", 1.8f) }
    val Volume: ImageVector by lazy { stroke("VolumeLine", "M4.5,9.5h3l4.5,-4v13l-4.5,-4h-3zM15.5,9a4,4 0,0 1,0 6M18,6.5a7.5,7.5 0,0 1,0 11", 1.6f) }
    val StopLine: ImageVector by lazy { stroke("StopLine", "M7.5,6h9a1.5,1.5 0,0 1,1.5,1.5v9a1.5,1.5 0,0 1,-1.5,1.5h-9A1.5,1.5 0,0 1,6 16.5v-9A1.5,1.5 0,0 1,7.5 6z", 1.6f) }
    val Refresh: ImageVector by lazy { stroke("RefreshLine", "M19.5,12a7.5,7.5 0,1 1,-2.2 -5.3M19.5,4.5v4h-4", 1.6f) }
    val Trash: ImageVector by lazy { stroke("TrashLine", "M4.5,7h15M9.5,7V5h5v2M6.5,7l0.8,12a1.5,1.5 0,0 0,1.5 1.4h6.4a1.5,1.5 0,0 0,1.5 -1.4L17.5,7M10.5,11v5.5M13.5,11v5.5", 1.6f) }
    val Branch: ImageVector by lazy { stroke("BranchLine", "M7,4.5v15M7,14.5c0,-3.5 10,-2.5 10,-7M17,7.5a2,2 0,1 0,0 -0.01M7,19.5a2,2 0,1 0,0 -0.01", 1.6f) }
    val EditLine: ImageVector by lazy { stroke("EditLine", "M15.5,5l3.5,3.5l-10,10l-4.5,1l1,-4.5zM13.5,7l3.5,3.5", 1.6f) }
    val Gauge: ImageVector by lazy { stroke("GaugeLine", "M4.5,16a7.5,7.5 0,1 1,15 0M12,16l3.5,-4.5", 1.6f) }

    private fun stroke(name: String, path: String, width: Float): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .build()
}
