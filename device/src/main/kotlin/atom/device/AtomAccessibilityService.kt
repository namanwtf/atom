package atom.device

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import atom.device.screen.UiController

/**
 * Atom's eyes and hands. It does no work of its own: tools read the tree and act on demand, and events
 * are subscribed to only while an agent task is running (see [Accessibility.setTaskActive]).
 */
class AtomAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Accessibility.attach(this)
        applyEventMask(Accessibility.taskActive)
        Accessibility.serviceHook?.let { runCatching { it.onConnected(this) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Automation "app opened" triggers (cheap: returns at once when nobody watches).
            val pkg = event.packageName?.toString()
            if (pkg != null) Accessibility.appOpenHook?.let { if (it.watching) runCatching { it.onAppOpened(pkg) } }
        }
        // Our own UI (chat streaming, overlays) must not keep the screen from "settling".
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> Accessibility.settle.signal()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        Accessibility.serviceHook?.let { runCatching { it.onDisconnected(this) } }
        Accessibility.detach(this)
        UiController.reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Accessibility.serviceHook?.let { runCatching { it.onDisconnected(this) } }
        Accessibility.detach(this)
        UiController.reset()
        super.onDestroy()
    }

    internal fun applyEventMask(active: Boolean) {
        mainExecutor.execute {
            val info = serviceInfo ?: return@execute
            val appWatch = Accessibility.appOpenHook?.watching == true
            val wanted = (if (active) ACTIVE_EVENTS else 0) or (if (appWatch) AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED else 0)
            if (info.eventTypes == wanted && info.notificationTimeout == NOTIFICATION_TIMEOUT_MS) return@execute
            info.eventTypes = wanted
            info.notificationTimeout = NOTIFICATION_TIMEOUT_MS
            serviceInfo = info
        }
    }

    private companion object {
        const val ACTIVE_EVENTS =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        const val NOTIFICATION_TIMEOUT_MS = 150L
    }
}
