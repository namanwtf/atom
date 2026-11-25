package atom.general.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.general.R
import atom.general.appearance.AppearanceStore
import atom.ui.components.AtomSwitch
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.feature.FeatureNav
import atom.ui.theme.AccentColor
import atom.ui.theme.Appearance
import atom.ui.theme.AtomPalettes
import atom.ui.theme.AtomTheme
import atom.ui.theme.ColorStyle
import atom.ui.theme.ThemeMode
import atom.ui.theme.TopBarBlur
import atom.ui.theme.atomColorScheme
import atom.ui.theme.isDark
import atom.ui.theme.supportsDynamicColor
import kotlinx.coroutines.launch

private val MODES = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark)

@Composable
internal fun AppearanceScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val store = remember { AppearanceStore.get(context) }
    val saved by store.appearance.collectAsStateWithLifecycle(initialValue = null)
    val a = saved ?: Appearance()
    val scope = rememberCoroutineScope()
    fun edit(t: (Appearance) -> Appearance) {
        scope.launch { store.update(t) }
    }
    val dark = isDark(a.mode, isSystemInDarkTheme())
    val accents = remember { AccentColor.entries.filter { it != AccentColor.System || supportsDynamicColor } }

    LargeTitleScreen(title = stringResource(R.string.appearance_title), onBack = nav::back) {
        item(key = "preview") {
            // The preview always renders the chosen appearance, even before the app theme catches up.
            AtomTheme(appearance = a) { AppearancePreview() }
        }

        item(key = "l-theme") { SectionLabel(stringResource(R.string.appearance_section_theme)) }
        item(key = "g-theme") {
            Group {
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.appearance_mode_system) to null,
                        stringResource(R.string.appearance_mode_light) to null,
                        stringResource(R.string.appearance_mode_dark) to null,
                    ),
                    selected = MODES.indexOf(a.mode).coerceAtLeast(0),
                    onSelect = { i -> edit { it.copy(mode = MODES[i]) } },
                    modifier = Modifier.padding(12.dp),
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.appearance_pure_black),
                    subtitle = stringResource(R.string.appearance_pure_black_sub),
                    enabled = a.mode != ThemeMode.Light,
                    onClick = { edit { it.copy(pureBlack = !it.pureBlack) } },
                    trailing = {
                        AtomSwitch(a.pureBlack, { v -> edit { it.copy(pureBlack = v) } }, enabled = a.mode != ThemeMode.Light)
                    },
                )
            }
        }

        item(key = "l-accent") { SectionLabel(stringResource(R.string.appearance_section_accent)) }
        item(key = "g-accent") {
            Group {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    accents.forEach { accent ->
                        AccentSwatch(
                            accent = accent,
                            dark = dark,
                            selected = a.accent == accent,
                            onClick = { edit { it.copy(accent = accent) } },
                        )
                    }
                }
                if (a.accent == AccentColor.System && supportsDynamicColor) {
                    GroupDivider(inset = 18.dp)
                    ChoiceRow(
                        title = stringResource(R.string.appearance_color_style),
                        subtitle = stringResource(R.string.appearance_color_style_sub),
                        value = stringResource(styleLabel(a.colorStyle)),
                        options = ColorStyle.entries.map { it to stringResource(styleLabel(it)) },
                        selected = a.colorStyle,
                        onSelect = { s -> edit { it.copy(colorStyle = s) } },
                    )
                }
            }
            if (!supportsDynamicColor) FootNote(stringResource(R.string.appearance_monet_unavailable))
        }

        item(key = "l-effects") { SectionLabel(stringResource(R.string.appearance_section_effects)) }
        item(key = "g-effects") {
            Group {
                GroupRow(
                    title = stringResource(R.string.appearance_blur),
                    subtitle = stringResource(R.string.appearance_blur_sub),
                    onClick = { edit { it.copy(blur = !it.blur) } },
                    trailing = { AtomSwitch(a.blur, { v -> edit { it.copy(blur = v) } }) },
                )
                GroupDivider(inset = 18.dp)
                ChoiceRow(
                    title = stringResource(R.string.appearance_top_bar_blur),
                    value = stringResource(topBarLabel(a.topBarBlur)),
                    options = TopBarBlur.entries.map { it to stringResource(topBarLabel(it)) },
                    selected = a.topBarBlur,
                    onSelect = { s -> edit { it.copy(topBarBlur = s) } },
                    enabled = a.blur,
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.appearance_progressive_blur),
                    subtitle = stringResource(R.string.appearance_progressive_blur_sub),
                    enabled = a.blur,
                    onClick = { edit { it.copy(progressiveBlur = !it.progressiveBlur) } },
                    trailing = {
                        AtomSwitch(a.progressiveBlur, { v -> edit { it.copy(progressiveBlur = v) } }, enabled = a.blur)
                    },
                )
            }
        }

        item(key = "l-nav") { SectionLabel(stringResource(R.string.appearance_section_navigation)) }
        item(key = "g-nav") {
            Group {
                GroupRow(
                    title = stringResource(R.string.appearance_predictive_back),
                    subtitle = stringResource(R.string.appearance_predictive_back_sub),
                    onClick = { edit { it.copy(predictiveBack = !it.predictiveBack) } },
                    trailing = { AtomSwitch(a.predictiveBack, { v -> edit { it.copy(predictiveBack = v) } }) },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.appearance_swipe_back),
                    subtitle = stringResource(R.string.appearance_swipe_back_sub),
                    onClick = { edit { it.copy(swipeBackAnywhere = !it.swipeBackAnywhere) } },
                    trailing = { AtomSwitch(a.swipeBackAnywhere, { v -> edit { it.copy(swipeBackAnywhere = v) } }) },
                )
            }
        }
    }
}

