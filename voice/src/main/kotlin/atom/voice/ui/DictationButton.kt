package atom.voice.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.ui.components.CompactButton
import atom.voice.speech.AsrServiceId
import atom.ui.theme.AtomIcons
import atom.voice.R
import atom.voice.asr.AsrPhase
import atom.voice.asr.VoiceInput
import atom.voice.asr.VoiceInputOptions
import atom.voice.asr.VoiceInputState
import atom.voice.audio.MicCapture
import atom.voice.speech.Speech
import atom.voice.speech.SpeechError
import kotlinx.coroutines.delay

private enum class DictationMode { Idle, Tap, Hold }

/**
 * The chat composer mic.
 *
 * - Tap: starts recording; tap again or "Done" inserts the text via [onInsert] (the caller inserts it at the
 *   cursor as an editable draft and must never overwrite existing text).
 * - Press and hold: talk; release to transcribe and [onSend]; slide away to cancel ("Release to send, slide away
 *   to cancel").
 * - Recognition errors appear in a small bubble with a link to voice settings ([onOpenSettings]).
 * - Hidden entirely while "Enable speech recognition" is off.
 *
 * Streaming services show live text in the bubble; upload services show progress states. The microphone is
 * released as soon as the recording ends or the composable leaves.
 */
@Composable
fun DictationButton(
    onInsert: (String) -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val config by Speech.config.collectAsStateWithLifecycle()
    if (!config.recognitionEnabled) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { VoiceInput(context.applicationContext, scope) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    val state by voice.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(DictationMode.Idle) }
    var cancelArmed by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val insert by rememberUpdatedState(onInsert)
    val send by rememberUpdatedState(onSend)
    val haptics = LocalHapticFeedback.current

    fun begin(next: DictationMode) {
        permissionDenied = false
        mode = next
        val options = if (next == DictationMode.Hold) VoiceInputOptions(noSpeechTimeoutMs = null) else VoiceInputOptions()
        val started = voice.start(options) { result ->
            val m = mode
            mode = DictationMode.Idle
            if (result.text.isNotBlank()) if (m == DictationMode.Hold) send(result.text) else insert(result.text)
        }
        if (!started) mode = DictationMode.Idle
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) permissionDenied = true
    }

    fun ensurePermission(): Boolean {
        if (MicCapture.hasPermission(context)) return true
        permission.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    // The recorder can also end by itself (max length, recognizer endpointing).
    LaunchedEffect(state) {
        if (state !is VoiceInputState.Active && mode != DictationMode.Idle && !voice.isActive) mode = DictationMode.Idle
        if (state is VoiceInputState.Failed && (state as VoiceInputState.Failed).error == SpeechError.NoSpeech) {
            delay(2_500)
            voice.clearError()
        }
    }

    val active = state as? VoiceInputState.Active
    val recording = active != null
    val viewConfig = LocalViewConfiguration.current
    val cancelDistance = with(LocalDensity.current) { 72.dp.toPx() }
    val c = MaterialTheme.colorScheme
    val container by animateColorAsState(if (recording) c.primary else c.surfaceContainerHigh, tween(180), label = "micBg")
    val tint by animateColorAsState(if (recording) c.onPrimary else c.onSurface, tween(180), label = "micTint")
    val ring by animateFloatAsState(if (active != null && active.phase == AsrPhase.Listening) 1f + 0.18f * active.level else 1f, tween(120), label = "micLevel")
    val description = stringResource(if (recording && mode == DictationMode.Tap) R.string.speech_dictation_finish else R.string.speech_dictation_cd)

    Box(modifier) {
        Box(
            Modifier
                .size(40.dp)
                .scale(ring)
                .clip(CircleShape)
                .background(container)
                .semantics { contentDescription = description }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var tapped = false
                        val beforeHold = withTimeoutOrNull(viewConfig.longPressTimeoutMillis) {
                            tapped = waitForUpOrCancellation() != null
                            true
                        }
                        if (beforeHold == true) {
                            if (tapped) {
                                when (mode) {
                                    DictationMode.Idle -> if (ensurePermission()) begin(DictationMode.Tap)
                                    DictationMode.Tap -> voice.finish()
                                    DictationMode.Hold -> Unit
                                }
                            }
                            return@awaitEachGesture
                        }
                        // Still pressed after the long-press timeout: hold to talk.
                        if (mode != DictationMode.Idle || !ensurePermission()) return@awaitEachGesture
                        cancelArmed = false
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        begin(DictationMode.Hold)
                        var total = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                voice.cancel()
                                mode = DictationMode.Idle
                                break
                            }
                            total += change.positionChange()
                            cancelArmed = total.getDistance() > cancelDistance
                            change.consume()
                            if (!change.pressed) {
                                if (cancelArmed) {
                                    voice.cancel()
                                    mode = DictationMode.Idle
                                } else {
                                    voice.finish()
                                }
                                cancelArmed = false
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (recording) AtomIcons.Stop.takeIf { mode == DictationMode.Tap } ?: AtomIcons.Mic else AtomIcons.Mic, null, Modifier.size(20.dp), tint = tint)
        }

        val failed = state as? VoiceInputState.Failed
        val showBubble = recording || failed != null || permissionDenied
        if (showBubble) {
            Popup(
                popupPositionProvider = AboveAnchor(with(LocalDensity.current) { 10.dp.roundToPx() }),
                onDismissRequest = { if (!recording) { voice.clearError(); permissionDenied = false } },
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = c.surfaceContainerHigh,
                    shadowElevation = 10.dp,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            permissionDenied -> {
                                Text(stringResource(R.string.speech_dictation_mic_denied), style = MaterialTheme.typography.bodyMedium)
                                CompactButton(stringResource(R.string.speech_dictation_open_settings), onClick = {
                                    permissionDenied = false
                                    openAppSettings(context)
                                }, primary = false)
                            }
                            failed != null -> VoiceErrorRow(failed.error, onOpenSettings?.let { open -> { voice.clearError(); open() } })
                            active != null -> {
                                val waitingForFile = active.service == AsrServiceId.QwenFile &&
                                    (active.phase == AsrPhase.Uploading || active.phase == AsrPhase.Recognizing)
                                val text = active.partial.takeIf { it.isNotBlank() && active.phase == AsrPhase.Listening } ?: when {
                                    mode == DictationMode.Hold && active.phase == AsrPhase.Listening ->
                                        stringResource(if (cancelArmed) R.string.speech_dictation_release_cancel else R.string.speech_dictation_hold_hint)
                                    waitingForFile -> stringResource(R.string.speech_dictation_file_wait)
                                    mode == DictationMode.Hold && active.phase != AsrPhase.Connecting -> stringResource(R.string.speech_dictation_sending)
                                    else -> asrPhaseText(active.phase)
                                }
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (cancelArmed) c.error else c.onSurface,
                                    maxLines = 4,
                                    // File transcription can take a while; a tap on the progress cancels it.
                                    modifier = if (waitingForFile) {
                                        Modifier.clickable {
                                            voice.cancel()
                                            mode = DictationMode.Idle
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                if (mode == DictationMode.Hold && active.partial.isNotBlank() && active.phase == AsrPhase.Listening) {
                                    Text(
                                        stringResource(if (cancelArmed) R.string.speech_dictation_release_cancel else R.string.speech_dictation_hold_hint),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (cancelArmed) c.error else c.onSurfaceVariant,
                                    )
                                }
                                if (mode == DictationMode.Tap) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        CompactButton(stringResource(R.string.speech_dictation_cancel), onClick = {
                                            voice.cancel()
                                            mode = DictationMode.Idle
                                        }, primary = false)
                                        Spacer(Modifier.weight(1f, fill = false))
                                        CompactButton(
                                            stringResource(R.string.speech_dictation_done),
                                            onClick = { voice.finish() },
                                            enabled = active.phase == AsrPhase.Listening || active.phase == AsrPhase.Connecting,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Atom's own app-info page, where the microphone permission can be allowed after a denial. */
private fun openAppSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Places a popup just above its anchor, horizontally centred and kept inside the window. */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
        val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(8)
        return IntOffset(x, y)
    }
}
