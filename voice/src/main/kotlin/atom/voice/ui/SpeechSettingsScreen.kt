package atom.voice.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.ui.components.AtomMenu
import atom.ui.components.AtomMenuItem
import atom.ui.components.AtomSwitch
import atom.ui.components.BottomActionBar
import atom.ui.components.CompactButton
import atom.ui.components.Group
import atom.ui.components.GroupDivider
import atom.ui.components.GroupRow
import atom.ui.components.LargeTitleScreen
import atom.ui.components.PrimaryButton
import atom.ui.components.RowChevron
import atom.ui.components.RowValue
import atom.ui.components.SectionLabel
import atom.ui.components.SegmentedControl
import atom.ui.feature.FeatureNav
import atom.voice.R
import atom.voice.Wake
import atom.voice.WakePolicy
import atom.voice.WakeState
import atom.voice.asr.VoiceInput
import atom.voice.asr.VoiceInputOptions
import atom.voice.asr.VoiceInputState
import atom.voice.audio.MicCapture
import atom.voice.chat.BargeInMonitor
import atom.voice.cloud.OssCleanup
import atom.voice.speech.AsrServiceId
import atom.voice.speech.AutoSendPause
import atom.voice.speech.MimoLanguage
import atom.voice.speech.MiniMaxSite
import atom.voice.speech.QwenRegion
import atom.voice.speech.SecretResolver
import atom.voice.speech.Speech
import atom.voice.speech.SpeechConfig
import atom.voice.speech.SpeechSecret
import atom.voice.speech.TtsServiceId
import atom.voice.speech.VoiceCatalog
import atom.voice.speech.VoiceGender
import atom.voice.speech.WakePrefs
import atom.voice.tts.ReadAloud
import atom.voice.tts.ReadAloudSource
import atom.voice.tts.ReadAloudState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class SpeechPage { Overview, Recognition, Voice, Storage }

/**
 * Speech settings: one draft for every page (overview, recognition, spoken answers, recording storage), saved
 * only with the explicit Save. "Test recognition" and "Preview voice" use the unsaved draft, including typed
 * keys. Hey Atom switches apply at once (they need the microphone permission prompt).
 */
@Composable
fun SpeechSettingsScreen(nav: FeatureNav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by Speech.config.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(Speech.config.value) }
    val edits = remember { mutableStateMapOf<SpeechSecret, String>() }
    val removed = remember { mutableStateMapOf<SpeechSecret, Boolean>() }
    var stored by remember { mutableStateOf(emptySet<SpeechSecret>()) }
    var page by rememberSaveable { mutableStateOf(SpeechPage.Overview) }
    val credentials = Speech.credentials

    suspend fun refreshStored() {
        stored = SpeechSecret.entries.filter { runCatching { credentials?.has(it) == true }.getOrDefault(false) }.toSet()
    }
    LaunchedEffect(Unit) { refreshStored() }

    val dirty = draft != saved || edits.values.any { it.isNotBlank() } || removed.isNotEmpty()
    fun resolver() = SecretResolver(credentials, edits.filterValues { it.isNotBlank() })

    fun save() {
        val next = draft
        val changes = edits.filterValues { it.isNotBlank() }.toMap()
        val removals = removed.keys.toList()
        scope.launch {
            Speech.save(next)
            credentials?.let { store ->
                changes.forEach { (k, v) -> store.set(k, v) }
                removals.forEach { store.set(it, null) }
            }
            edits.clear()
            removed.clear()
            refreshStored()
        }
    }

    BackHandler(page != SpeechPage.Overview) { page = SpeechPage.Overview }

    // Stop a preview when the page goes away.
    DisposableEffect(Unit) { onDispose { ReadAloud.onOwnerHidden(ReadAloudSource.Preview) } }

    val secretProps = SecretProps(stored, removed, edits)
    val title = stringResource(
        when (page) {
            SpeechPage.Overview -> R.string.speech_title
            SpeechPage.Recognition -> R.string.speech_set_recognition
            SpeechPage.Voice -> R.string.speech_set_spoken_answers
            SpeechPage.Storage -> R.string.speech_set_storage
        },
    )
    LargeTitleScreen(
        title = title,
        onBack = { if (page != SpeechPage.Overview) page = SpeechPage.Overview else nav.back() },
        bottomBar = {
            if (dirty) BottomActionBar { PrimaryButton(stringResource(R.string.speech_set_save), onClick = ::save) }
        },
    ) {
        when (page) {
            SpeechPage.Overview -> overview(draft, { draft = it }, { page = it }, nav, ::resolver)
            SpeechPage.Recognition -> recognitionPage(draft, { draft = it }, secretProps) { page = SpeechPage.Storage }
            SpeechPage.Voice -> voicePage(draft, { draft = it }, secretProps)
            SpeechPage.Storage -> storagePage(draft, { draft = it }, secretProps)
        }
    }
}

