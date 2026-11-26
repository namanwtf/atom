package atom.general.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.general.R
import atom.general.appearance.AppearanceStore
import atom.ui.components.AtomSwitch
import atom.ui.components.CompactButton
import atom.ui.components.DropdownRow
import atom.ui.components.Group
import atom.ui.components.LargeTitleScreen
import atom.ui.components.SectionLabel
import atom.ui.components.SwitchRow
import atom.ui.feature.FeatureNav
import atom.ui.theme.AccentColor
import atom.ui.theme.Appearance
import atom.ui.theme.AtomTheme
import atom.ui.theme.ColorStyle
import atom.ui.theme.TextSize
import atom.ui.theme.ThemeMode
import atom.ui.theme.TopBarBlur
import atom.ui.theme.UiStyle
import atom.ui.theme.supportsDynamicColor
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Theme mode choices, in menu order. */
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

    LargeTitleScreen(title = stringResource(R.string.appearance_title), onBack = nav::back) {
        item(key = "l-theme") { SectionLabel(stringResource(R.string.appearance_section_theme)) }
        item(key = "g-theme") {
            Group {
                DropdownRow(
                    title = stringResource(R.string.appearance_ui_style),
                    summary = stringResource(R.string.appearance_ui_style_sub),
                    items = UiStyle.entries.map { stringResource(uiStyleLabel(it)) },
                    selectedIndex = UiStyle.entries.indexOf(a.uiStyle).coerceAtLeast(0),
                    onSelect = { i -> edit { it.copy(uiStyle = UiStyle.entries[i]) } },
                )
                DropdownRow(
                    title = stringResource(R.string.appearance_theme_mode),
                    items = MODES.map { stringResource(modeLabel(it)) },
                    selectedIndex = MODES.indexOf(a.mode).coerceAtLeast(0),
                    onSelect = { i -> edit { it.copy(mode = MODES[i]) } },
                )
                SwitchRow(
                    title = stringResource(R.string.appearance_monet),
                    summary = stringResource(if (supportsDynamicColor) R.string.appearance_monet_sub else R.string.appearance_monet_unavailable),
                    checked = a.monet && supportsDynamicColor,
                    onCheckedChange = { v -> edit { it.copy(monet = v) } },
                    enabled = supportsDynamicColor,
                )
                AnimatedVisibility(
                    visible = a.monet && supportsDynamicColor,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
                ) {
                    Column {
                        DropdownRow(
                            title = stringResource(R.string.appearance_color_style),
                            items = ColorStyle.entries.map { stringResource(styleLabel(it)) },
                            selectedIndex = ColorStyle.entries.indexOf(a.colorStyle).coerceAtLeast(0),
                            onSelect = { i -> edit { it.copy(colorStyle = ColorStyle.entries[i]) } },
                            enabled = a.accent != AccentColor.Monochrome,
                        )
                        DropdownRow(
                            title = stringResource(R.string.appearance_accent),
                            items = AccentColor.entries.map { stringResource(accentLabel(it)) },
                            selectedIndex = AccentColor.entries.indexOf(a.accent).coerceAtLeast(0),
                            onSelect = { i -> edit { it.copy(accent = AccentColor.entries[i]) } },
                        )
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.appearance_pure_black),
                    summary = stringResource(R.string.appearance_pure_black_sub),
                    checked = a.pureBlack,
                    onCheckedChange = { v -> edit { it.copy(pureBlack = v) } },
                    enabled = a.mode != ThemeMode.Light,
                )
            }
        }

        item(key = "l-effects") { SectionLabel(stringResource(R.string.appearance_section_effects)) }
        item(key = "g-effects") {
            Group {
                DropdownRow(
                    title = stringResource(R.string.appearance_text_size),
                    summary = stringResource(R.string.appearance_text_size_sub),
                    items = TextSize.entries.map { stringResource(textSizeLabel(it)) },
                    selectedIndex = TextSize.entries.indexOf(a.textSize).coerceAtLeast(0),
                    onSelect = { i -> edit { it.copy(textSize = TextSize.entries[i]) } },
                )
                SwitchRow(
                    title = stringResource(R.string.appearance_swipe_back),
                    summary = stringResource(R.string.appearance_swipe_back_sub),
                    checked = a.swipeBackAnywhere,
                    onCheckedChange = { v -> edit { it.copy(swipeBackAnywhere = v) } },
                )
                SwitchRow(
                    title = stringResource(R.string.appearance_predictive_back),
                    summary = stringResource(R.string.appearance_predictive_back_sub),
                    checked = a.predictiveBack,
                    onCheckedChange = { v -> edit { it.copy(predictiveBack = v) } },
                )
                SwitchRow(
                    title = stringResource(R.string.appearance_blur),
                    summary = stringResource(R.string.appearance_blur_sub),
                    checked = a.blur,
                    onCheckedChange = { v -> edit { it.copy(blur = v) } },
                )
                DropdownRow(
                    title = stringResource(R.string.appearance_top_bar_blur),
                    items = TopBarBlur.entries.map { stringResource(topBarLabel(it)) },
                    selectedIndex = TopBarBlur.entries.indexOf(a.topBarBlur).coerceAtLeast(0),
                    onSelect = { i -> edit { it.copy(topBarBlur = TopBarBlur.entries[i]) } },
                    enabled = a.blur,
                )
                SwitchRow(
                    title = stringResource(R.string.appearance_progressive_blur),
                    summary = stringResource(R.string.appearance_progressive_blur_sub),
                    checked = a.progressiveBlur,
                    onCheckedChange = { v -> edit { it.copy(progressiveBlur = v) } },
                    enabled = a.blur,
                )
            }
        }

        item(key = "l-preview") { SectionLabel(stringResource(R.string.appearance_preview)) }
        item(key = "preview") {
            // The preview always renders the chosen appearance, even before the app theme catches up.
            // Text size is already applied by the app theme around this page; applying it again would square it.
            AtomTheme(appearance = a.copy(textSize = TextSize.Default)) { AppearancePreview() }
        }
    }
}

