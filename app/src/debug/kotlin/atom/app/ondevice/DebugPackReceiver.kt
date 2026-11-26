package atom.app.ondevice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug builds only (this source set never reaches a release). CI copies the debug pack into the app's cache with
 * `run-as` and sends:
 *
 *     am broadcast -n io.github.namanwtf.atom.debug/atom.app.ondevice.DebugPackReceiver \
 *         -a atom.app.debug.INSTALL_PACK [--ez probe true]
 *
 * The pack is then installed like a download, minus the SHA-256 step (a local file has no published checksum):
 * package name, versionCode and signing certificate are still checked. With `probe`, each native library of the
 * pack is loaded once and the result is logged (tag AtomPack), so CI shows whether dynamic loading works.
 */
class DebugPackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val probe = intent.getBooleanExtra("probe", false)
        scope.launch {
            try {
                val file = File(context.cacheDir, FILE)
                if (!file.isFile) {
                    Log.w(TAG, "no pack at ${file.path}")
                    return@launch
                }
                val result = OnDevicePack.installLocal(file)
                Log.i(TAG, "install: $result")
                if (probe) OnDevicePack.probe().forEach { Log.i(TAG, "probe: $it") }
            } catch (e: Throwable) {
                Log.e(TAG, "install failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION = "atom.app.debug.INSTALL_PACK"
        const val FILE = "atom4_debug_pack.apk"
        const val TAG = "AtomPack"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
