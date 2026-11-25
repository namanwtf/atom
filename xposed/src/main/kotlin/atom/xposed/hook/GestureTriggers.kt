package atom.xposed.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import atom.xposed.LongPressGestures
import java.lang.reflect.Proxy

/**
 * Gesture-bar long press and two-finger long press → Circle to Search, ROM-agnostic. system_server already sends
 * every touch on the main display to its system-gesture listeners; this registers one more listener of that kind
 * (`WindowManagerService.registerPointerEventListener`). It only observes: the touches still reach the app and the
 * ROM's own gesture handling. Work per touch is a few comparisons, and nothing happens at all while both settings
 * are off.
 */
internal object GestureTriggers {
    private const val LISTENER = "android.view.WindowManagerPolicyConstants\$PointerEventListener"
    private const val DEFAULT_DISPLAY = 0

    private val detector = LongPressGestures()
    private val main = Handler(Looper.getMainLooper())
    private val check = Runnable { fire() }

    private lateinit var env: HookEnv
    private lateinit var context: Context

    fun install(env: HookEnv, context: Context) {
        this.env = env
        this.context = context
        env.guard("gesture-listener") {
            val wms = Reflect.service("window") ?: error("no window service")
            val iface = Class.forName(LISTENER, false, wms.javaClass.classLoader)
            val listener = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
                when (method.name) {
                    "onPointerEvent" -> {
                        (args?.firstOrNull() as? MotionEvent)?.let(::onEvent)
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "AtomCircleToSearchGestures"
                    else -> null
                }
            }
            val register = wms.javaClass.methods.first { it.name == "registerPointerEventListener" && it.parameterCount == 2 }
            register.invoke(wms, listener, DEFAULT_DISPLAY)
            env.logOnce("gesture-installed", "Circle to Search gesture listener registered")
        }
    }

    private fun onEvent(e: MotionEvent) {
        try {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(check)
                    val cfg = env.config
                    if (!cfg.anyCircleToSearch) return
                    val at = detector.onDown(e.x, e.y, e.eventTime, area(), cfg.gestureBarCircleToSearch, cfg.twoFingerCircleToSearch)
                    if (at != null) schedule(at, e.eventTime)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    val at = detector.onPointerDown(e.pointerCount, e.getX(i), e.getY(i), e.eventTime)
                    main.removeCallbacks(check)
                    if (at != null) schedule(at, e.eventTime)
                }
                MotionEvent.ACTION_MOVE ->
                    if (e.pointerCount >= 2) detector.onMove(e.getX(0), e.getY(0), e.getX(1), e.getY(1)) else detector.onMove(e.x, e.y)
                MotionEvent.ACTION_POINTER_UP -> {
                    detector.onPointerUp()
                    main.removeCallbacks(check)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    detector.reset()
                    main.removeCallbacks(check)
                }
            }
        } catch (t: Throwable) {
            env.logOnce("gesture-event", "Gesture tracking failed", t)
        }
    }

    private fun schedule(at: Long, eventTime: Long) {
        main.postDelayed(check, (at - eventTime).coerceAtLeast(0))
    }

    private fun fire() {
        val gesture = detector.check(SystemClock.uptimeMillis()) ?: return
        env.logOnce("gesture-$gesture", "Detected $gesture")
        CircleToSearch.trigger(env, context)
    }

    private fun area(): LongPressGestures.Area {
        val dm = context.resources.displayMetrics
        val res = context.resources
        val id = res.getIdentifier("navigation_bar_gesture_height", "dimen", "android")
            .takeIf { it != 0 } ?: res.getIdentifier("navigation_bar_height", "dimen", "android")
        val bar = if (id != 0) res.getDimension(id) else 0f
        val min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, dm)
        val (w, h) = realSize(dm.widthPixels, dm.heightPixels)
        return LongPressGestures.Area(w.toFloat(), h.toFloat(), maxOf(bar, min), ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    }

    /** Current (rotated) size of the main display; the system context's metrics do not follow rotation. */
    @Suppress("DEPRECATION")
    private fun realSize(fw: Int, fh: Int): Pair<Int, Int> = try {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(DEFAULT_DISPLAY)
        val p = android.graphics.Point()
        if (display != null) {
            display.getRealSize(p)
            p.x to p.y
        } else {
            fw to fh
        }
    } catch (t: Throwable) {
        fw to fh
    }
}
