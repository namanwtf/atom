package atom.vdisplay.system

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.ui.components.DropdownRow
import atom.ui.components.GroupRow
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.SwitchRow
import atom.ui.theme.extras
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import atom.vdisplay.R
import atom.vdisplay.route.DisplayRouter
import atom.xposed.ModuleStatus
import atom.xposed.PowerKeyTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The system rows that live on the Settings page (the original layout keeps them there, not on the System
 * enhancements page): background virtual display, the assistant takeover rows, Circle to Search and accessibility
 * protection. Each draws one row for a Settings group; the app passes the icons.
 */

/** Whether the Xposed framework has ever connected on this phone (framework-only rows show from then on). */
@Composable
fun rememberFrameworkSeen(): Boolean {
    val context = LocalContext.current
    val prefs = remember { SystemPrefs.get(context) }
    val seen by prefs.frameworkSeen.collectAsStateWithLifecycle()
    val state by ModuleStatus.state.collectAsStateWithLifecycle()
    return seen || state is ModuleStatus.Connection.Connected
}

@Composable
private fun frameworkConnected(): Boolean {
    val state by ModuleStatus.state.collectAsStateWithLifecycle()
    return state is ModuleStatus.Connection.Connected
}

/** "Background virtual display" switch. */
@Composable
fun BackgroundDisplayRow(icon: ImageVector?) {
    val context = LocalContext.current
    val prefs = remember { SystemPrefs.get(context) }
    val on by prefs.backgroundDisplay.collectAsStateWithLifecycle()
    SwitchRow(
        title = stringResource(R.string.vd_bg_display),
        checked = on,
        onCheckedChange = { value ->
            prefs.setBackgroundDisplay(value)
            if (!value) DisplayRouter.release()
        },
        icon = icon,
    )
}

/**
 * "Digital assistant app": the current default assistant as the end value (green when it is Atom); opens the
 * system default-assistant picker.
 */
@Composable
fun DigitalAssistantRow(icon: ImageVector?) {
    val context = LocalContext.current
    val holder = rememberAssistantHolder()
    val isAtom = holder == context.getString(R.string.vd_assistant_atom)
    GroupRow(
        title = stringResource(R.string.vd_digital_assistant),
        icon = icon,
        onClick = {
            if (!openAssistantPickerOf(context)) {
                Toast.makeText(context, R.string.vd_assistant_open_failed, Toast.LENGTH_SHORT).show()
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isAtom) {
                    Text(holder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extras.success, maxLines = 1)
                } else {
                    RowValue(holder)
                }
                RowChevron()
            }
        },
    )
}

/**
 * The current default assistant, kept live: read again on every resume and once more shortly after (the role
 * holder settles asynchronously after the system picker closes), and whenever the system's assistant settings
 * change while Atom is in front (for example when "Automatically set" takes the role through root).
 */
@Composable
internal fun rememberAssistantHolder(): String {
    val context = LocalContext.current
    var holder by remember { mutableStateOf(assistantHolderOf(context)) }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    LaunchedEffect(resumes) {
        holder = assistantHolderOf(context)
        for (wait in longArrayOf(500, 1_500)) {
            delay(wait)
            holder = assistantHolderOf(context)
        }
    }
    DisposableEffect(Unit) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                holder = assistantHolderOf(context)
            }
        }
        for (key in listOf("assistant", "voice_interaction_service")) {
            runCatching { resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer) }
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return holder
}

/** "Long press the power button": shown once the framework was seen, editable only while it is connected. */
@Composable
fun PowerKeyRow(icon: ImageVector?) {
    val context = LocalContext.current
    val prefs = remember { SystemPrefs.get(context) }
    val hooks by prefs.hooks.collectAsStateWithLifecycle()
    val connected = frameworkConnected()
    val targets = PowerKeyTarget.entries
    val labels = targets.map {
        stringResource(
            when (it) {
                PowerKeyTarget.SystemDefault -> R.string.vd_power_system
                PowerKeyTarget.Gemini -> R.string.vd_power_gemini
                PowerKeyTarget.Atom -> R.string.vd_power_atom
            },
        )
    }
    DropdownRow(
        title = stringResource(R.string.vd_power_target),
        items = labels,
        selectedIndex = targets.indexOf(hooks.powerKeyTarget).coerceAtLeast(0),
        onSelect = { prefs.setPowerKeyTarget(targets[it]) },
        icon = icon,
        summary = if (connected) null else stringResource(R.string.vd_lsposed_needed),
        enabled = connected,
    )
}

/** "Automatically set default assistant" switch (framework rows). */
@Composable
fun AutoAssistantRow(icon: ImageVector?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SystemPrefs.get(context) }
    val hooks by prefs.hooks.collectAsStateWithLifecycle()
    val connected = frameworkConnected()
    SwitchRow(
        title = stringResource(R.string.vd_auto_assistant),
        checked = hooks.autoDefaultAssistant,
        onCheckedChange = { on ->
            prefs.updateHooks { it.copy(autoDefaultAssistant = on) }
            if (on && !connected) scope.launch { setAssistantRoleWithRoot(context, prefs.hooks.value) }
        },
        icon = icon,
        summary = if (connected) null else stringResource(R.string.vd_lsposed_needed),
    )
}

/** "Touch and hold the gesture bar to start Circle to Search". */
@Composable
fun CircleToSearchGestureBarRow(icon: ImageVector?) {
    val context = LocalContext.current
    val prefs = remember { SystemPrefs.get(context) }
    val hooks by prefs.hooks.collectAsStateWithLifecycle()
    SwitchRow(
        title = stringResource(R.string.vd_cts_bar),
        checked = hooks.gestureBarCircleToSearch,
        onCheckedChange = { on -> prefs.updateHooks { it.copy(gestureBarCircleToSearch = on) } },
        icon = icon,
        summary = if (frameworkConnected()) null else stringResource(R.string.vd_lsposed_needed),
    )
}

/** "Touch and hold with two fingers to start Circle to Search". */
@Composable
fun CircleToSearchTwoFingerRow(icon: ImageVector?) {
    val context = LocalContext.current
    val prefs = remember { SystemPrefs.get(context) }
    val hooks by prefs.hooks.collectAsStateWithLifecycle()
    SwitchRow(
        title = stringResource(R.string.vd_cts_two_finger),
        checked = hooks.twoFingerCircleToSearch,
        onCheckedChange = { on -> prefs.updateHooks { it.copy(twoFingerCircleToSearch = on) } },
        icon = icon,
        summary = if (frameworkConnected()) null else stringResource(R.string.vd_lsposed_needed),
    )
}

/** "Enforce accessibility" switch (framework rows). */
@Composable
fun EnforceAccessibilityRow(icon: ImageVector?) {
    val context = LocalContext.current
    val prefs = remember { SystemPrefs.get(context) }
    val hooks by prefs.hooks.collectAsStateWithLifecycle()
    SwitchRow(
        title = stringResource(R.string.vd_enforce_a11y),
        checked = hooks.enforceAccessibility,
        onCheckedChange = { on -> prefs.updateHooks { it.copy(enforceAccessibility = on) } },
        icon = icon,
        summary = if (frameworkConnected()) null else stringResource(R.string.vd_lsposed_needed),
    )
}