private class SecretProps(
    val stored: Set<SpeechSecret>,
    val removed: MutableMap<SpeechSecret, Boolean>,
    val edits: MutableMap<SpeechSecret, String>,
)

// ------------------------------------------------------------------ overview

private fun LazyListScope.overview(
    draft: SpeechConfig,
    set: (SpeechConfig) -> Unit,
    go: (SpeechPage) -> Unit,
    nav: FeatureNav,
    resolver: () -> SecretResolver,
) {
    item(key = "master") {
        Group {
            GroupRow(
                title = stringResource(R.string.speech_set_enable_recognition),
                subtitle = stringResource(R.string.speech_set_enable_recognition_sub),
                onClick = { set(draft.copy(recognitionEnabled = !draft.recognitionEnabled)) },
                trailing = { AtomSwitch(draft.recognitionEnabled, { set(draft.copy(recognitionEnabled = it)) }) },
            )
        }
    }
    item(key = "services") {
        val context = LocalContext.current
        SectionLabel(stringResource(R.string.speech_set_recognition))
        Group {
            GroupRow(
                title = stringResource(R.string.speech_set_recognition_service),
                subtitle = asrName(Speech.effectiveAsr(context, draft)),
                enabled = draft.recognitionEnabled,
                onClick = { go(SpeechPage.Recognition) },
                trailing = { RowChevron() },
            )
            GroupDivider(inset = 18.dp)
            GroupRow(
                title = stringResource(R.string.speech_set_storage),
                subtitle = stringResource(R.string.speech_set_storage_sub),
                onClick = { go(SpeechPage.Storage) },
                trailing = { RowChevron() },
            )
        }
        SectionLabel(stringResource(R.string.speech_set_spoken_answers))
        Group {
            GroupRow(
                title = stringResource(R.string.speech_set_voice_service),
                subtitle = ttsName(draft.tts) + voiceSuffix(draft),
                onClick = { go(SpeechPage.Voice) },
                trailing = { RowChevron() },
            )
        }
    }
    item(key = "assistant") {
        SectionLabel(stringResource(R.string.speech_set_assistant))
        Group {
            GroupRow(
                title = stringResource(R.string.speech_set_overlay_speak),
                subtitle = stringResource(R.string.speech_set_overlay_speak_sub),
                onClick = { set(draft.copy(overlaySpeakAnswers = !draft.overlaySpeakAnswers)) },
                trailing = { AtomSwitch(draft.overlaySpeakAnswers, { set(draft.copy(overlaySpeakAnswers = it)) }) },
            )
            GroupDivider(inset = 18.dp)
            var open by remember { mutableStateOf(false) }
            Box {
                GroupRow(
                    title = stringResource(R.string.speech_set_auto_send),
                    enabled = draft.recognitionEnabled,
                    onClick = { open = true },
                    trailing = { RowValue(pauseName(draft.autoSendPause)) },
                )
                AtomMenu(expanded = open, onDismiss = { open = false }) {
                    AutoSendPause.entries.forEach { p ->
                        AtomMenuItem(pauseName(p), checked = p == draft.autoSendPause) {
                            set(draft.copy(autoSendPause = p))
                            open = false
                        }
                    }
                }
            }
            if (draft.ttsConfigured) {
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.speech_set_auto_read),
                    subtitle = stringResource(R.string.speech_set_auto_read_sub),
                    onClick = { set(draft.copy(autoReadAloud = !draft.autoReadAloud)) },
                    trailing = { AtomSwitch(draft.autoReadAloud, { set(draft.copy(autoReadAloud = it)) }) },
                )
            }
        }
    }
    item(key = "accuracy") {
        SectionLabel(stringResource(R.string.speech_set_accuracy))
        Group {
            GroupRow(
                title = stringResource(R.string.speech_set_smart_correction),
                subtitle = stringResource(R.string.speech_set_smart_correction_sub),
                enabled = Speech.proofreader != null,
                onClick = { set(draft.copy(smartCorrection = !draft.smartCorrection)) },
                trailing = { AtomSwitch(draft.smartCorrection, { set(draft.copy(smartCorrection = it)) }, enabled = Speech.proofreader != null) },
            )
            GroupDivider(inset = 18.dp)
            GroupRow(
                title = stringResource(R.string.speech_set_filter_markdown),
                subtitle = stringResource(R.string.speech_set_filter_markdown_sub),
                onClick = { set(draft.copy(filterMarkdown = !draft.filterMarkdown)) },
                trailing = { AtomSwitch(draft.filterMarkdown, { set(draft.copy(filterMarkdown = it)) }) },
            )
        }
    }
    item(key = "audiochat") {
        val bargeSupported = remember { BargeInMonitor.supported() }
        SectionLabel(stringResource(R.string.speech_set_audio_chat))
        Group {
            GroupRow(
                title = stringResource(R.string.speech_set_open_audio_chat),
                subtitle = stringResource(R.string.speech_audio_chat_subtitle),
                enabled = draft.recognitionEnabled,
                onClick = { nav.open(VoiceRoutes.AUDIO_CHAT) },
                trailing = { RowChevron() },
            )
            GroupDivider(inset = 18.dp)
            GroupRow(
                title = stringResource(R.string.speech_set_chat_speak),
                onClick = { set(draft.copy(audioChatSpeakAnswers = !draft.audioChatSpeakAnswers)) },
                trailing = { AtomSwitch(draft.audioChatSpeakAnswers, { set(draft.copy(audioChatSpeakAnswers = it)) }) },
            )
            GroupDivider(inset = 18.dp)
            GroupRow(
                title = stringResource(R.string.speech_set_chat_barge),
                subtitle = stringResource(if (bargeSupported) R.string.speech_set_chat_barge_sub else R.string.speech_set_chat_barge_unsupported),
                enabled = bargeSupported,
                onClick = { set(draft.copy(audioChatBargeIn = !draft.audioChatBargeIn)) },
                trailing = { AtomSwitch(draft.audioChatBargeIn && bargeSupported, { set(draft.copy(audioChatBargeIn = it)) }, enabled = bargeSupported) },
            )
        }
    }
    item(key = "wake") { WakeGroup(draft, set) }
    item(key = "try") { TryGroup(draft, set, resolver) }
}

