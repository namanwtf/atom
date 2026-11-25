package atom.device

import android.content.Context
import android.content.pm.PackageManager
import atom.device.root.RootAccess
import atom.device.shell.PersistentShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** How much privilege shell commands run with. [App] is Atom's own sandboxed user: always available. */
enum class ShellTier { App, Shizuku, Root }

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

interface ShellExecutor {
    val tier: ShellTier
    suspend fun run(command: String, timeoutMs: Long = 30_000): ShellResult
}

enum class ShizukuState(val hint: String) {
    Ready("Shizuku is running and Atom is allowed."),
    NotRunning("Shizuku is not running. Start it in the Shizuku app (wireless debugging or root)."),
    TooOld("The installed Shizuku is too old. Update Shizuku."),
    NoPermission("Shizuku is running but Atom is not allowed yet. Tap Authorize."),
}

object Shells {
    private val mutex = Mutex()
    private var cached: PersistentShell? = null

    private val _tier = MutableStateFlow<ShellTier?>(null)

    /** Tier of the last detection; null until something asked for a shell. */
    val tier: StateFlow<ShellTier?> = _tier.asStateFlow()

    /**
     * Root (granted via [RootAccess], probed once) beats Shizuku (binder alive + permission granted) beats the app's
     * own shell. The same executor is returned while the tier is unchanged, so every caller shares one process.
     */
    suspend fun detect(context: Context): ShellExecutor = mutex.withLock {
        val wanted = when {
            RootAccess.ensure(context) -> ShellTier.Root
            shizukuState() == ShizukuState.Ready -> ShellTier.Shizuku
            else -> ShellTier.App
        }
        cached?.takeIf { it.tier == wanted }?.let { return it }
        cached?.close()
        val shell = when (wanted) {
            ShellTier.Root -> PersistentShell(ShellTier.Root) { ProcessBuilder("su").start() }
            ShellTier.Shizuku -> PersistentShell(ShellTier.Shizuku) { ShizukuProcess.start("sh") }
            ShellTier.App -> PersistentShell(ShellTier.App) { ProcessBuilder("/system/bin/sh").start() }
        }
        cached = shell
        _tier.value = wanted
        shell
    }

    @Volatile
    private var waitedForBinder = false

    suspend fun shizukuState(): ShizukuState {
        // The binder arrives asynchronously from ShizukuProvider at app start; wait for it once per process only,
        // so phones without Shizuku do not pay the wait on every tool call.
        val alive = try {
            Shizuku.pingBinder() || (!waitedForBinder && awaitShizukuBinder(1_500).also { waitedForBinder = true })
        } catch (e: Throwable) {
            false
        }
        if (!alive) return ShizukuState.NotRunning
        return try {
            when {
                Shizuku.isPreV11() -> ShizukuState.TooOld
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.Ready
                else -> ShizukuState.NoPermission
            }
        } catch (e: Throwable) {
            ShizukuState.NotRunning
        }
    }

    /** Shows Shizuku's own permission dialog; returns whether Atom was allowed. */
    suspend fun requestShizuku(): Boolean = withTimeoutOrNull(60_000) {
        suspendCancellableCoroutine { cont ->
            val code = 4217
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != code) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            try {
                Shizuku.addRequestPermissionResultListener(listener)
                Shizuku.requestPermission(code)
            } catch (e: Throwable) {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
        }
    } ?: false

    private suspend fun awaitShizukuBinder(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    if (cont.isActive) cont.resume(true)
                }
            }
            Shizuku.addBinderReceivedListenerSticky(listener)
            cont.invokeOnCancellation { Shizuku.removeBinderReceivedListener(listener) }
        }
    } ?: false
}

/** Shizuku 13 keeps newProcess private (it nudges towards UserService); it is still the lightest path. */
internal object ShizukuProcess {
    private val method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    fun start(vararg cmd: String): Process = method.invoke(null, cmd, null, null) as Process
}
