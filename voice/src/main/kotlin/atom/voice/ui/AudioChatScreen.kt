package atom.voice.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.ui.components.CircleIconButton
import atom.ui.components.CompactButton
import atom.ui.components.PrimaryButton
import atom.ui.components.SegmentedControl
import atom.ui.feature.FeatureNav
import atom.ui.feature.StepState
import atom.voice.R
import atom.voice.asr.VoiceInputState
import atom.voice.audio.MicCapture
import atom.voice.chat.AudioChatController
import atom.voice.chat.AudioChatMachine
import atom.voice.speech.MarkdownSpeech
import atom.voice.tts.ReadAloud

/**
 * Full-screen hands-free voice conversation. Calm by design: the orb is a still image unless audio is flowing
 * (listening or speaking). The microphone is only open while the screen is visible; leaving, backgrounding or the
 * screen turning off ends the session and releases everything.
 */
@Composable
fun AudioChatScreen(nav: FeatureNav, conversationId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { AudioChatController(context.applicationContext, scope, conversationId) }
    val ui by controller.ui.collectAsStateWithLifecycle()
    val input by controller.voiceState.collectAsStateWithLifecycle()
    val outLevel by ReadAloud.level.collectAsStateWithLifecycle()
    var hasMic by remember { mutableStateOf(MicCapture.hasPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMic = granted
        if (granted) controller.start()
    }

    LaunchedEffect(Unit) {
        if (hasMic) controller.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Never runs in the background: stop on leave, on background and on screen off.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.leave() }
        lifecycle.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) controller.onScreenOff()
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
            controller.leave()
        }
    }

    // A live conversation keeps the screen on (talking hands-free); released as soon as it ends.
    val view = LocalView.current
    val live = ui.state !is AudioChatMachine.State.Ended
    DisposableEffect(live) {
        val window = (view.context as? Activity)?.window
        if (live) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    BackHandler { nav.back() }

    val c = MaterialTheme.colorScheme
    val state = ui.state
    val listening = state == AudioChatMachine.State.Listening
    val speaking = state is AudioChatMachine.State.Speaking
    val level = when {
        listening -> (input as? VoiceInputState.Active)?.level ?: 0f
        speaking -> outLevel
        else -> 0f
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, null, onClick = { nav.back() })
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.speech_audio_chat_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ui.conversationId?.let { id ->
                CompactButton(stringResource(R.string.speech_chat_open_in_chat), onClick = { nav.openChat(id) }, primary = false)
            }
        }

        // Transcript: what you said, then the answer and its latest steps, compact.
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val you = ui.partial.takeIf { listening && it.isNotBlank() } ?: ui.heard
            if (you.isNotBlank()) {
                Label(stringResource(R.string.speech_chat_you))
                Text(you, style = MaterialTheme.typography.headlineSmall, color = c.onSurface)
            }
            if (ui.steps.isNotEmpty() && state is AudioChatMachine.State.Thinking) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.steps.forEach { step ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape).background(
                                    when (step.state) {
                                        StepState.Running -> c.onSurface
                                        StepState.Failed -> c.error
                                        else -> c.outline
                                    },
                                ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                step.summary.ifBlank { step.tool },
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            val answer = remember(ui.answer) { MarkdownSpeech.toSpeech(ui.answer) }
            if (answer.isNotBlank() && state !is AudioChatMachine.State.Listening) {
                Label(stringResource(R.string.speech_chat_atom))
                Text(answer, style = MaterialTheme.typography.bodyLarge, color = c.onSurface.copy(alpha = 0.92f), maxLines = 12, overflow = TextOverflow.Ellipsis)
            }
            ui.speechError?.let { VoiceErrorRow(it, onOpenSettings = { nav.open(VoiceRoutes.SETTINGS) }) }
            ui.runError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.error) }
        }

        // The orb: tap to start / interrupt; in push-to-talk, press and hold.
        val orbDescription = stringResource(R.string.speech_chat_orb_cd)
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = orbDescription }
                .pointerInput(ui.handsFree, state is AudioChatMachine.State.Ended) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (state is AudioChatMachine.State.Ended) {
                            if (waitForUpOrCancellation() != null) controller.restart()
                        } else if (ui.handsFree) {
                            if (waitForUpOrCancellation() != null) controller.tap()
                        } else {
                            controller.press()
                            waitForUpOrCancellation()
                            controller.release()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            VoiceOrb(level = level, active = listening || speaking, modifier = Modifier.size(200.dp))
        }

        val status = when (state) {
            AudioChatMachine.State.Ready -> stringResource(if (ui.handsFree) R.string.speech_chat_tap_to_start else R.string.speech_chat_hold_to_talk)
            AudioChatMachine.State.Listening -> (input as? VoiceInputState.Active)?.let { asrPhaseText(it.phase) } ?: stringResource(R.string.speech_chat_listening)
            is AudioChatMachine.State.Thinking -> stringResource(R.string.speech_chat_thinking)
            is AudioChatMachine.State.Speaking -> stringResource(if (ui.bargeInByVoice) R.string.speech_chat_speak_interrupt else R.string.speech_chat_tap_interrupt)
            is AudioChatMachine.State.Ended -> stringResource(
                when (state.reason) {
                    AudioChatMachine.EndReason.Silence -> R.string.speech_chat_ended_silence
                    AudioChatMachine.EndReason.ScreenOff -> R.string.speech_chat_ended_screen
                    else -> R.string.speech_chat_ended
                },
            )
        }
        AnimatedContent(status, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(150)) }, label = "chatStatus", modifier = Modifier.fillMaxWidth()) { text ->
            Text(text, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(18.dp))

        if (!hasMic) {
            Text(stringResource(R.string.speech_chat_mic_needed), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            PrimaryButton(stringResource(R.string.speech_chat_allow_mic), onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) })
        } else if (state is AudioChatMachine.State.Ended) {
            PrimaryButton(stringResource(R.string.speech_chat_start_again), onClick = { controller.restart() })
        } else {
            SegmentedControl(
                options = listOf(stringResource(R.string.speech_chat_hands_free) to null, stringResource(R.string.speech_chat_push_to_talk) to null),
                selected = if (ui.handsFree) 0 else 1,
                onSelect = { controller.setHandsFree(it == 0) },
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.Center) {
                CompactButton(stringResource(R.string.speech_chat_end), onClick = { controller.end() }, primary = false)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