@Composable
private fun WakeGroup(draft: SpeechConfig, set: (SpeechConfig) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wakePreferences = Speech.wakePreferences
    val flow = remember { wakePreferences?.current ?: Speech.config.map { WakePrefs(it.wakeEnabled, it.wakeScreenOff, it.wakeWhileCharging) } }
    val prefs by flow.collectAsStateWithLifecycle(initialValue = null)
    val state by Wake.state.collectAsStateWithLifecycle()
    val modelReady by Wake.modelAvailable.collectAsStateWithLifecycle()
    val p = prefs ?: return

    fun apply(next: WakePrefs) {
        scope.launch {
            if (wakePreferences != null) wakePreferences.set(next)
            else Speech.update { it.copy(wakeEnabled = next.enabled, wakeScreenOff = next.screenOff, wakeWhileCharging = next.whileCharging) }
            Wake.setEnabled(context, next.enabled, WakePolicy(screenOffListening = next.screenOff, listenWhileCharging = next.whileCharging))
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) apply(p.copy(enabled = true))
    }

    fun toggle(on: Boolean) {
        if (on && !MicCapture.hasPermission(context)) mic.launch(Manifest.permission.RECORD_AUDIO) else apply(p.copy(enabled = on))
    }

    SectionLabel(stringResource(R.string.speech_set_hey_atom))
    Group {
        GroupRow(
            title = stringResource(R.string.speech_set_hey_atom_switch),
            subtitle = when {
                !modelReady -> stringResource(R.string.speech_set_hey_atom_missing)
                else -> when (val s = state) {
                    WakeState.Listening -> stringResource(R.string.speech_phase_listening)
                    is WakeState.Paused -> s.reason
                    is WakeState.Unavailable -> s.reason
                    WakeState.Off -> null
                }
            },
            // Without the on-device AI pack the row opens the screen that downloads it.
            onClick = { if (modelReady) toggle(!p.enabled) else Wake.openPackSettings?.invoke() },
            trailing = { AtomSwitch(p.enabled && modelReady, ::toggle, enabled = modelReady) },
        )
        if (p.enabled) {
            GroupDivider(inset = 18.dp)
            GroupRow(
                title = stringResource(R.string.speech_set_hey_atom_charging),
                subtitle = stringResource(R.string.speech_set_hey_atom_charging_sub),
                onClick = { apply(p.copy(whileCharging = !p.whileCharging)) },
                trailing = { AtomSwitch(p.whileCharging, { apply(p.copy(whileCharging = it)) }) },
            )
            GroupDivider(inset = 18.dp)
            GroupRow(
                title = stringResource(R.string.speech_set_hey_atom_screen_off),
                subtitle = stringResource(R.string.speech_set_hey_atom_screen_off_sub),
                onClick = { apply(p.copy(screenOff = !p.screenOff)) },
                trailing = { AtomSwitch(p.screenOff, { apply(p.copy(screenOff = it)) }) },
            )
            if (Wake.presenter != null) {
                GroupDivider(inset = 18.dp)
                OverlayRow()
                GroupDivider(inset = 18.dp)
                GroupRow(
                    title = stringResource(R.string.speech_set_hey_atom_screen),
                    subtitle = stringResource(R.string.speech_set_hey_atom_screen_sub),
                    onClick = { set(draft.copy(wakeScreenContext = !draft.wakeScreenContext)) },
                    trailing = { AtomSwitch(draft.wakeScreenContext, { set(draft.copy(wakeScreenContext = it)) }) },
                )
            }
        }
    }
}

