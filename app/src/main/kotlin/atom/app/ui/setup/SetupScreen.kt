package atom.app.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.graph
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.PrimaryButton
import atom.ui.components.StatusDot
import atom.ui.components.CompactButton
import atom.ui.theme.AtomIcons
import atom.ui.theme.AtomEmblem
import atom.ui.theme.extras
import atom.device.Accessibility
import atom.device.ShellTier
import atom.device.root.RootAccess
import atom.device.root.RootStatus
import atom.voice.AssistantRole
import atom.voice.Wake
import atom.voice.WakePolicy
import atom.voice.WakeState
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    firstRun: Boolean,
    engineChosen: Boolean,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val connected by Accessibility.connected.collectAsStateWithLifecycle()
    val tier by graph.shellTier.collectAsStateWithLifecycle()
    val rootStatus by RootAccess.status.collectAsStateWithLifecycle()
    var accessibilityEnabled by remember { mutableStateOf(Accessibility.isEnabled(context)) }
    var notificationsGranted by remember { mutableStateOf(notificationsGranted(context)) }
    var checkingShell by remember { mutableStateOf(false) }

    // Settings screens are outside the app; refresh what we show when the user comes back.
    LifecycleResumeEffect(Unit) {
        accessibilityEnabled = Accessibility.isEnabled(context)
        notificationsGranted = notificationsGranted(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (graph.shellTier.value == null) {
            checkingShell = true
            runCatching { graph.shell() }
            checkingShell = false
        }
    }
    val colors = MaterialTheme.colorScheme
    val ok = MaterialTheme.extras.success
    val warn = MaterialTheme.extras.warning

    LargeTitleScreen(
        title = stringResource(R.string.screen_setup),
        onBack = if (firstRun) null else onBack,
        bottomBar = {
            BottomActionBar {
                PrimaryButton(stringResource(if (firstRun) R.string.setup_continue else R.string.action_done), onDone)
            }
        },
    ) {
        if (firstRun) {
            item(key = "welcome") { Welcome() }
        }

        item(key = "accessibility") {
            CapabilityGroup(
                icon = AtomIcons.Eye,
                title = stringResource(R.string.setup_accessibility_title),
                dot = when {
                    connected -> ok
                    accessibilityEnabled -> warn
                    else -> colors.error
                },
                status = stringResource(
                    when {
                        connected -> R.string.status_connected
                        accessibilityEnabled -> R.string.status_enabled_not_connected
                        else -> R.string.status_off
                    },
                ),
            ) {
                Body(stringResource(R.string.setup_accessibility_text))
                if (!connected) {
                    Actions {
                        CompactButton(stringResource(R.string.setup_open_accessibility), onClick = {
                            runCatching { context.startActivity(Accessibility.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        })
                    }
                    if (!accessibilityEnabled) {
                        Hint(stringResource(R.string.setup_restricted_hint))
                        Actions {
                            CompactButton(stringResource(R.string.setup_open_app_info), primary = false, onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            })
                        }
                    }
                }
            }
        }

        item(key = "voice") { VoiceGroup() }

        item(key = "notifications") {
            CapabilityGroup(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.setup_notifications_title),
                // Not asked here: Atom asks the first time a task keeps running in the background.
                dot = if (notificationsGranted) ok else colors.outline,
                status = stringResource(if (notificationsGranted) R.string.status_allowed else R.string.status_when_needed),
            ) {
                Body(stringResource(R.string.setup_notifications_text))
                if (!notificationsGranted) Hint(stringResource(R.string.setup_notifications_later))
            }
        }

        item(key = "shell") {
            val detecting = checkingShell || tier == null || rootStatus == RootStatus.Checking
            CapabilityGroup(
                icon = AtomIcons.Terminal,
                title = stringResource(R.string.setup_shell_title),
                dot = if (detecting) null else if (tier == ShellTier.App) colors.outline else ok,
                status = stringResource(
                    when {
                        detecting -> R.string.shell_detecting
                        tier == ShellTier.Root -> R.string.shell_root
                        tier == ShellTier.Shizuku -> R.string.shell_shizuku
                        else -> R.string.shell_none
                    },
                ),
                busy = detecting,
            ) {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TierLine(stringResource(R.string.shell_tier_none_label), stringResource(R.string.shell_tier_none_desc), tier == ShellTier.App)
                    TierLine(stringResource(R.string.shell_tier_shizuku_label), stringResource(R.string.shell_tier_shizuku_desc), tier == ShellTier.Shizuku)
                    TierLine(stringResource(R.string.shell_tier_root_label), stringResource(R.string.shell_tier_root_desc), tier == ShellTier.Root)
                }
                if (tier != null && tier != ShellTier.Root) Hint(stringResource(R.string.shell_root_hint))
                Actions {
                    CompactButton(
                        stringResource(R.string.shell_check_again),
                        primary = false,
                        enabled = !checkingShell,
                        onClick = {
                            scope.launch {
                                checkingShell = true
                                runCatching { graph.redetectShell() }
                                checkingShell = false
                            }
                        },
                    )
                }
            }
        }

        item(key = "battery") {
            CapabilityGroup(icon = AtomIcons.Battery, title = stringResource(R.string.setup_battery_title)) {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Bullet(stringResource(R.string.setup_battery_1))
                    Bullet(stringResource(R.string.setup_battery_2))
                    Bullet(stringResource(R.string.setup_battery_3))
                    Bullet(stringResource(R.string.setup_battery_4))
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (!engineChosen) {
            item(key = "model") {
                CapabilityGroup(icon = AtomIcons.Chip, title = stringResource(R.string.setup_model_title)) {
                    Body(stringResource(R.string.setup_model_text))
                    Actions { CompactButton(stringResource(R.string.empty_choose_model), onClick = onOpenModels) }
                }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) { AtomEmblem(96.dp) }
        Spacer(Modifier.height(22.dp))
        Text(
            stringResource(R.string.setup_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.setup_welcome_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun VoiceGroup() {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val wake by Wake.state.collectAsStateWithLifecycle()
    var assistant by remember { mutableStateOf(AssistantRole.isHeld(context)) }
    val modelReady by Wake.modelAvailable.collectAsStateWithLifecycle()
    val heyAtom = settings?.heyAtom == true && modelReady
    val screenOff = settings?.heyAtomScreenOff == true

    LifecycleResumeEffect(Unit) {
        assistant = AssistantRole.isHeld(context)
        onPauseOrDispose { }
    }

    fun apply(enabled: Boolean, screenOffListening: Boolean) {
        Wake.setEnabled(context, enabled, WakePolicy(screenOffListening = screenOffListening))
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            scope.launch { graph.settings.setHeyAtom(true) }
            apply(true, screenOff)
        }
    }

    fun toggleHeyAtom(on: Boolean) {
        if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            scope.launch { graph.settings.setHeyAtom(on) }
            apply(on, screenOff)
        }
    }

    fun toggleScreenOff(on: Boolean) {
        scope.launch { graph.settings.setHeyAtomScreenOff(on) }
        apply(true, on)
    }

    CapabilityGroup(
        icon = AtomIcons.Mic,
        title = stringResource(R.string.setup_voice_title),
        dot = if (assistant) MaterialTheme.extras.success else MaterialTheme.colorScheme.outline,
        status = stringResource(if (assistant) R.string.voice_assistant_on else R.string.voice_assistant_off),
    ) {
        Body(stringResource(R.string.voice_assistant_text))
        if (!assistant) {
            Actions {
                CompactButton(stringResource(R.string.voice_assistant_set), onClick = {
                    AssistantRole.requestIntent(context)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                })
            }
        }
        Spacer(Modifier.height(6.dp))
        GroupDivider(inset = 0.dp)
        GroupRow(
            title = stringResource(R.string.voice_hey_atom),
            subtitle = when {
                !modelReady -> stringResource(R.string.voice_model_missing)
                else -> when (val s = wake) {
                    WakeState.Listening -> stringResource(R.string.voice_state_listening)
                    is WakeState.Paused -> stringResource(R.string.voice_state_paused, s.reason)
                    is WakeState.Unavailable -> s.reason
                    WakeState.Off -> stringResource(R.string.voice_hey_atom_text)
                }
            },
            // Without the on-device AI pack the row opens the screen that downloads it.
            onClick = { if (modelReady) toggleHeyAtom(!heyAtom) else Wake.openPackSettings?.invoke() },
            trailing = { AtomSwitch(checked = heyAtom, onCheckedChange = ::toggleHeyAtom, enabled = modelReady) },
        )
        AnimatedVisibility(
            visible = heyAtom,
            enter = fadeIn(tween(180)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
        ) {
            Column {
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.voice_screen_off),
                    subtitle = stringResource(R.string.voice_screen_off_text),
                    onClick = { toggleScreenOff(!screenOff) },
                    trailing = { AtomSwitch(checked = screenOff, onCheckedChange = ::toggleScreenOff) },
                )
            }
        }
    }
}

/**
 * One capability as a graphite group: icon, title and a status line (dot + text), then body text and actions.
 * Groups are spaced like separate sections.
 */
@Composable
private fun CapabilityGroup(
    icon: ImageVector,
    title: String,
    dot: Color? = null,
    status: String? = null,
    busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Group(Modifier.padding(top = 12.dp).animateContentSize(tween(200))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (status != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(10.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (dot != null) {
                            StatusDot(dot)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

/** Quieter, smaller text for edge-case instructions (restricted settings, KernelSU). */
@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

@Composable
private fun Bullet(text: String) {
    Row {
        Box(Modifier.padding(top = 8.dp, end = 12.dp)) { StatusDot(MaterialTheme.colorScheme.outline, Modifier.size(5.dp)) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TierLine(label: String, desc: String, current: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        Row(Modifier.width(88.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                color = if (current) c.onSurface else c.onSurfaceVariant,
            )
            if (current) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = c.onSurface)
            }
        }
        Text(desc, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

private fun notificationsGranted(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
