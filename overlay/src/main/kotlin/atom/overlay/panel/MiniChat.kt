package atom.overlay.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import atom.overlay.Overlay
import atom.overlay.OverlayMessage
import atom.overlay.OverlayRuntime
import atom.overlay.R
import atom.overlay.RunHub
import atom.overlay.bubble.Bubble
import atom.overlay.ui.Markdown
import atom.overlay.ui.VoiceInput
import atom.overlay.window.OverlayWindow
import atom.overlay.window.Windows
import atom.ui.feature.HostRun
import atom.ui.feature.RunPhase
import atom.ui.feature.RunSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The bubble's mini chat: one ongoing conversation (kept across restarts, cleared only by New chat) running the
 * full agent through the host. The window exists only while the panel is open. Main thread only.
 */
internal object MiniChat {
    private var window: OverlayWindow? = null
    private var hiddenFor: String? = null
    private var closeReceiver: BroadcastReceiver? = null
    private var sendHeard = false

    private val prefs get() = OverlayRuntime.prefs

    private val _log by lazy { MutableStateFlow(PanelLog.decode(prefs.bubbleLog)) }
    val log: StateFlow<List<OverlayMessage>> by lazy { _log.asStateFlow() }

    var conversationId by mutableStateOf<String?>(null)
        private set
    private var loadedConversation = false

    var draft by mutableStateOf("")
    var notice by mutableStateOf<String?>(null)
        private set
    var blurred by mutableStateOf(false)
        private set

    val voice = VoiceInput { heard ->
        if (sendHeard) {
            sendHeard = false
            send(heard)
        } else {
            draft = if (draft.isBlank()) heard else draft.trimEnd() + " " + heard
        }
    }

    val isOpen: Boolean get() = window != null