/** A miniature chat screen drawn with the current theme: background, bubble, reply, switch and primary action. */
@Composable
private fun AppearancePreview() {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.background)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(26.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(c.primary, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.about_app_name), style = MaterialTheme.typography.titleMedium, color = c.onBackground)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                stringResource(R.string.appearance_preview_user),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurface,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Text(stringResource(R.string.appearance_preview_reply), style = MaterialTheme.typography.bodyMedium, color = c.onBackground)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(c.surfaceContainer)
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.appearance_preview_switch),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurface,
                modifier = Modifier.weight(1f),
            )
            AtomSwitch(checked = true, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            CompactButton(stringResource(R.string.appearance_preview_action), onClick = {})
        }
    }
}

@Composable
private fun AccentSwatch(accent: AccentColor, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val fill: Brush = remember(accent, dark) {
        val palette = AtomPalettes.of(accent)
        if (palette != null) {
            SolidColor(Color((if (dark) palette.dark else palette.light).primary))
        } else {
            val s = atomColorScheme(context, Appearance(accent = AccentColor.System, mode = if (dark) ThemeMode.Dark else ThemeMode.Light), dark)
            Brush.sweepGradient(listOf(s.primary, s.tertiary, s.secondary, s.primary))
        }
    }
    val label = stringResource(accentLabel(accent))
    Column(
        Modifier
            .width(62.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .border(2.dp, if (selected) c.onSurface else Color.Transparent, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.dp, c.outline.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                val onFill = when (val p = AtomPalettes.of(accent)) {
                    null -> Color.White
                    else -> Color((if (dark) p.dark else p.light).onPrimary)
                }
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = onFill)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.onSurface else c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun accentLabel(a: AccentColor) = when (a) {
    AccentColor.Monochrome -> R.string.accent_monochrome
    AccentColor.Silver -> R.string.accent_silver
    AccentColor.Ember -> R.string.accent_ember
    AccentColor.Jade -> R.string.accent_jade
    AccentColor.Iris -> R.string.accent_iris
    AccentColor.Rose -> R.string.accent_rose
    AccentColor.System -> R.string.accent_system
}

private fun styleLabel(s: ColorStyle) = when (s) {
    ColorStyle.TonalSpot -> R.string.style_tonal_spot
    ColorStyle.Neutral -> R.string.style_neutral
    ColorStyle.Vibrant -> R.string.style_vibrant
    ColorStyle.Expressive -> R.string.style_expressive
    ColorStyle.Monochrome -> R.string.style_monochrome
}

private fun topBarLabel(t: TopBarBlur) = when (t) {
    TopBarBlur.Gradient -> R.string.top_bar_blur_gradient
    TopBarBlur.Progressive -> R.string.top_bar_blur_progressive
}
