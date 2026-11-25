package atom.xposed.hook

import android.content.SharedPreferences
import android.util.Log
import atom.xposed.HookConfig
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Everything a hook needs inside a hooked process: the framework, the user's settings and "fail silently, log once"
 * guards. Every hook goes through [guard] so a ROM that lacks a class or method costs one log line, never a crash.
 */
internal class HookEnv(val xposed: XposedInterface, val process: String) {
    private val logged = HashSet<String>()

    /** Kept strongly: SharedPreferences hold listeners weakly. */
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val configListeners = ArrayList<(HookConfig) -> Unit>()

    private val prefs: SharedPreferences? by lazy {
        try {
            xposed.getRemotePreferences(HookConfig.GROUP)
        } catch (t: Throwable) {
            logOnce("prefs", "Remote preferences unavailable; every hook stays off", t)
            null
        }
    }

    @Volatile
    private var cached: HookConfig? = null

    /** The user's settings; re-read on change where the framework supports listeners, else on every call. */
    val config: HookConfig
        get() {
            cached?.let { return it }
            val p = prefs ?: return HookConfig()
            val c = HookConfig.fromMap(runCatching { p.all }.getOrNull())
            if (prefsListener != null) cached = c
            return c
        }

    val modulePackage: String get() = xposed.moduleApplicationInfo.packageName

    /** Runs [block] when the settings change (and [config] is then served from cache). */
    @Synchronized
    fun onConfigChanged(block: (HookConfig) -> Unit) {
        configListeners += block
        if (prefsListener != null) return
        val p = prefs ?: return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { sp, _ ->
            val c = HookConfig.fromMap(runCatching { sp.all }.getOrNull())
            cached = c
            configListeners.toList().forEach { cb -> guard("config-listener") { cb(c) } }
        }
        try {
            p.registerOnSharedPreferenceChangeListener(l)
            prefsListener = l
        } catch (t: Throwable) {
            logOnce("prefs-listener", "Settings changes apply on next use only", t)
        }
    }

    fun log(msg: String) = xposed.log(Log.INFO, TAG, "[$process] $msg")

    @Synchronized
    fun logOnce(key: String, msg: String, t: Throwable? = null) {
        if (!logged.add(key)) return
        if (t == null) xposed.log(Log.INFO, TAG, "[$process] $msg") else xposed.log(Log.WARN, TAG, "[$process] $msg: ${t.javaClass.simpleName}: ${t.message}")
    }

    inline fun guard(key: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logOnce(key, "$key skipped (ROM mismatch?)", t)
        }
    }

    /** Hooks every method called [name] declared directly on [cls]. Returns how many were hooked. */
    fun hookDeclared(cls: Class<*>, name: String, key: String, hooker: XposedInterface.Hooker): Int {
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != name) continue
            guard("$key:${cls.simpleName}.${m.name}") {
                xposed.hook(m as Executable).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker)
                n++
            }
        }
        return n
    }

    fun loadClass(loader: ClassLoader, name: String): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (t: Throwable) {
        null
    }

    companion object {
        const val TAG = "AtomXposed"
    }
}

/** Reflection helpers that walk the class hierarchy and never throw. */
internal object Reflect {
    fun field(obj: Any, name: String): Field? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    fun get(obj: Any, name: String): Any? = try {
        field(obj, name)?.get(obj)
    } catch (t: Throwable) {
        null
    }

    fun set(obj: Any, name: String, value: Any?): Boolean = try {
        val f = field(obj, name)
        if (f == null) false else {
            f.set(obj, value)
            true
        }
    } catch (t: Throwable) {
        false
    }

    /** First method named [name] in [cls]'s hierarchy accepting exactly [argc] parameters. */
    fun method(cls: Class<*>, name: String, argc: Int): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == argc }?.let { return it.apply { isAccessible = true } }
            c = c.superclass
        }
        return cls.methods.firstOrNull { it.name == name && it.parameterCount == argc }
    }

    fun staticInt(className: String, field: String, loader: ClassLoader? = null): Int? = try {
        Class.forName(className, false, loader).getDeclaredField(field).apply { isAccessible = true }.getInt(null)
    } catch (t: Throwable) {
        null
    }

    fun service(name: String): Any? = try {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name)
    } catch (t: Throwable) {
        null
    }
}