    fun open(listen: Boolean = false) {
        ensureConversation()
        val existing = window
        if (existing != null) {
            if (listen) listen(existing.view.context, send = true)
            return
        }
        RunHub.start()
        val slot = Windows.appOverlay() ?: return
        val blur = Windows.blurAvailable(slot.context)
        blurred = blur
        val flags = if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LayoutParams.FLAG_BLUR_BEHIND else 0
        val params = OverlayWindow.params(slot.type, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, flags).apply {
            // Still what makes a non-activity window receive IME insets instead of being panned.
            @Suppress("DEPRECATION")
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_RESIZE or LayoutParams.SOFT_INPUT_STATE_HIDDEN
            if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = (16 * slot.context.resources.displayMetrics.density).roundToInt()
            }
        }
        val w = OverlayWindow(slot.context, params, onBack = {
            android.util.Log.i("AtomOverlay", "Back closes the mini chat")
            close()
            true
        }) { MiniChatSheet() }
        if (!w.show()) return
        window = w
        hiddenFor = null
        notice = null
        Bubble.onPanel(true)
        registerCloseReceiver(slot.context)
        refreshTranscript()
        if (listen) listen(slot.context, send = true)
    }

    fun close() {
        voice.cancel()
        sendHeard = false
        removeWindow()
    }

    fun listen(context: Context, send: Boolean) {
        sendHeard = send
        voice.toggle(context)
    }

    fun newChat() {
        voice.cancel()
        conversationId = null
        prefs.bubbleConversation = null
        _log.value = emptyList()
        prefs.bubbleLog = null
        draft = ""
        notice = null
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        val host = OverlayRuntime.host()
        if (host == null) {
            notice = OverlayRuntime.app.getString(R.string.overlay_not_ready)
            return
        }
        RunHub.start()
        notice = null
        val id = conversationId
        val running = id != null && host.runs.value.any { it.conversationId == id && it.phase.active }
        if (running) {
            host.steer(id, prompt)
        } else {
            // Same block as the home chat: no run starts on a model that is not set up.
            val chip = Overlay.hooks.modelChip(RunSource.Bubble)?.value
            if (chip != null && !chip.ready) {
                notice = chip.blocked ?: OverlayRuntime.app.getString(R.string.overlay_not_ready)
                return
            }
            val started = host.start(prompt, id, RunSource.Bubble)
            when {
                started == null && id != null -> host.steer(id, prompt)
                started == null -> {
                    notice = OverlayRuntime.app.getString(R.string.overlay_not_ready)
                    return
                }
                started != id -> {
                    conversationId = started
                    prefs.bubbleConversation = started
                }
            }
        }
        append(OverlayMessage(fromUser = true, text = prompt))
        draft = ""
    }

    fun stop() {
        val id = conversationId ?: return
        OverlayRuntime.host()?.stop(id)
    }

    fun answer(approvalId: Long, approved: Boolean) {
        OverlayRuntime.host()?.answer(approvalId, approved)
    }

    fun expand() {
        val ctx = window?.view?.context ?: OverlayRuntime.app
        runCatching { ctx.startActivity(Overlay.hooks.appIntent(ctx, conversationId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        close()
    }

    fun openModelPicker() {
        val ctx = window?.view?.context ?: return
        val intent = Overlay.hooks.modelPickerIntent(ctx, RunSource.Bubble) ?: return
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        close()
    }

    fun openVoiceSettings() {
        val ctx = window?.view?.context ?: return
        val intent = Overlay.hooks.voiceSettingsIntent(ctx) ?: return
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        close()
    }

    /** Gets out of the way while the agent uses the screen; it comes back with the result of its own run. */
    fun hideForAgent(conversationId: String): Boolean {
        if (window == null) return false
        voice.cancel()
        hiddenFor = conversationId
        removeWindow()
        return true
    }

    /** True when the panel reopened to present [run]'s result. */
    fun onFinished(run: HostRun): Boolean {
        if (run.conversationId == conversationId && run.source == RunSource.Bubble) {
            val answer = run.text.trim()
            val message = when {
                run.phase == RunPhase.Failed -> run.error?.takeIf { it.isNotBlank() } ?: OverlayRuntime.app.getString(R.string.overlay_failed)
                answer.isNotEmpty() -> answer
                run.phase == RunPhase.Cancelled -> OverlayRuntime.app.getString(R.string.overlay_stopped)
                else -> null
            }
            if (message != null) append(OverlayMessage(fromUser = false, text = message))
        }
        if (hiddenFor == run.conversationId) {
            hiddenFor = null
            if (run.conversationId == conversationId && run.phase != RunPhase.Cancelled) {
                open()
                return true
            }
        }
        return false
    }

    /** Plain preview of the latest answer, for the result card. */
    fun preview(run: HostRun): String = Markdown.plain(run.text).take(240)

    private fun ensureConversation() {
        if (loadedConversation) return
        loadedConversation = true
        conversationId = prefs.bubbleConversation
    }

    private fun refreshTranscript() {
        val id = conversationId ?: return
        OverlayRuntime.scope.launch {
            val messages = runCatching { Overlay.hooks.transcript(id) }.getOrNull() ?: return@launch
            if (conversationId == id) {
                _log.value = PanelLog.trim(messages)
                prefs.bubbleLog = PanelLog.encode(_log.value)
            }
        }
    }

    private fun append(message: OverlayMessage) {
        _log.value = PanelLog.append(_log.value, message)
        prefs.bubbleLog = PanelLog.encode(_log.value)
    }

    private fun removeWindow() {
        unregisterCloseReceiver()
        val w = window ?: return
        window = null
        w.remove()
        Bubble.onPanel(false)
    }

    private fun registerCloseReceiver(context: Context) {
        if (closeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getStringExtra("reason")) {
                    "homekey", "recentapps", "lock" -> {
                        android.util.Log.i("AtomOverlay", "mini chat closed by system: ${intent.getStringExtra("reason")}")
                        close()
                    }
                }
            }
        }
        @Suppress("DEPRECATION")
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        runCatching { ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
            .onSuccess { closeReceiver = receiver }
    }

    private fun unregisterCloseReceiver() {
        val receiver = closeReceiver ?: return
        closeReceiver = null
        runCatching { OverlayRuntime.app.unregisterReceiver(receiver) }
    }
}
