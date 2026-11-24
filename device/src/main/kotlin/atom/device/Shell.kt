package atom.device

import android.content.Context
import android.content.pm.PackageManager
import atom.device.shell.PersistentShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** How much privilege shell commands run with. [App] is Atom's own sandboxed user: always available. */
enum class ShellTier { App, Shizuku, Root }

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

interface ShellExecutor {
    val tier: ShellTier
    suspend fun run(command: String, timeoutMs: Long = 30_000): ShellResult
}

object Shells {
    /** Root (`su -c id` works) beats Shizuku (binder alive + permission granted) beats the app's own shell. */
    suspend fun detect(context: Context): ShellExecutor {
        if (rootAvailable()) return PersistentShell(ShellTier.Root) { ProcessBuilder("su").start() }
        return when (val s = shizukuStatus()) {
            ShizukuStatus.Ready -> PersistentShell(ShellTier.Shizuku) { ShizukuProcess.start("sh") }
            else -> PersistentShell(ShellTier.App) { ProcessBuilder("/system/bin/sh").start() }
        }
    }

    private suspend fun rootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            p.outputStream.close()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return@withContext false
            }
            val out = p.inputStream.bufferedReader().readText()
            p.exitValue() == 0 && out.contains("uid=0")
        } catch (e: Exception) {
            false // no su binary, or denied
        }
    }

    private enum class ShizukuStatus(val hint: String) {
        Ready(""),
        NotRunning("Shizuku is not running. Start Shizuku (or root the phone and grant Atom superuser) to enable shell."),
        TooOld("The installed Shizuku is too old. Update Shizuku to enable shell."),
        NoPermission("Shizuku is running but Atom has not been granted permission. Open Atom's settings and allow Shizuku access."),
    }

    private suspend fun shizukuStatus(): ShizukuStatus {
        // The binder arrives asynchronously from ShizukuProvider at app start; give it a moment once.
        val alive = try {
            Shizuku.pingBinder() || awaitShizukuBinder(1_500)
        } catch (e: Throwable) {
            false
        }
        if (!alive) return ShizukuStatus.NotRunning
        return try {
            when {
                Shizuku.isPreV11() -> ShizukuStatus.TooOld
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuStatus.Ready
                else -> ShizukuStatus.NoPermission
            }
        } catch (e: Throwable) {
            ShizukuStatus.NotRunning
        }
    }

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
