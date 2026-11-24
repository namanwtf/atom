package atom.linux.launch

import atom.linux.CommandResult

/** Talks to `su` for chroot mode. Every call is a short-lived process; nothing stays running. */
internal object RootSupport {

    data class Probe(val root: Boolean, val privateMounts: Boolean) {
        val chrootCapable get() = root && privateMounts
    }

    /**
     * Checks that su works and that a private mount namespace can be created (busybox or toybox
     * `unshare -m`). Without the latter we never chroot: binds could leak into a shared namespace.
     */
    suspend fun probe(hostEnv: Map<String, String>): Probe {
        val script = """
            |id -u
            |for b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do
            |  [ -x "${'$'}b" ] && "${'$'}b" unshare -m true 2>/dev/null && { echo NS_OK; exit 0; }
            |done
            |unshare -m true 2>/dev/null && echo NS_OK
            |command -v chroot >/dev/null 2>&1 && echo CHROOT_OK
            |""".trimMargin()
        val r = su(script, hostEnv, 10_000)
        val lines = r.stdout.lines().map { it.trim() }
        val root = r.exitCode == 0 && lines.firstOrNull() == "0"
        return Probe(root = root, privateMounts = root && "NS_OK" in lines)
    }

    /** Runs [script] as root (`su -c`), in the namespace su gives us (normally the app's own). */
    suspend fun su(script: String, hostEnv: Map<String, String>, timeoutMs: Long = 30_000): CommandResult =
        ProcessRunner.run(LaunchSpec(listOf("su", "-c", script), hostEnv, "/"), timeoutMs)

    /** Like [su] but in the global (init) mount namespace: `-mm` is "mount master" for Magisk and KernelSU. */
    suspend fun suGlobal(script: String, hostEnv: Map<String, String>, timeoutMs: Long = 30_000): CommandResult =
        ProcessRunner.run(LaunchSpec(listOf("su", "-mm", "-c", script), hostEnv, "/"), timeoutMs)
}
