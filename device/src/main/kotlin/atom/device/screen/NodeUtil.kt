package atom.device.screen

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Thrown inside UI operations; becomes a tool error with this exact message. */
internal class UiError(message: String) : Exception(message)

/** recycle() matters on API 30-32 (pooled instances) and is a deprecated no-op from 33. */
@Suppress("DEPRECATION")
internal fun AccessibilityNodeInfo.release() {
    if (Build.VERSION.SDK_INT < 33) runCatching { recycle() }
}

@Suppress("DEPRECATION")
internal fun AccessibilityWindowInfo.release() {
    if (Build.VERSION.SDK_INT < 33) runCatching { recycle() }
}

internal fun AccessibilityNodeInfo.box(): Box {
    val r = Rect()
    getBoundsInScreen(r)
    return Box(r.left, r.top, r.right, r.bottom)
}

/** Short human label for outcome lines. */
internal fun AccessibilityNodeInfo.shortLabel(): String {
    val t = (text?.takeUnless { isShowingHintText } ?: contentDescription ?: hintText)?.toString()
        ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    return if (t.isEmpty()) "" else " \"" + (if (t.length > 40) t.take(39) + "…" else t) + "\""
}

/** What a node "is", used to notice that a cached node now shows different content (recycled list row). */
internal fun AccessibilityNodeInfo.fingerprint(): String =
    "${className}|${viewIdResourceName}|${text?.takeUnless { isShowingHintText }}|${contentDescription}"

/** Collects nodes obtained during one operation and releases them together. */
internal class NodeBag {
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    fun <T : AccessibilityNodeInfo?> add(n: T): T {
        if (n != null) nodes += n
        return n
    }

    fun releaseAll() {
        nodes.forEach { it.release() }
        nodes.clear()
    }
}

internal inline fun <T> withBag(block: (NodeBag) -> T): T {
    val bag = NodeBag()
    try {
        return block(bag)
    } finally {
        bag.releaseAll()
    }
}