/**
 * The on-screen "Hey ATOM" overlay: the glow and the live words need "display over other apps". Without that
 * permission the command opens ATOM instead, and this row says so with a button that asks for it.
 */
@Composable
private fun OverlayRow() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = Settings.canDrawOverlays(context) }
    val ask: () -> Unit = { openOverlayPermission(context) }
    val allow: @Composable () -> Unit = { CompactButton(stringResource(R.string.speech_set_hey_atom_overlay_allow), onClick = ask) }
    GroupRow(
        title = stringResource(R.string.speech_set_hey_atom_overlay),
        subtitle = stringResource(if (allowed) R.string.speech_set_hey_atom_overlay_on else R.string.speech_set_hey_atom_overlay_needs),
        onClick = if (allowed) null else ask,
        trailing = if (allowed) null else allow,
    )
}

private fun openOverlayPermission(context: Context) {
    val exact = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(exact)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }
}

@Composable
private fun TryGroup(draft: SpeechConfig, set: (SpeechConfig) -> Unit, resolver: () -> SecretResolver) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { VoiceInput(context.applicationContext, scope) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    val input by voice.state.collectAsStateWithLifecycle()
    val reading by ReadAloud.state.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf("") }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val previewing = when (val r = reading) {
        is ReadAloudState.Preparing -> r.source == ReadAloudSource.Preview
        is ReadAloudState.Speaking -> r.source == ReadAloudSource.Preview
        ReadAloudState.Idle -> false
    }

    SectionLabel(stringResource(R.string.speech_set_try))
    Group {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.speech_set_try_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val active = input as? VoiceInputState.Active
            CompactButton(
                stringResource(if (active != null) R.string.speech_set_test_stop else R.string.speech_set_test_recognition),
                onClick = {
                    when {
                        active != null -> voice.finish()
                        !MicCapture.hasPermission(context) -> mic.launch(Manifest.permission.RECORD_AUDIO)
                        else -> {
                            result = ""
                            voice.start(VoiceInputOptions(config = draft.copy(recognitionEnabled = true), secrets = resolver())) { result = it.text }
                        }
                    }
                },
            )
            when {
                active != null -> Text(
                    active.partial.ifBlank { asrPhaseText(active.phase) },
                    style = MaterialTheme.typography.bodyLarge,
                )
                input is VoiceInputState.Failed -> VoiceErrorRow((input as VoiceInputState.Failed).error, null)
                result.isNotBlank() -> Text(result, style = MaterialTheme.typography.bodyLarge)
            }
        }
        GroupDivider(inset = 0.dp)
        FieldRow(
            label = stringResource(R.string.speech_set_preview_text),
            value = draft.previewText,
            onValueChange = { set(draft.copy(previewText = it)) },
            singleLine = false,
            maxChars = SpeechConfig.PREVIEW_MAX,
        )
        Row(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
            CompactButton(
                stringResource(if (previewing) R.string.speech_stop else R.string.speech_set_preview_voice),
                onClick = {
                    if (previewing) ReadAloud.stop()
                    else ReadAloud.speak(draft.previewText.ifBlank { SpeechConfig.DEFAULT_PREVIEW }, ReadAloudSource.Preview, config = draft, secrets = resolver())
                },
            )
        }
    }
}

