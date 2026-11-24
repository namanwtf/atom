@file:OptIn(ExperimentalMaterial3Api::class)

package atom.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Graphite popup menu with rounded corners, matching the grouped lists. */
@Composable
fun AtomMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(16.dp, 0.dp),
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) { content() }
}

@Composable
fun AtomMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = if (checked) {
            { Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface) }
        } else {
            null
        },
        onClick = onClick,
    )
}

/**
 * Graphite track with a white pill that slides to the selected segment. Used for the Models tabs.
 */
@Composable
fun SegmentedControl(
    options: List<Pair<String, ImageVector?>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(c.surfaceContainerHigh)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * selected, tween(220), label = "segment")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(c.primary),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, (label, icon) ->
                val active = i == selected
                val tint by animateColorAsState(if (active) c.onPrimary else c.onSurfaceVariant, tween(200), label = "segmentText")
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Icon(icon, null, Modifier.size(16.dp), tint = tint)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tint)
                }
            }
        }
    }
}

/** Compact pill button for actions inside a group or card: white when [primary], graphite otherwise. */
@Composable
fun CompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color? = null,
) {
    val c = MaterialTheme.colorScheme
    val container = when {
        !enabled -> c.surfaceContainerHigh
        primary -> c.primary
        else -> c.surfaceContainerHighest
    }
    val content = when {
        !enabled -> c.onSurfaceVariant
        contentColor != null -> contentColor
        primary -> c.onPrimary
        else -> c.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Glyphs these screens need beyond AtomIcons (Material Design paths, Apache-2.0). */
object ScreenIcons {
    val Shield: ImageVector by lazy {
        icon("Shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94V12H5V6.3l7,-3.11v8.8z")
    }
    val Storage: ImageVector by lazy {
        icon("Storage", "M2,20h20v-4H2v4zM4,17h2v2H4v-2zM2,4v4h20V4H2zM6,7H4V5h2v2zM2,14h20v-4H2v4zM4,11h2v2H4v-2z")
    }
    val Document: ImageVector by lazy {
        icon(
            "Document",
            "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z",
        )
    }
    val Contrast: ImageVector by lazy {
        icon(
            "Contrast",
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36c-0.98,1.37 -2.58,2.26 -4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3L12,3z",
        )
    }
    val Key: ImageVector by lazy {
        icon(
            "Key",
            "M12.65,10C11.83,7.67 9.61,6 7,6c-3.31,0 -6,2.69 -6,6s2.69,6 6,6c2.61,0 4.83,-1.67 5.65,-4H17v4h4v-4h2v-4H12.65zM7,14c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z",
        )
    }
    val Tune: ImageVector by lazy {
        icon("Tune", "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z")
    }
    val ChevronDown: ImageVector by lazy { icon("ChevronDown", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z") }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
}
