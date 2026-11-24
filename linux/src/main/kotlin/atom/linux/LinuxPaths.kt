package atom.linux

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import atom.linux.install.FsOps
import java.io.File

/** Where everything lives. All under the app's private storage except the binaries in nativeLibraryDir. */
internal class LinuxPaths(context: Context) {
    val base = File(context.filesDir, "linux")
    val downloads = File(base, "download")
    /** Shown as /tmp (and /dev/shm under proot) inside the guest. */
    val guestTmp = File(base, "tmp")
    val prootTmp = File(base, ".proot-tmp")
    /** Chroot session records (mount namespace ids). */
    val run = File(base, "run")
    val fakeProc = File(base, "fakeproc")
    val chrootScript = File(base, "atom-chroot.sh")

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val proot = File(nativeDir, "libproot.so")
    val prootLoader = File(nativeDir, "libproot_loader.so")

    fun distroDir(d: Distro) = File(base, d.name.lowercase())
    fun rootfs(d: Distro) = File(distroDir(d), "rootfs")
    fun partialRootfs(d: Distro) = File(distroDir(d), "rootfs.partial")
    fun marker(d: Distro) = File(distroDir(d), "installed.properties")
    fun download(d: Distro) = File(downloads, "${d.name.lowercase()}.rootfs.part")

    /** Shared storage, when this app may read it; bound at /sdcard in the guest. */
    fun sdcardOrNull(): String? = runCatching {
        @Suppress("DEPRECATION")
        Environment.getExternalStorageDirectory().takeIf { it.canRead() && (it.list() != null) }?.absolutePath
    }.getOrNull()
}

/** Full POSIX mode bits (setuid/setgid/sticky included), which java.nio cannot express. */
internal object AndroidFsOps : FsOps {
    override fun symlink(target: String, link: File) = Os.symlink(target, link.path)

    override fun hardlink(existing: File, link: File): Boolean = try {
        Os.link(existing.path, link.path)
        true
    } catch (e: Exception) {
        false
    }

    override fun chmod(file: File, mode: Int) = Os.chmod(file.path, mode)

    override fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    } catch (e: Exception) {
        false
    }

    override fun exists(file: File): Boolean = try {
        Os.lstat(file.path)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Deletes a tree without following symlinks and without crossing into another file system, so a stale
 * bind mount (e.g. /sdcard or /dev inside a chroot) can never make us delete the user's files.
 * Returns false if anything could not be removed.
 */
internal object SafeDelete {
    fun deleteTree(root: File): Boolean {
        val rootDev = try {
            Os.lstat(root.path).st_dev
        } catch (e: Exception) {
            return true // nothing there
        }
        return delete(root, rootDev)
    }

    private fun delete(file: File, dev: Long): Boolean {
        val st = try {
            Os.lstat(file.path)
        } catch (e: Exception) {
            return true
        }
        if (OsConstants.S_ISDIR(st.st_mode)) {
            if (st.st_dev != dev) return false // a mount point: leave it alone
            runCatching { Os.chmod(file.path, (st.st_mode and 0xFFF) or "700".toInt(8)) }
            var ok = true
            file.list()?.forEach { name -> if (!delete(File(file, name), dev)) ok = false }
            return runCatching { Os.remove(file.path) }.isSuccess && ok
        }
        return runCatching { Os.remove(file.path) }.isSuccess
    }
}
