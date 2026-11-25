package atom.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.popup.WindowDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

/*
 * Thin wrappers over the Miuix preference rows, fields, dialogs and sheets, so screens get the Atom look without
 * depending on the library's exact signatures. Rows go inside a [Group]. Popups, dialogs and sheets open in their
 * own window, so they work on any screen (not only inside a Miuix Scaffold).
 */

private fun optionIcon(vector: ImageVector?): (@Composable (Modifier) -> Unit)? {
    if (vector == null) return null
    return { m -> Icon(vector, null, m.padding(end = 12.dp).size(24.dp), tint = MiuixTheme.colorScheme.onBackground) }
}

/** Navigation row: icon, title, summary, optional end [value] text and the Miuix arrow. */
@Composable
fun ArrowRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
) {
    AtomRow(
        title = title,
        modifier = modifier,
        icon = icon,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        end = {
            if (value != null) {
                Box(Modifier.align(Alignment.CenterVertically).weight(1f, fill = false)) { RowValue(value) }
            }
            Box(Modifier.align(Alignment.CenterVertically)) { RowChevron() }
        },
    )
}

/** Row with a Miuix switch at the end; tapping the row toggles it. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
) {
    AtomRow(
        title = title,
        modifier = modifier,
        icon = icon,
        summary = summary,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        role = Role.Switch,
        end = {
            Box(Modifier.align(Alignment.CenterVertically)) {
                Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            }
        },
    )
}

/** Row with a Miuix checkbox at the end; tapping the row toggles it. */
@Composable
fun CheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
) {
    AtomRow(
        title = title,
        modifier = modifier,
        icon = icon,
        summary = summary,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        role = Role.Checkbox,
        end = {
            Box(Modifier.align(Alignment.CenterVertically)) {
                AtomCheckbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
            }
        },
    )
}

/** A bare Miuix checkbox. */
@Composable
fun AtomCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Checkbox(
        state = ToggleableState(checked),
        onClick = onCheckedChange?.let { { it(!checked) } },
        modifier = modifier,
        enabled = enabled,
    )
}

/**
 * Dropdown row: the current value in grey with the up/down chevron at the end; tapping opens the Miuix dropdown
 * list anchored to the row.
 */
@Composable
fun DropdownRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SpinnerRow(
        title = title,
        options = items.map { SpinnerOption(it) },
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
        icon = icon,
        summary = summary,
        enabled = enabled,
    )
}

/** One choice in a [SpinnerRow]: title, optional summary line and optional icon. */
data class SpinnerOption(val title: String, val summary: String? = null, val icon: ImageVector? = null)

/** Like [DropdownRow], but each choice can carry a summary line and an icon. */
@Composable
fun SpinnerRow(
    title: String,
    options: List<SpinnerOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val entry = remember(options, selectedIndex) {
        DropdownEntry(
            options.mapIndexed { i, o ->
                DropdownItem(
                    text = o.title,
                    selected = i == selectedIndex,
                    onClick = { currentOnSelect(i) },
                    icon = optionIcon(o.icon),
                    summary = o.summary,
                )
            },
        )
    }
    var expanded by remember { mutableStateOf(false) }
    var holdDown by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val active = enabled && options.isNotEmpty()
    val c = MiuixTheme.colorScheme
    val actionColor = if (active) c.onSurfaceVariantActions else c.disabledOnSecondaryVariant
    val current = options.getOrNull(selectedIndex)?.title
    AtomRow(
        title = title,
        modifier = modifier,
        icon = icon,
        summary = summary,
        enabled = active,
        onClick = {
            expanded = !expanded
            if (expanded) {
                holdDown = true
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        },
        role = Role.DropdownList,
        holdDown = holdDown,
        end = {
            if (!current.isNullOrEmpty()) {
                Text(
                    text = current,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .align(Alignment.CenterVertically)
                        .weight(1f, fill = false),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = actionColor,
                    textAlign = TextAlign.End,
                )
            }
            DropdownArrowEndAction(actionColor = actionColor)
            if (options.isNotEmpty()) {
                WindowDropdownPopup(
                    entry = entry,
                    show = expanded,
                    onDismiss = { expanded = false },
                    maxHeight = null,
                    dropdownColors = DropdownDefaults.dropdownColors(),
                    onDismissFinished = { holdDown = false },
                )
            }
        },
    )
}

/** Miuix search field: capsule with the search glyph, a clear button while there is text. */
@Composable
fun AtomSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    onSearch: (String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    InputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = onSearch,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
        label = label,
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.padding(end = 6.dp)) {
                    Icon(MiuixIcons.Basic.SearchCleanup, null, tint = MiuixTheme.colorScheme.onSurfaceContainerHigh)
                }
            }
        } else {
            null
        },
    )
}

/** Miuix text field (filled, rounded, label shown above the text once typing starts). */
@Composable
fun AtomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
    )
}

/** Miuix text button: accent fill when [primary], grey otherwise. */
@Composable
fun AtomTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
    )
}

/**
 * Miuix dialog (rounded card that rises from the bottom on phones, dimmed window). Put fields in [content] and
 * end with [DialogButtons].
 */
@Composable
fun AtomDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    WindowDialog(show = show, title = title, summary = summary, onDismissRequest = onDismiss, content = content)
}

/** The Miuix dialog button pair: secondary dismiss on the left, primary confirm on the right. */
@Composable
fun DialogButtons(
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            enabled = confirmEnabled,
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/** Miuix bottom sheet (slides up, drag down or tap the dim to dismiss). */
@Composable
fun AtomBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    WindowBottomSheet(show = show, title = title, onDismissRequest = onDismiss, content = content)
}