// ------------------------------------------------------------------ recognition

private fun LazyListScope.recognitionPage(draft: SpeechConfig, set: (SpeechConfig) -> Unit, secrets: SecretProps, openStorage: () -> Unit) {
    item(key = "asr-list") {
        val context = LocalContext.current
        val onDevice = remember { Speech.onDeviceRecognitionAvailable(context) }
        val current = Speech.effectiveAsr(context, draft)
        Group {
            AsrServiceId.entries.forEachIndexed { i, id ->
                if (i > 0) GroupDivider(inset = 18.dp)
                val unavailable = id == AsrServiceId.AndroidOnDevice && !onDevice
                ChoiceRow(
                    title = asrName(id),
                    subtitle = if (unavailable) stringResource(R.string.speech_asr_android_on_device_unavailable) else asrSub(id),
                    selected = id == current,
                    enabled = !unavailable,
                    onClick = { set(draft.copy(asr = id)) },
                )
            }
        }
    }
    item(key = "asr-fields") {
        val context = LocalContext.current
        val id = Speech.effectiveAsr(context, draft)
        SectionLabel(asrName(id))
        Group {
            if (id == AsrServiceId.Mimo) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.speech_f_mimo_language), style = MaterialTheme.typography.bodyLarge)
                    SegmentedControl(
                        options = listOf(stringResource(R.string.speech_f_auto) to null, stringResource(R.string.speech_f_chinese) to null, stringResource(R.string.speech_f_english) to null),
                        selected = draft.mimoLanguage.ordinal,
                        onSelect = { set(draft.copy(mimoLanguage = MimoLanguage.entries[it])) },
                    )
                }
            } else {
                FieldRow(
                    label = stringResource(R.string.speech_f_language),
                    value = draft.language,
                    onValueChange = { set(draft.copy(language = it.trim())) },
                    hint = stringResource(R.string.speech_f_language_hint),
                )
            }
            when (id) {
                AsrServiceId.QwenRealtime, AsrServiceId.QwenShort, AsrServiceId.QwenFile -> {
                    GroupDivider(inset = 18.dp)
                    QwenFields(draft, set, secrets)
                    if (id == AsrServiceId.QwenFile) {
                        GroupDivider(inset = 18.dp)
                        GroupRow(
                            title = stringResource(R.string.speech_set_storage),
                            subtitle = if (draft.ossBucket.isBlank()) stringResource(R.string.speech_f_file_needs_storage) else draft.ossBucket,
                            onClick = openStorage,
                            trailing = { RowChevron() },
                        )
                    }
                }
                AsrServiceId.DoubaoStreaming -> {
                    GroupDivider(inset = 18.dp)
                    DoubaoFields(draft, set, secrets, tts = false)
                }
                AsrServiceId.Mimo -> {
                    GroupDivider(inset = 18.dp)
                    Secret(SpeechSecret.MimoApiKey, stringResource(R.string.speech_f_api_key), secrets)
                }
                AsrServiceId.DeepSeek -> {
                    GroupDivider(inset = 18.dp)
                    Secret(SpeechSecret.DeepSeekApiKey, stringResource(R.string.speech_f_api_key), secrets, stringResource(R.string.speech_f_api_key_reuse, "DeepSeek"))
                }
                AsrServiceId.AndroidOnDevice, AsrServiceId.AndroidDefault -> Unit
            }
        }
    }
}

