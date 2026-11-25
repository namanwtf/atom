package atom.voice.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import atom.voice.R
import atom.voice.asr.AsrPhase
import atom.voice.speech.SpeechError

/** A [VoiceInput] tied to the calling composable: released (mic closed) when it leaves composition. */
@Composable
fun rememberVoiceInput(): atom.voice.asr.VoiceInput {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val input = androidx.compose.runtime.remember { atom.voice.asr.VoiceInput(context.applicationContext, scope) }
    androidx.compose.runtime.DisposableEffect(input) { onDispose { input.release() } }
    return input
}

@Composable
fun speechErrorText(error: SpeechError): String = stringResource(
    when (error) {
        SpeechError.NoPermission -> R.string.speech_err_no_permission
        SpeechError.Disabled -> R.string.speech_err_disabled
        SpeechError.MissingKey -> R.string.speech_err_missing_key
        SpeechError.CredentialUnreadable -> R.string.speech_err_unreadable_key
        SpeechError.MissingStorage -> R.string.speech_err_missing_storage
        SpeechError.Auth -> R.string.speech_err_auth
        SpeechError.Quota -> R.string.speech_err_quota
        SpeechError.Network -> R.string.speech_err_network
        SpeechError.Timeout -> R.string.speech_err_timeout
        SpeechError.NoSpeech -> R.string.speech_err_no_speech
        SpeechError.TooLong -> R.string.speech_err_too_long
        SpeechError.Busy -> R.string.speech_err_busy
        SpeechError.Unsupported -> R.string.speech_err_unsupported
        SpeechError.Server -> R.string.speech_err_server
    },
)

@Composable
fun asrPhaseText(phase: AsrPhase): String = stringResource(
    when (phase) {
        AsrPhase.Connecting -> R.string.speech_phase_connecting
        AsrPhase.Listening -> R.string.speech_phase_listening
        AsrPhase.Recognizing -> R.string.speech_phase_recognizing
        AsrPhase.Uploading -> R.string.speech_phase_uploading
        AsrPhase.Proofreading -> R.string.speech_phase_proofreading
    },
)

/**
 * A recognition error with a link to voice settings, for showing next to an input (chat composer, overlay).
 * [onOpenSettings] null hides the link.
 */
@Composable
fun VoiceErrorRow(error: SpeechError, onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(speechErrorText(error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f, fill = false))
        if (onOpenSettings != null && error != SpeechError.NoSpeech) {
            Text(
                stringResource(R.string.speech_dictation_settings_link),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable(onClick = onOpenSettings).padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * The voice orb: a graphite sphere with a white core. Static when [active] is false (no animation at all);
 * while audio is active its size and halo follow [level] (0..1) with a short 120 ms ease.
 */
@Composable
fun VoiceOrb(level: Float, active: Boolean, modifier: Modifier = Modifier, emphasis: Float = 1f) {
    val target = if (active) level.coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(target, tween(120), label = "orbLevel")
    val core = MaterialTheme.colorScheme.primary
    val graphite = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val base = r * 0.62f
        val radius = base * (1f + 0.22f * animated * emphasis)
        if (active) {
            // Halo: one soft ring that only exists while audio flows.
            drawCircle(
                brush = Brush.radialGradient(listOf(core.copy(alpha = 0.10f + 0.18f * animated), Color.Transparent), c, r),
                radius = r,
                center = c,
            )
        }
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFF2E2E33), graphite, Color(0xFF101012)), c, radius),
            radius = radius,
            center = c,
        )
        drawCircle(color = outline.copy(alpha = 0.6f), radius = radius, center = c, style = Stroke(width = 1.dp.toPx()))
        drawCircle(
            brush = Brush.radialGradient(listOf(core.copy(alpha = if (active) 0.95f else 0.75f), core.copy(alpha = 0f)), c, radius * 0.55f),
            radius = radius * 0.55f,
            center = c,
        )
    }
}
