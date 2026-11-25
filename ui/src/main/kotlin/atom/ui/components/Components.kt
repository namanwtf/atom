package atom.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import atom.ui.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

// ------------------------------------------------------------------ Screen scaffolding

/** Side margin of cards on list pages; group titles sit 16dp further in (28dp from the screen edge). */
val ListSideMargin: Dp = 12.dp

/**
 * A secondary screen in the Miuix style: back chevron, a large title that collapses into the small bar as the
 * list scrolls, Miuix rubber-band overscroll with a scroll-end haptic, and a list body with 12dp side margins.
 */
@Composable
fun LargeTitleScreen(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val behavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = title,
                color = MiuixTheme.colorScheme.surface,
                scrollBehavior = behavior,
                navigationIcon = { if (onBack != null) BackButton(onBack) },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical(onOffsetChanged = { behavior.state.overscrollOffset = it })
                .nestedScroll(behavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + ListSideMargin,
                end = padding.calculateEndPadding(layoutDirection) + ListSideMargin,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            overscrollEffect = null,
            content = content,
        )
    }
}

/** The Miuix back chevron for top bars (mirrored in right-to-left layouts). */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = MiuixIcons.ChevronBackward,
            contentDescription = stringResource(R.string.cd_back),
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer { if (rtl) scaleX = -1f },
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** Round icon button on a filled disc (Miuix icon button with a container colour). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
        backgroundColor = container,
        minWidth = size,
        minHeight = size,
        cornerRadius = size,
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.onSurface)
    }
}

// ------------------------------------------------------------------ Grouped lists

/** Miuix small group title: blue-grey, 28dp from the screen edge on list pages. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = text,
        modifier = modifier,
        insideMargin = PaddingValues(start = 28.dp - ListSideMargin, end = 28.dp - ListSideMargin, top = 8.dp, bottom = 8.dp),
    )
}

/** A Miuix card holding rows, with the 12dp gap below it. Rows stack without dividers. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp), content = content)
}

/** Kept for source compatibility: Miuix cards stack rows without dividers, so this draws nothing. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun GroupDivider(inset: Dp = 58.dp) = Unit

/**
 * One row in a [Group] with the Miuix preference anatomy: optional 24dp monochrome icon, title, optional
 * summary, optional trailing content (value text, chevron, switch...). Clickable when [onClick] is set.
 * [titleColor] defaults to the Miuix row title colour.
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val end: (@Composable RowScope.() -> Unit)? = if (trailing != null) {
        { Box(Modifier.align(Alignment.CenterVertically)) { trailing() } }
    } else {
        null
    }
    AtomRow(
        title = title,
        modifier = modifier,
        icon = icon,
        summary = subtitle,
        titleColor = titleColor,
        enabled = enabled,
        onClick = onClick,
        end = end,
    )
}

/**
 * The shared row layout (Miuix BasicComponent): 16dp padding, optional 24dp monochrome icon, a 17sp regular
 * title, an optional 14sp grey summary and optional [end] content. Every row wrapper in this package uses it,
 * so all rows look the same.
 */
@Composable
fun AtomRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    titleColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    role: Role? = null,
    holdDown: Boolean = false,
    end: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = MiuixTheme.colorScheme
    val start: (@Composable () -> Unit)? = if (icon != null) {
        { RowIcon(icon, enabled) }
    } else {
        null
    }
    BasicComponent(
        modifier = modifier,
        startAction = start,
        endActions = end,
        onClick = onClick,
        role = role,
        holdDownState = holdDown,
        enabled = enabled,
    ) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Normal,
            color = when {
                !enabled -> c.disabledOnSecondaryVariant
                titleColor.isSpecified -> titleColor
                else -> c.onBackground
            },
        )
        if (summary != null) {
            Text(
                text = summary,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = if (enabled) c.onSurfaceVariantSummary else c.disabledOnSecondaryVariant,
            )
        }
    }
}

/** 24dp monochrome start icon of a row, with the Miuix 8dp gap after it. */
@Composable
fun RowIcon(icon: ImageVector, enabled: Boolean = true) {
    val c = MiuixTheme.colorScheme
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 8.dp).size(24.dp),
        tint = if (enabled) c.onBackground else c.disabledOnSecondaryVariant,
    )
}

/** The Miuix row arrow (">" in the actions grey). */
@Composable
fun RowChevron() {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Image(
        imageVector = MiuixIcons.Basic.ArrowRight,
        contentDescription = null,
        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantActions),
        modifier = Modifier
            .padding(start = 8.dp)
            .size(width = 10.dp, height = 16.dp)
            .graphicsLayer { if (rtl) scaleX = -1f },
    )
}

/** End value text of a row (body2, actions grey). */
@Composable
fun RowValue(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Miuix switch: primary (blue) track when on, grey when off. */
@Composable
fun AtomSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
}

// ------------------------------------------------------------------ Buttons

/** Full-width Miuix primary text button (accent fill, white text): the one primary action on a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColorsPrimary(),
    )
}

/** Miuix secondary text button (grey fill). Wraps its content unless given a width. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
}

/** Pinned bottom area for a screen's primary action, over the page colour. */
@Composable
fun BottomActionBar(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}

// ------------------------------------------------------------------ Small pieces

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) = SectionLabel(text, modifier)

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, contentColor: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = MiuixTheme.textStyles.footnote2, color = contentColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(color, CircleShape))
}

/** Standalone Miuix card with padding (for content that is not a list of rows). */
@Composable
fun AtomCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun StatusLine(dot: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(dot)
        Text(text = text, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
    }
}

/** Vertical gap helper for list pages. */
@Composable
fun ListGap(height: Dp = 12.dp) = Spacer(Modifier.height(height))
