package atom.xposed

import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-side view of the Xposed framework. When the user has enabled Atom as a module, the framework (LSPosed or a
 * compatible fork) hands the app a service binder through `io.github.libxposed.service.XposedProvider`; that is the
 * "connected" signal. Connection proves the framework sees the module, not that every scope is enabled or that the
 * hooked processes have restarted since: [Connection.Connected.scope] and [Connection.Connected.hookedProcesses]
 * say more, and the settings screen explains the difference.
 *
 * Event-driven only: the framework calls us; nothing polls.
 */
object ModuleStatus {
    data class Framework(
        val name: String,
        val version: String,
        val api: Int,
        /** Packages the user enabled for the module ("system" is system_server). */
        val scope: List<String>,
        /** Process names currently running module code (API 102+), or null when the framework cannot say. */
        val hookedProcesses: List<String>?,
        val remotePrefs: Boolean,
    )

    sealed interface Connection {
        data object NotConnected : Connection
        data class Connected(val framework: Framework) : Connection
    }

    private const val TAG = "AtomXposed"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<Connection>(Connection.NotConnected)
    val state: StateFlow<Connection> = _state.asStateFlow()

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var pending: HookConfig? = null

    @Volatile
    private var started = false

    /** Call once from Application.onCreate. Cheap: registers a listener, starts nothing. */
    @Synchronized
    fun init() {
        if (started) return
        started = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                this@ModuleStatus.service = service
                scope.launch {
                    pending?.let { write(service, it) }
                    refreshNow()
                }
            }

            override fun onServiceDied(service: XposedService) {
                if (this@ModuleStatus.service === service) {
                    this@ModuleStatus.service = null
                    _state.value = Connection.NotConnected
                }
            }
        })
    }

    /** Re-reads scope and hooked processes (for the settings screen's "Check again"). */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    /** Sends [config] to the hooked processes now, or as soon as the framework connects. */
    fun push(config: HookConfig) {
        pending = config
        val s = service ?: return
        scope.launch { write(s, config) }
    }

    /** Asks the framework (it shows its own prompt) to add [packages] to the module scope. */
    fun requestScope(packages: List<String>, done: (Boolean, String?) -> Unit) {
        val s = service ?: return done(false, null)
        scope.launch {
            try {
                s.requestScope(packages, object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        refresh()
                        done(true, null)
                    }

                    override fun onScopeRequestFailed(message: String) = done(false, message)
                })
            } catch (t: Throwable) {
                done(false, t.message)
            }
        }
    }

    private fun write(s: XposedService, config: HookConfig) {
        try {
            val editor = s.getRemotePreferences(HookConfig.GROUP).edit()
            for ((k, v) in config.toMap()) {
                when (v) {
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is String -> editor.putString(k, v)
                }
            }
            editor.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write hook settings: ${t.message}")
        }
    }

    private fun refreshNow() {
        val s = service ?: return
        _state.value = try {
            val api = s.apiVersion
            val hooked = if (api >= XposedService.API_102) {
                runCatching { s.runningTargets.map { it.processName } }.getOrNull()
            } else {
                null
            }
            Connection.Connected(
                Framework(
                    name = s.frameworkName,
                    version = s.frameworkVersion,
                    api = api,
                    scope = runCatching { s.scope }.getOrDefault(emptyList()),
                    hookedProcesses = hooked,
                    remotePrefs = (s.frameworkProperties and XposedService.PROP_CAP_REMOTE) != 0L,
                ),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Framework query failed: ${t.message}")
            Connection.NotConnected
        }
    }
}
