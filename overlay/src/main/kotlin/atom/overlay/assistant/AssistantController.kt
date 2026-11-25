package atom.overlay.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import atom.overlay.Overlay
import atom.overlay.OverlayMessage
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.RunHub
import atom.overlay.ui.Markdown
import atom.overlay.ui.VoiceInput
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import atom.voice.assist.AssistOverlay
import atom.voice.assist.AssistSessionHandle
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the full-screen assistant overlay. Lives for the process, so the conversation survives the session
 * window hiding while the agent uses the screen and coming back with the result. Main thread only.
 */
internal object AssistantController {
    var conversationId by mutableStateOf<String?>(null)
        private set
    val messages = mutableStateListOf<OverlayMessage>()
    var screen by mutableStateOf<ScreenContext?>(null)
        private set
    var screenshot by mutableStateOf<ImageBitmap?>(null)
        private set
    var includeScreen by mutableStateOf(true)
    var keyboard by mutableStateOf(false)
    var draft by mutableStateOf("")
    var notice by mutableStateOf<String?>(null)
        private set

    /** Bumped on every fresh invocation; replays the entry animation. */
    var entry by mutableStateOf(0)
        private set

    val voice = VoiceInput { send(it) }

    private var handle: AssistSessionHandle? = null
    private var visible = false
    private var hiddenFor: String? = null
    private var screenshotPath: String? = null
    private var contextSent = false
    private var awaitingStructure = false
    private var awaitingScreenshot = false
    private var queued: String? = null
    private var queueTimer: Job? = null
    private var captureJob: Job? = null

    // ------------------------------------------------------------------ Session

    fun onShow(session: AssistSessionHandle, args: Bundle?, showFlags: Int) {
        handle = session
        visible = true
        RunHub.start()
        val resume = args?.getString(AssistOverlay.EXTRA_RESUME_CONVERSATION)
        if (resume != null && resume == conversationId) return
        reset()
        entry++
        awaitingStructure = showFlags and VoiceInteractionSession.SHOW_WITH_ASSIST != 0
        awaitingScreenshot = showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0
        if (voice.available) {
            keyboard = false
            voice.start(session.sessionContext())
        } else {
            keyboard = true
        }
    }

    fun onHide(session: AssistSessionHandle) {
        if (handle !== session) return
        visible = false
        voice.cancel()
        if (hiddenFor == null) {
            Overlay.readAloud.stop()
            // Closed by the user: the full-resolution screenshot is not needed any more (its file stays for the run).
            screenshot = null
        }
    }

    fun onDestroy(session: AssistSessionHandle) {
        if (handle === session) {
            handle = null
            visible = false
        }
    }

    fun onAssist(context: Context, structure: AssistStructure?, content: AssistContent?) {
        val at = System.currentTimeMillis()
        val app = context.applicationContext
        captureJob = OverlayRuntime.scope.launch {
            val read = withContext(Dispatchers.Default) { runCatching { AssistExtract.read(app, structure, content, at) }.getOrNull() }
            if (read != null && (read.text.isNotEmpty() || read.packageName != null)) screen = read
            awaitingStructure = false
            flushQueued()
        }
    }

    fun onScreenshot(bitmap: Bitmap?) {
        awaitingScreenshot = false
        if (bitmap == null) {
            flushQueued()
            return
        }
        screenshot = bitmap.asImageBitmap()
        val at = System.currentTimeMillis()
        if (screen == null) screen = ScreenContext(null, null, null, "", at)
        val dir = File(OverlayRuntime.app.cacheDir, "assist")
        OverlayRuntime.scope.launch {
            screenshotPath = withContext(Dispatchers.IO) { save(bitmap, dir, at) }
            flushQueued()
        }
    }

    fun dismiss() {
        handle?.dismiss()
    }

    // ------------------------------------------------------------------ Conversation

    fun send(text: String) {
        val question = text.trim()
        if (question.isEmpty()) return
        voice.cancel()
        if (awaitingStructure || awaitingScreenshot) {
            queued = question
            draft = ""
            messages += OverlayMessage(fromUser = true, text = question)
            queueTimer?.cancel()
            queueTimer = OverlayRuntime.scope.launch {
                delay(CAPTURE_WAIT_MS)
                awaitingStructure = false
                awaitingScreenshot = false
                flushQueued()
            }
            return
        }
        messages += OverlayMessage(fromUser = true, text = question)
        draft = ""
        dispatch(question)
    }

    private fun flushQueued() {
        if (awaitingStructure || awaitingScreenshot) return
        val question = queued ?: return
        queued = null
        queueTimer?.cancel()
        dispatch(question)
    }