@Composable
private fun QwenFields(draft: SpeechConfig, set: (SpeechConfig) -> Unit, secrets: SecretProps) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.speech_f_region), style = MaterialTheme.typography.bodyLarge)
        SegmentedControl(
            options = listOf(stringResource(R.string.speech_f_beijing) to null, stringResource(R.string.speech_f_singapore) to null),
            selected = draft.qwenRegion.ordinal,
            onSelect = { set(draft.copy(qwenRegion = QwenRegion.entries[it])) },
        )
        Text(stringResource(R.string.speech_f_region_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    GroupDivider(inset = 18.dp)
    FieldRow(
        label = stringResource(R.string.speech_f_base_url),
        value = draft.qwenBaseUrl,
        onValueChange = { set(draft.copy(qwenBaseUrl = it.trim())) },
        hint = stringResource(R.string.speech_f_base_url_hint),
    )
    GroupDivider(inset = 18.dp)
    Secret(SpeechSecret.QwenApiKey, stringResource(R.string.speech_f_api_key), secrets, stringResource(R.string.speech_f_api_key_reuse, "Qwen"))
}

@Composable
private fun DoubaoFields(draft: SpeechConfig, set: (SpeechConfig) -> Unit, secrets: SecretProps, tts: Boolean) {
    Secret(SpeechSecret.DoubaoApiKey, stringResource(R.string.speech_f_api_key), secrets)
    GroupDivider(inset = 18.dp)
    FieldRow(
        label = stringResource(R.string.speech_f_doubao_resource),
        value = if (tts) draft.doubaoTtsResourceId else draft.doubaoResourceId,
        onValueChange = { v -> set(if (tts) draft.copy(doubaoTtsResourceId = v.trim()) else draft.copy(doubaoResourceId = v.trim())) },
    )
    GroupDivider(inset = 18.dp)
    FieldRow(
        label = stringResource(R.string.speech_f_doubao_app_id),
        value = draft.doubaoAppId,
        onValueChange = { set(draft.copy(doubaoAppId = it.trim())) },
        hint = stringResource(R.string.speech_f_doubao_app_id_hint),
    )
    if (draft.doubaoAppId.isNotBlank()) {
        GroupDivider(inset = 18.dp)
        Secret(SpeechSecret.DoubaoAccessToken, stringResource(R.string.speech_f_doubao_token), secrets)
    }
}

@Composable
private fun Secret(secret: SpeechSecret, label: String, props: SecretProps, hint: String? = null) {
    SecretRow(
        label = label,
        saved = secret in props.stored,
        removed = props.removed[secret] == true,
        value = props.edits[secret].orEmpty(),
        onValueChange = {
            props.edits[secret] = it
            props.removed.remove(secret)
        },
        onRemove = {
            props.removed[secret] = true
            props.edits.remove(secret)
        },
        hint = hint,
    )
}

// ------------------------------------------------------------------ spoken answers

private fun LazyListScope.voicePage(draft: SpeechConfig, set: (SpeechConfig) -> Unit, secrets: SecretProps) {
    item(key = "tts-list") {
        Group {
            TtsServiceId.entries.forEachIndexed { i, id ->
                if (i > 0) GroupDivider(inset = 18.dp)
                ChoiceRow(
                    title = ttsName(id),
                    subtitle = when (id) {
                        TtsServiceId.Off -> stringResource(R.string.speech_tts_off_sub)
                        TtsServiceId.System -> stringResource(R.string.speech_tts_system_sub)
                        else -> null
                    },
                    selected = id == draft.tts,
                    onClick = { set(draft.copy(tts = id)) },
                )
            }
        }
        NoteText(stringResource(R.string.speech_tts_fallback_note))
    }
    val service = draft.tts
    if (service == TtsServiceId.Off || service == TtsServiceId.System) return
    item(key = "tts-options") {
        SectionLabel(ttsName(service))
        Group {
            when (service) {
                TtsServiceId.Gemini -> {
                    ModelPicker(VoiceCatalog.geminiModels, draft.geminiModel) { set(draft.copy(geminiModel = it)) }
                    GroupDivider(inset = 18.dp)
                    FieldRow(
                        stringResource(R.string.speech_f_style), draft.geminiStyle, { set(draft.copy(geminiStyle = it)) },
                        hint = stringResource(R.string.speech_f_style_hint), singleLine = false, maxChars = SpeechConfig.STYLE_MAX,
                    )
                    GroupDivider(inset = 18.dp)
                    Secret(SpeechSecret.GeminiApiKey, stringResource(R.string.speech_f_api_key), secrets, stringResource(R.string.speech_f_api_key_reuse, "Gemini"))
                }
                TtsServiceId.MiniMax -> {
                    ModelPicker(VoiceCatalog.miniMaxModels, draft.miniMaxModel) { set(draft.copy(miniMaxModel = it)) }
                    GroupDivider(inset = 18.dp)
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.speech_f_site), style = MaterialTheme.typography.bodyLarge)
                        SegmentedControl(
                            options = listOf(stringResource(R.string.speech_f_overseas) to null, stringResource(R.string.speech_f_china) to null),
                            selected = draft.miniMaxSite.ordinal,
                            onSelect = { set(draft.copy(miniMaxSite = MiniMaxSite.entries[it])) },
                        )
                        Text(stringResource(R.string.speech_f_speed, draft.miniMaxSpeed), style = MaterialTheme.typography.bodyLarge)
                        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                        SegmentedControl(
                            options = speeds.map { (if (it % 1f == 0f) "${it.toInt()}" else "$it") + "×" to null },
                            selected = speeds.indexOfFirst { kotlin.math.abs(it - draft.miniMaxSpeed) < 0.01f }.let { if (it < 0) 2 else it },
                            onSelect = { set(draft.copy(miniMaxSpeed = speeds[it])) },
                        )
                    }
                    GroupDivider(inset = 18.dp)
                    GroupRow(
                        title = stringResource(R.string.speech_f_normalize),
                        onClick = { set(draft.copy(miniMaxNormalizeNumbers = !draft.miniMaxNormalizeNumbers)) },
                        trailing = { AtomSwitch(draft.miniMaxNormalizeNumbers, { set(draft.copy(miniMaxNormalizeNumbers = it)) }) },
                    )
                    GroupDivider(inset = 18.dp)
                    Secret(SpeechSecret.MiniMaxApiKey, stringResource(R.string.speech_f_api_key), secrets, stringResource(R.string.speech_f_api_key_reuse, "MiniMax"))
                }
                TtsServiceId.Mimo -> {
                    FieldRow(
                        stringResource(R.string.speech_f_style), draft.mimoStyle, { set(draft.copy(mimoStyle = it)) },
                        hint = stringResource(R.string.speech_f_style_hint), singleLine = false, maxChars = SpeechConfig.STYLE_MAX,
                    )
                    GroupDivider(inset = 18.dp)
                    Secret(SpeechSecret.MimoApiKey, stringResource(R.string.speech_f_api_key), secrets)
                }
                TtsServiceId.Qwen -> QwenFields(draft, set, secrets)
                TtsServiceId.Doubao -> DoubaoFields(draft, set, secrets, tts = true)
                TtsServiceId.DeepSeek ->
                    Secret(SpeechSecret.DeepSeekApiKey, stringResource(R.string.speech_f_api_key), secrets, stringResource(R.string.speech_f_api_key_reuse, "DeepSeek"))
                TtsServiceId.Off, TtsServiceId.System -> Unit
            }
        }
    }
    item(key = "tts-voices") {
        val voice = draft.voiceFor(service)
        val match = VoiceCatalog.match(service, voice)
        SectionLabel(stringResource(R.string.speech_f_voice))
        Group {
            VoiceCatalog.grouped(service).forEachIndexed { g, (gender, presets) ->
                if (g > 0) GroupDivider(inset = 0.dp)
                if (gender != null) {
                    Text(
                        stringResource(if (gender == VoiceGender.Female) R.string.speech_voice_female else R.string.speech_voice_male),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 2.dp),
                    )
                }
                presets.forEachIndexed { i, preset ->
                    if (i > 0) GroupDivider(inset = 18.dp)
                    ChoiceRow(title = preset.name, subtitle = preset.note, selected = match?.id == preset.id, onClick = { set(draft.withVoice(service, preset.id)) })
                }
            }
            GroupDivider(inset = 0.dp)
            FieldRow(
                label = stringResource(R.string.speech_f_custom_voice),
                value = if (match == null) voice else "",
                onValueChange = { set(draft.withVoice(service, it.trim())) },
                placeholder = match?.let { "${it.name} · ${it.id}" } ?: stringResource(R.string.speech_f_custom_voice_hint),
            )
        }
    }
}

