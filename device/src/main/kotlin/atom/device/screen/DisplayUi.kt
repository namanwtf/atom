package atom.device.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.accessibility.AccessibilityNodeInfo
import atom.device.Accessibility
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The accessibility service on displays other than the main one (the background virtual display). Every call
 * returns null / false when the service is not connected or the system refuses, so callers can fall back.
 * Nothing here keeps node references between calls.
 */
object DisplayUi {
    private const val MAX_NODES = 1_500
    private const val MAX_DEPTH = 64

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1)

    /** Outline of the windows on [displayId], newest layer first, or null when nothing can be read. */
    suspend fun readTree(displayId: Int, maxChars: Int): String? {
        val svc = Accessibility.service ?: return null
        return withContext(io) {
            val all = runCatching { svc.windowsOnAllDisplays }.getOrNull() ?: return@withContext null
            val windows = all.get(displayId) ?: return@withContext null
            try {
                val roots = windows.sortedByDescending { it.layer }.mapNotNull { it.root }
                if (roots.isEmpty()) return@withContext null
                val raw = ArrayList<AccessibilityNodeInfo>()
                try {
                    val trees = roots.map { snapshot(it, raw, 0) }
                    val screen = screenBox(svc, displayId) ?: trees.first().bounds
                    val result = ScreenRenderer.render(trees, screen, maxChars)
                    buildString {
                        append("display: ").append(displayId)
                        roots.firstOrNull()?.packageName?.let { append("  app: ").append(it) }
                        append("  screen: ").append(screen.width).append('x').append(screen.height)
                        append('\n').append(result.text)
                    }
                } finally {
                    raw.forEach { it.release() }
                }
            } finally {
                for (i in 0 until all.size()) all.valueAt(i).forEach { it.release() }
            }
        }
    }

    /** A software bitmap of [displayId] (caller recycles it), or null. */
    suspend fun screenshot(displayId: Int): Bitmap? {
        val svc = Accessibility.service ?: return null
        val result = suspendCancellableCoroutine<AccessibilityService.ScreenshotResult?> { cont ->
            runCatching {
                svc.takeScreenshot(
                    displayId,
                    svc.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            if (cont.isActive) cont.resume(result) else result.hardwareBuffer.close()
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.onFailure { if (cont.isActive) cont.resume(null) }
        } ?: return null
        return withContext(Dispatchers.Default) {
            val buffer = result.hardwareBuffer
            try {
                val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@withContext null
                try {
                    hw.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hw.recycle()
                }
            } finally {
                buffer.close()
            }
        }
    }

    suspend fun tap(displayId: Int, x: Int, y: Int, long: Boolean): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(displayId, path, if (long) 700 else 60)
    }

    suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(displayId, path, durationMs.coerceIn(50, 5_000))
    }

    private suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        val svc = Accessibility.service ?: return false
        return suspendCancellableCoroutine { cont ->
            val accepted = runCatching {
                val g = GestureDescription.Builder()
                    .setDisplayId(displayId)
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
                svc.dispatchGesture(
                    g,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(d: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
            }.getOrDefault(false)
            if (!accepted && cont.isActive) cont.resume(false)
        }
    }

    private fun screenBox(svc: AccessibilityService, displayId: Int): Box? {
        val display = svc.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return if (size.x > 0 && size.y > 0) Box(0, 0, size.x, size.y) else null
    }

    private fun snapshot(n: AccessibilityNodeInfo, raw: MutableList<AccessibilityNodeInfo>, depth: Int): UiNode {
        val handle = raw.size
        raw += n
        val visible = n.isVisibleToUser
        val children = if (visible && depth < MAX_DEPTH) {
            (0 until n.childCount).mapNotNull { i ->
                if (raw.size >= MAX_NODES) null else n.getChild(i)?.let { snapshot(it, raw, depth + 1) }
            }
        } else {
            emptyList()
        }
        @Suppress("DEPRECATION")
        return UiNode(
            handle = handle,
            bounds = n.box(),
            className = n.className?.toString(),
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            hint = n.hintText?.toString(),
            viewId = n.viewIdResourceName,
            visible = visible,
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            selected = n.isSelected,
            focused = n.isFocused,
            enabled = n.isEnabled,
            password = n.isPassword,
            showingHint = n.isShowingHintText,
            children = children,
        )
    }
}