    private fun dispatch(question: String) {
        val host = OverlayRuntime.host()
        if (host == null) {
            notice = OverlayRuntime.app.getString(R.string.overlay_not_ready)
            return
        }
        notice = null
        val context = if (includeScreen) screen else null
        val first = context != null && !contextSent
        val attachments = if (first) listOfNotNull(screenshotPath) else emptyList()
        val prompt = AssistPrompt.build(question, context, first, hasScreenshot = attachments.isNotEmpty(), now = System.currentTimeMillis())
        val id = conversationId
        val running = id != null && host.runs.value.any { it.conversationId == id && it.phase.active }
        if (running) {
            host.steer(id, prompt)
        } else {
            val started = host.start(prompt, id, RunSource.Assistant, attachments)
            when {
                started == null && id != null -> host.steer(id, prompt)
                started == null -> {
                    notice = OverlayRuntime.app.getString(R.string.overlay_not_ready)
                    return
                }
                else -> conversationId = started
            }
        }
        if (first) contextSent = true
    }

    fun stop() {
        conversationId?.let { OverlayRuntime.host()?.stop(it) }
    }

    fun answer(approvalId: Long, approved: Boolean) {
        OverlayRuntime.host()?.answer(approvalId, approved)
    }

    fun readAloud(text: String) {
        val bridge = Overlay.readAloud
        if (bridge.speaking.value) bridge.stop() else bridge.speak(Markdown.plain(text))
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Atom", Markdown.plain(text)))
    }

    fun openInApp() {
        val h = handle ?: return
        h.launch(Overlay.hooks.appIntent(h.sessionContext(), conversationId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openVoiceSettings() {
        val h = handle ?: return
        val intent = Overlay.hooks.voiceSettingsIntent(h.sessionContext()) ?: return
        h.launch(intent)
    }

    fun useKeyboard() {
        voice.cancel()
        voice.clearError()
        keyboard = true
    }

    fun useVoice() {
        val h = handle ?: return
        keyboard = false
        voice.start(h.sessionContext())
    }

    // ------------------------------------------------------------------ Runs

    /** The overlay leaves before the agent touches the screen, and comes back with its own run's result. */
    fun hideForAgent(conversationId: String): Boolean {
        if (!visible) return false
        if (conversationId == this.conversationId) hiddenFor = conversationId
        voice.cancel()
        handle?.dismiss()
        return true
    }

    /** True when the result is (or now will be) on screen in the overlay. */
    fun onFinished(run: HostRun): Boolean {
        if (run.conversationId != conversationId) return false
        val answer = run.text.trim()
        val message = when {
            run.phase == RunPhase.Failed -> run.error?.takeIf { it.isNotBlank() } ?: OverlayRuntime.app.getString(R.string.overlay_failed)
            answer.isNotEmpty() -> answer
            run.phase == RunPhase.Cancelled -> OverlayRuntime.app.getString(R.string.overlay_stopped)
            else -> null
        }
        if (message != null) messages += OverlayMessage(fromUser = false, text = message)
        val reshown = if (hiddenFor == run.conversationId && run.phase != RunPhase.Cancelled) {
            hiddenFor = null
            AssistOverlay.reshow(Bundle().apply { putString(AssistOverlay.EXTRA_RESUME_CONVERSATION, run.conversationId) })
        } else {
            hiddenFor = null
            false
        }
        val shown = visible || reshown
        if (shown && run.phase == RunPhase.Done && answer.isNotEmpty() && OverlayRuntime.prefs.autoSpeak.value && Overlay.readAloud.available) {
            Overlay.readAloud.speak(Markdown.plain(answer))
        }
        return shown
    }

    private fun reset() {
        voice.cancel()
        Overlay.readAloud.stop()
        captureJob?.cancel()
        queueTimer?.cancel()
        conversationId = null
        messages.clear()
        screen = null
        screenshot = null
        screenshotPath = null
        includeScreen = true
        contextSent = false
        queued = null
        draft = ""
        notice = null
        hiddenFor = null
    }

    private fun save(bitmap: Bitmap, dir: File, at: Long): String? = runCatching {
        dir.mkdirs()
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_SCREENSHOTS - 1)?.forEach { it.delete() }
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val file = File(dir, "screen-$at.jpg")
        FileOutputStream(file).use { readable.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        file.absolutePath
    }.getOrNull()

    private const val CAPTURE_WAIT_MS = 1_500L
    private const val KEEP_SCREENSHOTS = 3
}