@Composable
private fun ModelPicker(models: List<atom.voice.speech.VoicePreset>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.speech_f_model), style = MaterialTheme.typography.bodyLarge)
        SegmentedControl(
            options = models.map { it.name to null },
            selected = models.indexOfFirst { it.id == selected }.coerceAtLeast(0),
            onSelect = { onSelect(models[it].id) },
        )
        models.firstOrNull { it.id == selected }?.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ storage

private fun LazyListScope.storagePage(draft: SpeechConfig, set: (SpeechConfig) -> Unit, secrets: SecretProps) {
    item(key = "oss") {
        Group {
            FieldRow(stringResource(R.string.speech_f_oss_region), draft.ossRegion, { set(draft.copy(ossRegion = it.trim())) }, hint = stringResource(R.string.speech_f_oss_region_hint))
            GroupDivider(inset = 18.dp)
            FieldRow(stringResource(R.string.speech_f_oss_endpoint), draft.ossEndpoint, { set(draft.copy(ossEndpoint = it.trim())) }, hint = stringResource(R.string.speech_f_oss_endpoint_hint))
            GroupDivider(inset = 18.dp)
            FieldRow(stringResource(R.string.speech_f_oss_bucket), draft.ossBucket, { set(draft.copy(ossBucket = it.trim())) })
            GroupDivider(inset = 18.dp)
            FieldRow(stringResource(R.string.speech_f_oss_prefix), draft.ossPrefix, { set(draft.copy(ossPrefix = it.trim())) })
            GroupDivider(inset = 18.dp)
            Secret(SpeechSecret.OssAccessKeyId, stringResource(R.string.speech_f_oss_ak), secrets)
            GroupDivider(inset = 18.dp)
            Secret(SpeechSecret.OssAccessKeySecret, stringResource(R.string.speech_f_oss_sk), secrets)
        }
        NoteText(stringResource(R.string.speech_f_oss_note))
        val pending = remember { OssCleanup { Speech.ossCleanupPrefs() }.pendingCount }
        if (pending > 0) NoteText(stringResource(R.string.speech_f_oss_pending, pending))
    }
}

