package atom.device.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import atom.core.tool.ToolResult
import atom.device.Accessibility
import atom.device.AtomAccessibilityService
import atom.device.ShellExecutor
import atom.device.ShellTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * All screen reads and actions. Tree work runs on one background thread (accessibility calls are
 * blocking binder IPC and must not touch the main thread); that thread also owns the id map, the only
 * place live [AccessibilityNodeInfo]s are kept, and only until the next read.
 */
internal object UiController {
    const val NOT_CONNECTED =
        "Atom's accessibility service is not running, so the screen cannot be read or controlled. " +
            "Ask the user to enable Atom in Settings > Accessibility, then try again."

    private const val MAX_NODES = 1_500
    private const val MAX_DEPTH = 64

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ui = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ui)

    private class Ref(val node: AccessibilityNodeInfo, val fingerprint: String)

    /** id ("n12") to live node from the latest read. Touched only on [ui]. */
    private var registry: Map<String, Ref> = emptyMap()

    /** Used for key-event fallbacks when the accessibility API cannot submit. */
    @Volatile
    var shell: ShellExecutor? = null

    fun reset() {
        scope.launch { replaceRegistry(emptyMap()) }
    }

    private fun replaceRegistry(next: Map<String, Ref>) {
        registry.values.forEach { it.node.release() }
        registry = next
    }

    /** Runs [block] against the connected service; [UiError]s become tool errors. */
    suspend fun <T> withService(block: suspend (AtomAccessibilityService) -> T): Result<T> {
        val svc = Accessibility.service ?: return Result.failure(UiError(NOT_CONNECTED))
        return try {
            Result.success(block(svc))
        } catch (e: UiError) {
            Result.failure(e)
        }
    }

    /** The screen tools (read_screen, tap, type, scroll, swipe, global actions): they wait for an unlocked screen. */
    suspend fun tool(block: suspend (AtomAccessibilityService) -> ToolResult): ToolResult {
        val svc = Accessibility.service ?: return ToolResult.error(NOT_CONNECTED)
        // Agent runs wait for an unlocked screen before the call, outside its timeout (PhoneScreen). Here, inside the
        // timeout, only a quick check: a screen that locked in between fails clearly instead of waiting.
        if (!ScreenGate.usable(svc)) return ToolResult.error(ScreenGate.LOCKED_NOW)
        return withService(block).getOrElse { ToolResult.error(it.message ?: "UI action failed") }
    }

    // ---- reading -------------------------------------------------------------------------------------

    suspend fun read(svc: AtomAccessibilityService, maxChars: Int): String = withContext(ui) {
        val windows = runCatching { svc.windows }.getOrDefault(emptyList())
        try {
            val root = svc.rootInActiveWindow
                ?: windows.firstOrNull { it.isActive || it.isFocused }?.root
                ?: windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }?.root
                ?: throw UiError(
                    "No window could be read (screen locked, transitioning, or protected). " +
                        "Wait a moment, then call read_screen again.",
                )
            val screen = screenBox(svc)
            val raw = ArrayList<AccessibilityNodeInfo>()
            val tree = snapshot(root, raw, 0)
            val result = ScreenRenderer.render(listOf(tree), screen, maxChars)

            val keep = result.ids.mapValues { (_, handle) -> raw[handle].let { Ref(it, it.fingerprint()) } }
            val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>())
            keep.values.forEach { kept += it.node }
            raw.forEach { if (it !in kept) it.release() }
            replaceRegistry(keep)

            buildString {
                append("app: ").append(root.packageName ?: "unknown")
                append("  screen: ").append(screen.width).append('x').append(screen.height)
                if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) append("  keyboard: open")
                val others = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isActive }
                    .mapNotNull { it.title?.toString()?.takeIf(String::isNotBlank) }
                if (others.isNotEmpty()) append("\nother windows: ").append(others.joinToString(", "))
                append('\n').append(result.text)
            }
        } finally {
            windows.forEach { it.release() }
        }
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

    fun screenBox(svc: AccessibilityService): Box = try {
        val b = svc.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        Box(b.left, b.top, b.right, b.bottom)
    } catch (e: Exception) {
        val m = svc.resources.displayMetrics
        Box(0, 0, m.widthPixels, m.heightPixels)
    }

    suspend fun foreground(svc: AccessibilityService): String = withContext(ui) {
        val root = svc.rootInActiveWindow ?: return@withContext "unknown"
        try {
            root.packageName?.toString() ?: "unknown"
        } finally {
            root.release()
        }
    }

    // ---- node lookup ---------------------------------------------------------------------------------

    /** The cached node for [id], refreshed; throws a stale-id error the model can act on. */
    private fun resolve(id: String): AccessibilityNodeInfo {
        val key = id.trim().lowercase().removePrefix("#").let { if (it.startsWith("n")) it else "n$it" }
        val ref = registry[key] ?: throw UiError(
            "Unknown element id '$id'. Ids come from the latest read_screen only; call read_screen again.",
        )
        val n = ref.node
        if (!n.refresh() || !n.isVisibleToUser || n.fingerprint() != ref.fingerprint) {
            throw UiError("Element $key is stale (the screen changed since the last read). Call read_screen again for fresh ids.")
        }
        return n
    }

    /** [n] itself or its nearest ancestor matching [test]; ancestors obtained go into [bag]. */
    private fun selfOrAncestor(n: AccessibilityNodeInfo, bag: NodeBag, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var cur = n
        repeat(10) {
            if (test(cur)) return cur
            cur = bag.add(cur.parent) ?: return null
        }
        return null
    }

    private fun findDescendant(n: AccessibilityNodeInfo, bag: NodeBag, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(n) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 300) {
            val cur = queue.removeFirst()
            if (cur !== n && test(cur)) return cur
            for (i in 0 until cur.childCount) bag.add(cur.getChild(i))?.let(queue::add)
        }
        return null
    }

    // ---- actions -------------------------------------------------------------------------------------

    suspend fun tap(svc: AtomAccessibilityService, id: String?, x: Int?, y: Int?, long: Boolean): String {
        val verb = if (long) "Long-pressed" else "Tapped"
        if (id == null) {
            val (px, py) = requirePoint(svc, x, y)
            if (!tapAt(svc, px, py, long)) throw UiError("The system rejected the gesture at ($px,$py).")
            return "$verb ($px,$py)"
        }
        val target = withContext(ui) {
            withBag { bag ->
                val n = resolve(id)
                val label = n.shortLabel()
                val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                val actor = selfOrAncestor(n, bag) { if (long) it.isLongClickable else it.isClickable }
                if (actor != null && actor.isEnabled && actor.performAction(action)) {
                    TapPlan(done = "$verb $id$label")
                } else {
                    val c = n.box().clampTo(screenBox(svc))
                    TapPlan(fallback = c.centerX to c.centerY, done = "$verb $id$label by gesture at (${c.centerX},${c.centerY})")
                }
            }
        }
        target.fallback?.let { (px, py) ->
            if (!tapAt(svc, px, py, long)) throw UiError("Could not tap $id: neither the click action nor a gesture worked.")
        }
        return target.done
    }

    private class TapPlan(val done: String, val fallback: Pair<Int, Int>? = null)

    /**
     * Tap by area: the best element of the latest read_screen inside [region] (see [TapArea.best]); without one,
     * the centre of the region.
     */
    suspend fun tapArea(svc: AtomAccessibilityService, region: Box, long: Boolean): String {
        val verb = if (long) "Long-pressed" else "Tapped"
        val screen = screenBox(svc)
        val candidates = withContext(ui) {
            registry.mapNotNull { (id, ref) ->
                val n = ref.node
                if (!n.refresh() || !n.isVisibleToUser || n.fingerprint() != ref.fingerprint) {
                    null
                } else {
                    TapArea.Candidate(id, n.box(), clickable = if (long) n.isLongClickable else n.isClickable)
                }
            }
        }
        TapArea.best(region, candidates)?.let { return tap(svc, it.id, null, null, long) + " (best element in the area)" }
        val (x, y) = TapArea.centre(region, screen)
            ?: throw UiError("The area is outside the screen (${screen.width}x${screen.height}).")
        if (!tapAt(svc, x, y, long)) throw UiError("The system rejected the gesture at ($x,$y).")
        return "$verb the centre of the area at ($x,$y)"
    }

    private fun requirePoint(svc: AccessibilityService, x: Int?, y: Int?): Pair<Int, Int> {
        if (x == null || y == null) throw UiError("Give either an element id from read_screen, or both x and y.")
        val s = screenBox(svc)
        if (x < s.left || x >= s.right || y < s.top || y >= s.bottom) {
            throw UiError("($x,$y) is outside the screen (${s.width}x${s.height}).")
        }
        return x to y
    }

    private suspend fun tapAt(svc: AccessibilityService, x: Int, y: Int, long: Boolean): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(svc, path, if (long) 700 else 60)
    }

    suspend fun swipe(svc: AccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String {
        requirePoint(svc, x1, y1)
        requirePoint(svc, x2, y2)
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        if (!gesture(svc, path, durationMs.coerceIn(50, 5_000))) throw UiError("The system rejected the swipe gesture.")
        return "Swiped ($x1,$y1)→($x2,$y2) in ${durationMs}ms"
    }

    private suspend fun gesture(svc: AccessibilityService, path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = svc.dispatchGesture(
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
            if (!accepted && cont.isActive) cont.resume(false)
        }

    class Typed(val summary: String, val verified: Boolean)

    suspend fun typeText(svc: AtomAccessibilityService, id: String?, text: String, append: Boolean, submit: Boolean): Typed {
        var readBack = true
        val (summary, needsKeyEnter) = withContext(ui) {
            withBag { bag ->
                val start = if (id != null) {
                    resolve(id)
                } else {
                    bag.add(svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
                        ?: throw UiError("No text field is focused. Pass the id of a field marked 'edit' from read_screen.")
                }
                val field = if (start.isEditable) start else findDescendant(start, bag) { it.isEditable }
                    ?: throw UiError("${id ?: "The focused element"} is not a text field. Use an id marked 'edit' from read_screen.")
                if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
                val value = if (append) current + text else text
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                var how = ""
                if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    // Some custom fields ignore SET_TEXT but accept a paste over a full selection.
                    if (!pasteInto(svc, field, value, current.length)) {
                        throw UiError("The field refused the text (it accepts neither set-text nor paste). Try tapping it first, then type_text again.")
                    }
                    how = " via paste"
                }

                field.refresh()
                val shown = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
                val check = when {
                    field.isPassword -> ""
                    shown == value -> ""
                    else -> {
                        readBack = false
                        " (field now shows \"${shown.take(60)}\")"
                    }
                }
                var enter = false
                var submitted = ""
                if (submit) {
                    if (field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) {
                        submitted = " and pressed Enter"
                    } else {
                        enter = true
                    }
                }
                "Typed ${text.length} chars into ${id ?: "the focused field"}$how$check$submitted" to enter
            }
        }
        if (!needsKeyEnter) return Typed(summary, readBack)
        val sh = shell
        if (sh != null && sh.tier != ShellTier.App && sh.run("input keyevent 66", 5_000).exitCode == 0) {
            return Typed("$summary and pressed Enter", readBack)
        }
        return Typed("$summary, but Enter is not supported in this field; tap the app's send/search button instead", readBack)
    }

    /** Pastes the clipboard into [id] or the focused field and reads the field back. */
    suspend fun paste(svc: AtomAccessibilityService, id: String?): Typed = withContext(ui) {
        withBag { bag ->
            val start = if (id != null) {
                resolve(id)
            } else {
                bag.add(svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
                    ?: throw UiError("No text field is focused. Pass the id of a field marked 'edit' from read_screen.")
            }
            val field = if (start.isEditable) start else findDescendant(start, bag) { it.isEditable }
                ?: throw UiError("${id ?: "The focused element"} is not a text field.")
            if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val before = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
            if (!field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                throw UiError("The field refused to paste. Long-press it and tap Paste instead.")
            }
            field.refresh()
            val after = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
            val changed = field.isPassword || after != before
            Typed("Pasted into ${id ?: "the focused field"}" + if (changed) "" else " (the field text did not change)", changed)
        }
    }

    /** Whether any visible node's text or description contains [needle] (case-insensitive). */
    suspend fun screenContains(svc: AtomAccessibilityService, needle: String): Boolean = withContext(ui) {
        withBag { bag ->
            val root = bag.add(svc.rootInActiveWindow) ?: return@withBag false
            val q = needle.lowercase()
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (queue.isNotEmpty() && visited++ < MAX_NODES) {
                val n = queue.removeFirst()
                if (n.isVisibleToUser && !n.isPassword) {
                    val t = n.text?.toString()?.lowercase()
                    val d = n.contentDescription?.toString()?.lowercase()
                    if ((t != null && t.contains(q)) || (d != null && d.contains(q))) return@withBag true
                }
                for (i in 0 until n.childCount) bag.add(n.getChild(i))?.let(queue::add)
            }
            false
        }
    }

    private suspend fun pasteInto(svc: AccessibilityService, field: AccessibilityNodeInfo, value: String, currentLength: Int): Boolean {
        withContext(Dispatchers.Main) {
            svc.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Atom", value))
        }
        if (currentLength > 0) {
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, currentLength)
                },
            )
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    suspend fun scroll(svc: AtomAccessibilityService, id: String?, direction: String): String {
        val plan = withContext(ui) {
            withBag { bag ->
                val start = if (id != null) {
                    resolve(id)
                } else {
                    val root = bag.add(svc.rootInActiveWindow) ?: throw UiError("No window to scroll. Call read_screen.")
                    largestScrollable(root, bag)
                }
                val target = start?.let { selfOrAncestor(it, bag) { n -> n.isScrollable } }
                if (target == null) {
                    return@withBag ScrollPlan(gestureIn = start?.box() ?: screenBox(svc), done = "Scrolled $direction by swipe")
                }
                val directional = when (direction) {
                    "up" -> AccessibilityAction.ACTION_SCROLL_UP
                    "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
                    "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
                    else -> AccessibilityAction.ACTION_SCROLL_RIGHT
                }
                val generic = if (direction == "down" || direction == "right") {
                    AccessibilityAction.ACTION_SCROLL_FORWARD
                } else {
                    AccessibilityAction.ACTION_SCROLL_BACKWARD
                }
                val offered = target.actionList.map { it.id }
                val action = when {
                    directional.id in offered -> directional
                    generic.id in offered -> generic
                    else -> null
                }
                val label = target.shortLabel()
                when {
                    action == null -> ScrollPlan(gestureIn = target.box(), done = "Scrolled $direction by swipe")
                    target.performAction(action.id) -> ScrollPlan(done = "Scrolled$label $direction")
                    else -> throw UiError("Cannot scroll $direction any further (already at the end).")
                }
            }
        }
        plan.gestureIn?.let { area ->
            val a = area.clampTo(screenBox(svc))
            val (x1, y1, x2, y2) = swipeFor(direction, a)
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            if (!gesture(svc, path, 350)) throw UiError("The scroll gesture was rejected.")
        }
        return plan.done
    }

    private class ScrollPlan(val done: String, val gestureIn: Box? = null)

    /** Finger path for scrolling content in [direction]: the finger moves the opposite way. */
    private fun swipeFor(direction: String, a: Box): List<Int> {
        val l = a.left + a.width / 5
        val r = a.right - a.width / 5
        val t = a.top + a.height / 5
        val b = a.bottom - a.height / 5
        return when (direction) {
            "down" -> listOf(a.centerX, b, a.centerX, t)
            "up" -> listOf(a.centerX, t, a.centerX, b)
            "right" -> listOf(r, a.centerY, l, a.centerY)
            else -> listOf(l, a.centerY, r, a.centerY)
        }
    }

    private fun largestScrollable(root: AccessibilityNodeInfo, bag: NodeBag): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val cur = queue.removeFirst()
            if (cur.isScrollable && cur.isVisibleToUser) {
                val b = cur.box()
                val area = b.width.toLong() * b.height
                if (area > bestArea) {
                    best = cur
                    bestArea = area
                }
            }
            if (cur.isVisibleToUser) for (i in 0 until cur.childCount) bag.add(cur.getChild(i))?.let(queue::add)
        }
        return best
    }

    fun globalAction(svc: AccessibilityService, name: String): String {
        val action = when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> throw UiError("Unknown action '$name'. Use back, home, recents, notifications, quick_settings or lock_screen.")
        }
        if (!svc.performGlobalAction(action)) throw UiError("The system refused global action '$name'.")
        return "Performed $name"
    }

    /**
     * Waits for the screen to settle and formats the outcome every action returns: "done" when the screen visibly
     * reacted (or the expected app is in front), "submitted, unconfirmed" when nothing observable changed.
     */
    suspend fun outcome(svc: AccessibilityService, done: String, expectPackage: String? = null, verified: Boolean? = null): ToolResult {
        val settle = Accessibility.settle.awaitSettleDetailed()
        var fg = foreground(svc)
        if (expectPackage != null && fg != expectPackage && Accessibility.taskActive) {
            fg = awaitForeground(svc, expectPackage, 4_000) ?: foreground(svc)
        }
        val state = if (settle.settled) "screen settled" else "screen still changing after 3s"
        val result = when {
            verified == false -> "submitted, unconfirmed"
            expectPackage != null -> if (fg == expectPackage) "done" else "submitted, unconfirmed"
            verified == true -> "done"
            !Accessibility.taskActive -> "done"
            settle.changed -> "done"
            else -> "submitted, unconfirmed"
        }
        val note = when {
            expectPackage != null && fg == expectPackage -> " The $expectPackage window was seen."
            expectPackage != null -> " The $expectPackage window was not seen; it may still be loading, or another screen is in front. Call read_screen."
            result != "done" -> " Nothing visibly changed; call read_screen to check before acting again."
            else -> ""
        }
        return ToolResult.ok("Outcome: $result. $done; $state; foreground: $fg.$note")
    }

    /** Waits on window events (no polling) until [pkg] is in front; null on timeout. */
    suspend fun awaitForeground(svc: AccessibilityService, pkg: String, timeoutMs: Long): String? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            var seen = Accessibility.settle.events.value
            while (foreground(svc) != pkg) seen = Accessibility.settle.events.first { it != seen }
            pkg
        }
}
