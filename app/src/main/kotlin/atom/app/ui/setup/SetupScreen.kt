@file:OptIn(ExperimentalMaterial3Api::class)

package atom.app.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.app.R
import atom.app.graph
import atom.app.ui.common.AtomCard
import atom.app.ui.common.StatusLine
import atom.app.ui.theme.AtomIcons
import atom.app.ui.theme.AtomMark
import atom.app.ui.theme.extras
import atom.device.Accessibility
import atom.device.ShellTier
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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_setup)) },
                navigationIcon = {
                    if (!firstRun) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (firstRun) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    AtomMark(Modifier.size(44.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(stringResource(R.string.setup_welcome_title), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.setup_welcome_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val colors = MaterialTheme.colorScheme
            val ok = MaterialTheme.extras.success
            val warn = MaterialTheme.extras.warning

            SetupCard(AtomIcons.Eye, stringResource(R.string.setup_accessibility_title)) {
                StatusLine(
                    dot = when {
                        connected -> ok
                        accessibilityEnabled -> warn
                        else -> colors.error
                    },
                    text = stringResource(
                        when {
                            connected -> R.string.status_connected
                            accessibilityEnabled -> R.string.status_enabled_not_connected
                            else -> R.string.status_off
                        },
                    ),
                )
                Body(stringResource(R.string.setup_accessibility_text))
                if (!connected) {
                    Button(onClick = {
                        runCatching { context.startActivity(Accessibility.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text(stringResource(R.string.setup_open_accessibility)) }
                    if (!accessibilityEnabled) {
                        Body(stringResource(R.string.setup_restricted_hint))
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text(stringResource(R.string.setup_open_app_info)) }
                    }
                }
            }

            VoiceCard()

            SetupCard(Icons.Filled.Notifications, stringResource(R.string.setup_notifications_title)) {
                StatusLine(
                    dot = if (notificationsGranted) ok else warn,
                    text = stringResource(if (notificationsGranted) R.string.status_allowed else R.string.status_not_allowed),
                )
                Body(stringResource(R.string.setup_notifications_text))
                if (!notificationsGranted) {
                    FilledTonalButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    }) { Text(stringResource(R.string.setup_allow_notifications)) }
                }
            }

            SetupCard(AtomIcons.Terminal, stringResource(R.string.setup_shell_title)) {
                if (checkingShell || tier == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.shell_detecting), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    StatusLine(
                        dot = if (tier == ShellTier.None) colors.outline else ok,
                        text = stringResource(
                            when (tier) {
                                ShellTier.Root -> R.string.shell_root
                                ShellTier.Shizuku -> R.string.shell_shizuku
                                else -> R.string.shell_none
                            },
                        ),
                    )
                }
                TierLine(stringResource(R.string.shell_tier_none_label), stringResource(R.string.shell_tier_none_desc), tier == ShellTier.None)
                TierLine(stringResource(R.string.shell_tier_shizuku_label), stringResource(R.string.shell_tier_shizuku_desc), tier == ShellTier.Shizuku)
                TierLine(stringResource(R.string.shell_tier_root_label), stringResource(R.string.shell_tier_root_desc), tier == ShellTier.Root)
                TextButton(
                    onClick = {
                        scope.launch {
                            checkingShell = true
                            runCatching { graph.redetectShell() }
                            checkingShell = false
                        }
                    },
                    enabled = !checkingShell,
                ) { Text(stringResource(R.string.shell_check_again)) }
            }

            SetupCard(AtomIcons.Battery, stringResource(R.string.setup_battery_title)) {
                Body(stringResource(R.string.setup_battery_1))
                Body(stringResource(R.string.setup_battery_2))
                Body(stringResource(R.string.setup_battery_3))
                Body(stringResource(R.string.setup_battery_4))
            }

            if (!engineChosen) {
                SetupCard(AtomIcons.Chip, stringResource(R.string.setup_model_title)) {
                    Body(stringResource(R.string.setup_model_text))
                    FilledTonalButton(onClick = onOpenModels) { Text(stringResource(R.string.empty_choose_model)) }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(if (firstRun) R.string.setup_continue else R.string.action_done))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun VoiceCard() {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val wake by Wake.state.collectAsStateWithLifecycle()
    var assistant by remember { mutableStateOf(AssistantRole.isHeld(context)) }
    val modelReady = remember { Wake.isModelAvailable(context) }
    val heyAtom = settings?.heyAtom == true
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

    SetupCard(AtomIcons.Mic, stringResource(R.string.setup_voice_title)) {
        val ok = MaterialTheme.extras.success
        StatusLine(
            dot = if (assistant) ok else MaterialTheme.colorScheme.outline,
            text = stringResource(if (assistant) R.string.voice_assistant_on else R.string.voice_assistant_off),
        )
        Body(stringResource(R.string.voice_assistant_text))
        if (!assistant) {
            FilledTonalButton(onClick = {
                AssistantRole.requestIntent(context)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }) { Text(stringResource(R.string.voice_assistant_set)) }
        }

        Spacer(Modifier.height(4.dp))
        ToggleRow(
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
            checked = heyAtom,
            enabled = modelReady,
        ) { on ->
            if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                scope.launch { graph.settings.setHeyAtom(on) }
                apply(on, screenOff)
            }
        }
        if (heyAtom) {
            ToggleRow(
                title = stringResource(R.string.voice_screen_off),
                subtitle = stringResource(R.string.voice_screen_off_text),
                checked = screenOff,
                enabled = true,
            ) { on ->
                scope.launch { graph.settings.setHeyAtomScreenOff(on) }
                apply(true, on)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SetupCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    AtomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TierLine(label: String, desc: String, current: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(76.dp),
        )
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun notificationsGranted(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