// ------------------------------------------------------------------ names

@Composable
internal fun asrName(id: AsrServiceId): String = stringResource(
    when (id) {
        AsrServiceId.AndroidOnDevice -> R.string.speech_asr_android_on_device
        AsrServiceId.AndroidDefault -> R.string.speech_asr_android_default
        AsrServiceId.QwenRealtime -> R.string.speech_asr_qwen_realtime
        AsrServiceId.QwenShort -> R.string.speech_asr_qwen_short
        AsrServiceId.QwenFile -> R.string.speech_asr_qwen_file
        AsrServiceId.DoubaoStreaming -> R.string.speech_asr_doubao
        AsrServiceId.Mimo -> R.string.speech_asr_mimo
        AsrServiceId.DeepSeek -> R.string.speech_asr_deepseek
    },
)

@Composable
private fun asrSub(id: AsrServiceId): String = stringResource(
    when (id) {
        AsrServiceId.AndroidOnDevice -> R.string.speech_asr_android_on_device_sub
        AsrServiceId.AndroidDefault -> R.string.speech_asr_android_default_sub
        AsrServiceId.QwenRealtime -> R.string.speech_asr_qwen_realtime_sub
        AsrServiceId.QwenShort -> R.string.speech_asr_qwen_short_sub
        AsrServiceId.QwenFile -> R.string.speech_asr_qwen_file_sub
        AsrServiceId.DoubaoStreaming -> R.string.speech_asr_doubao_sub
        AsrServiceId.Mimo -> R.string.speech_asr_mimo_sub
        AsrServiceId.DeepSeek -> R.string.speech_asr_deepseek_sub
    },
)

@Composable
internal fun ttsName(id: TtsServiceId): String = stringResource(
    when (id) {
        TtsServiceId.Off -> R.string.speech_tts_off
        TtsServiceId.System -> R.string.speech_tts_system
        TtsServiceId.Qwen -> R.string.speech_tts_qwen
        TtsServiceId.Doubao -> R.string.speech_tts_doubao
        TtsServiceId.Gemini -> R.string.speech_tts_gemini
        TtsServiceId.MiniMax -> R.string.speech_tts_minimax
        TtsServiceId.Mimo -> R.string.speech_tts_mimo
        TtsServiceId.DeepSeek -> R.string.speech_tts_deepseek
    },
)

@Composable
private fun voiceSuffix(config: SpeechConfig): String {
    val voice = config.voiceFor(config.tts).takeIf { it.isNotBlank() } ?: return ""
    val name = VoiceCatalog.match(config.tts, voice)?.name ?: "${stringResource(R.string.speech_f_custom)}: $voice"
    return " · $name"
}

@Composable
private fun pauseName(p: AutoSendPause): String = stringResource(
    when (p) {
        AutoSendPause.OneSecond -> R.string.speech_pause_1
        AutoSendPause.OneAndHalf -> R.string.speech_pause_15
        AutoSendPause.Two -> R.string.speech_pause_2
        AutoSendPause.Three -> R.string.speech_pause_3
        AutoSendPause.Never -> R.string.speech_pause_never
    },
)