/** A miniature chat screen drawn with the current theme: page, bubble, reply, switch and primary action. */
@Composable
private fun AppearancePreview() {
    val c = MiuixTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.dividerLine, RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(c.primary, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(text = stringResource(R.string.about_app_name), style = MiuixTheme.textStyles.headline2, color = c.onSurface)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = stringResource(R.string.appearance_preview_user),
                style = MiuixTheme.textStyles.body2,
                color = c.onSurface,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Text(text = stringResource(R.string.appearance_preview_reply), style = MiuixTheme.textStyles.body2, color = c.onSurface)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.surfaceContainer)
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.appearance_preview_switch),
                style = MiuixTheme.textStyles.body2,
                color = c.onSurfaceContainer,
                modifier = Modifier.weight(1f),
            )
            AtomSwitch(checked = true, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            CompactButton(stringResource(R.string.appearance_preview_action), onClick = {})
        }
    }
}

private fun uiStyleLabel(s: UiStyle) = when (s) {
    UiStyle.Miuix -> R.string.ui_style_miuix
}

private fun modeLabel(m: ThemeMode) = when (m) {
    ThemeMode.System -> R.string.appearance_mode_system
    ThemeMode.Light -> R.string.appearance_mode_light
    ThemeMode.Dark -> R.string.appearance_mode_dark
}

private fun accentLabel(a: AccentColor) = when (a) {
    AccentColor.System -> R.string.accent_system
    AccentColor.Blue -> R.string.accent_blue
    AccentColor.Purple -> R.string.accent_purple
    AccentColor.Pink -> R.string.accent_pink
    AccentColor.Red -> R.string.accent_red
    AccentColor.Orange -> R.string.accent_orange
    AccentColor.Yellow -> R.string.accent_yellow
    AccentColor.Green -> R.string.accent_green
    AccentColor.Teal -> R.string.accent_teal
    AccentColor.Monochrome -> R.string.accent_monochrome
}

private fun styleLabel(s: ColorStyle) = when (s) {
    ColorStyle.TonalSpot -> R.string.style_tonal_spot
    ColorStyle.Neutral -> R.string.style_neutral
    ColorStyle.Vibrant -> R.string.style_vibrant
    ColorStyle.Expressive -> R.string.style_expressive
    ColorStyle.Rainbow -> R.string.style_rainbow
    ColorStyle.FruitSalad -> R.string.style_fruit_salad
    ColorStyle.Monochrome -> R.string.style_monochrome
    ColorStyle.Fidelity -> R.string.style_fidelity
    ColorStyle.Content -> R.string.style_content
    ColorStyle.Cmf -> R.string.style_cmf
}

private fun topBarLabel(t: TopBarBlur) = when (t) {
    TopBarBlur.Gradient -> R.string.top_bar_blur_gradient
    TopBarBlur.Progressive -> R.string.top_bar_blur_progressive
}

private fun textSizeLabel(size: TextSize): Int = when (size) {
    TextSize.XSmall -> R.string.appearance_text_xsmall
    TextSize.Small -> R.string.appearance_text_small
    TextSize.Default -> R.string.appearance_text_default
    TextSize.Large -> R.string.appearance_text_large
    TextSize.XLarge -> R.string.appearance_text_xlarge
}
