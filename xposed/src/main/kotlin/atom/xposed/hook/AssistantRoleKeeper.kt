package atom.xposed.hook

import android.app.role.RoleManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import atom.xposed.RetryBudget
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * "Automatically set default assistant" (system_server): keeps the assistant role on Atom (or on the Google app
 * when the power-key target is Gemini). Event-driven: checked at boot, when the setting changes and when the role
 * holders change (system_server may observe roles). Repairs are rate-limited so a ROM that insists on its own
 * assistant cannot cause a loop, and the user's way out is simply turning the setting off.
 */
internal object AssistantRoleKeeper {
    private const val ROLE = RoleManager.ROLE_ASSISTANT
    private val main = Handler(Looper.getMainLooper())
    private val budget = RetryBudget(maxAttempts = 3, windowMs = 10 * 60_000L, delaysMs = listOf(1_500L, 10_000L, 60_000L))
    private lateinit var env: HookEnv
    private lateinit var context: Context
    private var listener: Any? = null
    private val ensure = Runnable { ensureNow() }

    fun install(env: HookEnv, context: Context) {
        this.env = env
        this.context = context
        env.guard("role-listener") { listen() }
        env.onConfigChanged { if (it.autoDefaultAssistant) schedule(500) }
        schedule(5_000) // after boot settles
    }

    private fun user(): UserHandle = try {
        val id = Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
        UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, id) as UserHandle
    } catch (t: Throwable) {
        Process.myUserHandle()
    }

    private fun listen() {
        val roles = context.getSystemService(RoleManager::class.java) ?: return
        val iface = Class.forName("android.app.role.OnRoleHoldersChangedListener")
        val l = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
            when (method.name) {
                "onRoleHoldersChanged" -> {
                    if (args?.firstOrNull() == ROLE && env.config.autoDefaultAssistant) schedule(null)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        val all = UserHandle::class.java.getField("ALL").get(null) as UserHandle
        RoleManager::class.java.getMethod("addOnRoleHoldersChangedListenerAsUser", Executor::class.java, iface, UserHandle::class.java)
            .invoke(roles, context.mainExecutor, l, all)
        listener = l
    }

    /** [delayMs] null = use the retry budget's backoff. */
    private fun schedule(delayMs: Long?) {
        main.removeCallbacks(ensure)
        val d = delayMs ?: budget.nextDelay(SystemClock.elapsedRealtime()) ?: run {
            env.logOnce("role-budget", "Assistant role keeps being changed; pausing repairs for a while")
            return
        }
        main.postDelayed(ensure, d)
    }

    private fun ensureNow() = env.guard("role-ensure") {
        val cfg = env.config
        if (!cfg.autoDefaultAssistant) return@guard
        val want = cfg.assistantPackage(env.modulePackage)
        val roles = context.getSystemService(RoleManager::class.java) ?: return@guard
        val u = user()
        @Suppress("UNCHECKED_CAST")
        val holders = RoleManager::class.java.getMethod("getRoleHoldersAsUser", String::class.java, UserHandle::class.java)
            .invoke(roles, ROLE, u) as List<String>
        if (want in holders) return@guard
        if (budget.nextDelay(SystemClock.elapsedRealtime()) == null) return@guard
        budget.consume(SystemClock.elapsedRealtime())
        val callback = Consumer<Boolean> { ok -> if (!ok) env.logOnce("role-failed", "The system refused $want as assistant (does it qualify?)") }
        RoleManager::class.java.getMethod(
            "addRoleHolderAsUser", String::class.java, String::class.java, Int::class.javaPrimitiveType,
            UserHandle::class.java, Executor::class.java, Consumer::class.java,
        ).invoke(roles, ROLE, want, 0, u, context.mainExecutor, callback)
        env.log("Assistant role set to $want")
    }
}
