package atom.linux.launch

/**
 * Puts the root manager's BusyBox on PATH for root shells and root commands. Magisk keeps it at
 * /data/adb/magisk/busybox, KernelSU at /data/adb/ksu/bin/busybox, APatch at /data/adb/ap/bin/busybox; only root
 * can see those folders, so the lookup happens inside the root shell. The binary has no applet links of its own,
 * so they are linked once into [appletDir] (`busybox --install -s`). Both folders go at the END of PATH: the
 * system's own tools keep priority and BusyBox fills in what Android lacks (vi, less, wget...).
 */
object RootBusyBox {
    /** Where each root manager keeps its BusyBox, in lookup order. */
    val MANAGER_DIRS = listOf("/data/adb/magisk", "/data/adb/ksu/bin", "/data/adb/ap/bin")

    /**
     * Shell lines (run as root) that find the BusyBox, link its applets into [appletDir], hand that folder back to
     * the app user [appUid] (so the app can clean it up) and append both folders to PATH. Silent when none exists.
     */
    fun prelude(appletDir: String, appUid: Int?): String {
        val dir = LaunchCommands.shQuote(appletDir)
        val owner = appUid?.let { "; chown -R $it:$it $dir 2>/dev/null" }.orEmpty()
        return buildString {
            append("for atom_bb_d in ").append(MANAGER_DIRS.joinToString(" ")).append("; do ")
            append("if [ -x \"\$atom_bb_d/busybox\" ]; then ")
            append("[ -x $dir/vi ] || { mkdir -p $dir 2>/dev/null && \"\$atom_bb_d/busybox\" --install -s $dir 2>/dev/null$owner; }; ")
            append("PATH=\"\$PATH:\$atom_bb_d:\"$dir; export PATH; break; ")
            append("fi; done; unset atom_bb_d\n")
        }
    }

    /** [command] for `su -c`, with BusyBox on PATH. */
    fun wrap(command: String, appletDir: String, appUid: Int?): String = prelude(appletDir, appUid) + command

    /** The `su -c` script of an interactive root shell with BusyBox on PATH. */
    fun interactive(appletDir: String, appUid: Int?): String = prelude(appletDir, appUid) + "exec /system/bin/sh -i"

    /** PATH after appending [dirs] that are not already in it (what the prelude does at run time). */
    fun pathWith(path: String, dirs: List<String>): String {
        val parts = path.split(':').filter { it.isNotEmpty() }.toMutableList()
        dirs.forEach { if (it !in parts) parts += it }
        return parts.joinToString(":")
    }
}
