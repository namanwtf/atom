package atom.overlay.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import atom.overlay.Overlay
import atom.overlay.OverlayPrefs
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.bubble.Bubble
import atom.overlay.window.Windows
import atom.ui.components.AtomSwitch
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.SectionLabel
import atom.ui.feature.FeatureNav
import atom.voice.AssistantRole

/** Settings → Floating assistant. */
@Composable
internal fun OverlaySettingsScreen(nav: FeatureNav) {
    val context = LocalContext.current
    remember { OverlayRuntime.init(context) }
    val prefs = remember { OverlayPrefs.get(context) }
    val bubbleOn by prefs.bubbleEnabled.collectAsState()
    val glowOn by prefs.edgeGlow.collectAsState()
    val chipsOn by prefs.suggestionChips.collectAsState()
    val speakOn by prefs.autoSpeak.collectAsState()
    var canOverlay by remember { mutableStateOf(Windows.canDrawOverlays()) }
    var assistant by remember { mutableStateOf(assistantStatus(context)) }
    var enableOnReturn by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canOverlay = Windows.canDrawOverlays()
        assistant = assistantStatus(context)
        if (enableOnReturn && canOverlay) Bubble.setEnabled(true) else Bubble.sync()
        enableOnReturn = false
    }

    LargeTitleScreen(title = stringResource(R.string.overlay_settings_title), onBack = nav::back) {
        // As before: Assistant model, then the Floating bubble switch, then Reasoning level.
        item { SectionLabel(stringResource(R.string.overlay_section_assistant)) }
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.overlay_default_assistant),
                    subtitle = stringResource(R.string.overlay_default_assistant_hint),
                    onClick = { openAssistantPicker(context) },
                    trailing = { RowValue(stringResource(assistant)) },
                )
                val modelRoute = Overlay.hooks.assistantModelRoute
                if (modelRoute != null) {
                    GroupDivider(inset = 18.dp)
                    GroupRow(
                        title = stringResource(R.string.overlay_assistant_model),
                        subtitle = stringResource(R.string.overlay_assistant_model_hint),
                        onClick = { nav.open(modelRoute) },
                        trailing = { RowChevron() },
                    )
                }
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.overlay_bubble_switch),
                    subtitle = stringResource(
                        when {
                            bubbleOn && !canOverlay -> R.string.overlay_bubble_needs_permission
                            else -> R.string.overlay_bubble_hint
                        },
                    ),
                    onClick = {
                        if (bubbleOn && !canOverlay) {
                            toggleBubble(context, true, canOverlay) { enableOnReturn = true }
                        } else {
                            toggleBubble(context, !bubbleOn, canOverlay) { enableOnReturn = true }
                        }
                    },
                    trailing = {
                        AtomSwitch(bubbleOn, onCheckedChange = { on -> toggleBubble(context, on, canOverlay) { enableOnReturn = true } })
                    },
                )
                if (modelRoute != null) {
                    GroupDivider(inset = 18.dp)
                    GroupRow(
                        title = stringResource(R.string.overlay_reasoning),
                        subtitle = stringResource(R.string.overlay_reasoning_hint),
                        onClick = { nav.open(modelRoute) },
                        trailing = { RowChevron() },
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.overlay_section_bubble)) }
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.overlay_permission),
                    subtitle = stringResource(if (canOverlay) R.string.overlay_permission_granted else R.string.overlay_permission_missing),
                    onClick = { openOverlayPermission(context) },
                    trailing = { RowChevron() },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.overlay_reset_position),
                    subtitle = stringResource(R.string.overlay_reset_position_hint),
                    enabled = bubbleOn,
                    onClick = Bubble::resetPosition,
                )
            }
        }

        item { SectionLabel(stringResource(R.string.overlay_section_running)) }
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.overlay_edge_glow),
                    subtitle = stringResource(R.string.overlay_edge_glow_hint),
                    onClick = { prefs.setEdgeGlow(!glowOn) },
                    trailing = { AtomSwitch(glowOn, onCheckedChange = prefs::setEdgeGlow) },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.overlay_capsule),
                    subtitle = stringResource(
                        when {
                            Windows.hasAccessibility -> R.string.overlay_capsule_accessibility
                            canOverlay -> R.string.overlay_capsule_overlay
                            else -> R.string.overlay_capsule_missing
                        },
                    ),
                )
            }
        }

        item { SectionLabel(stringResource(R.string.overlay_section_overlay)) }
        item {
            Group {
                GroupRow(
                    title = stringResource(R.string.overlay_chips),
                    subtitle = stringResource(R.string.overlay_chips_hint),
                    onClick = { prefs.setSuggestionChips(!chipsOn) },
                    trailing = { AtomSwitch(chipsOn, onCheckedChange = prefs::setSuggestionChips) },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.overlay_auto_speak),
                    subtitle = stringResource(if (Overlay.readAloud.available) R.string.overlay_auto_speak_hint else R.string.overlay_auto_speak_unavailable),
                    onClick = { prefs.setAutoSpeak(!speakOn) },
                    trailing = { AtomSwitch(speakOn, onCheckedChange = prefs::setAutoSpeak) },
                )
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.overlay_screen_context_title),
                    subtitle = stringResource(R.string.overlay_screen_context_hint),
                )
            }
        }
    }
}

/**
 * The switch is on by default. Without the overlay permission it stays on and says why nothing shows; tapping the
 * row then asks for the permission, and the bubble appears on return. Turning it on asks the same way.
 */
private fun toggleBubble(context: Context, on: Boolean, canOverlay: Boolean, awaitPermission: () -> Unit) {
    if (on && !canOverlay) {
        awaitPermission()
        openOverlayPermission(context)
        return
    }
    Bubble.setEnabled(on)
}

private fun openOverlayPermission(context: Context) {
    val exact = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(exact)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }
}

private fun openAssistantPicker(context: Context) {
    val intent = AssistantRole.requestIntent(context) ?: Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    }
}

private fun assistantStatus(context: Context): Int {
    if (AssistantRole.isHeld(context)) return R.string.overlay_assistant_atom
    val current = runCatching { Settings.Secure.getString(context.contentResolver, "assistant") }.getOrNull()
    return when {
        current == null -> R.string.overlay_assistant_other_or_none
        current.isBlank() -> R.string.overlay_assistant_none
        else -> R.string.overlay_assistant_other
    }
}
