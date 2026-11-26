package atom.web.files

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WriteGuardTest {
    private val files = "/data/user/0/io.github.namanwtf.atom/files"
    private val noBackup = "/data/user/0/io.github.namanwtf.atom/no_backup"
    private fun asks(scope: String, path: String) = assertNotNull(WriteGuard.reason(scope, path, files, noBackup), "$scope $path should ask")
    private fun free(scope: String, path: String) = assertNull(WriteGuard.reason(scope, path, files, noBackup), "$scope $path should not need to ask")

    @Test
    fun everythingInTheLinuxScopeAsks() {
        asks("linux", "/workspace/notes.txt")
        asks("linux", "/root/.profile")
        asks("linux", "/tmp/x")
    }

    @Test
    fun startUpFilesAskAnywhere() {
        for (p in listOf(
            "$files/atom4_workspace/.profile", "$files/atom4_workspace/.bashrc", "/sdcard/.bash_profile", "$files/.mkshrc",
            "/sdcard/Download/.gitconfig", "$files/atom4_workspace/app/.git/config", "$files/atom4_workspace/app/.git/hooks/pre-commit",
            "$files/atom4_workspace/app/.gitattributes", "/sdcard/x/../.zshrc",
        )) asks("app", p)
        asks("root", "/data/local/tmp/.profile")
        asks("root", "/system/etc/mkshrc")
        asks("root", "$files/atom4_linux/alpine/rootfs/etc/profile")
        asks("root", "$files/atom4_linux/alpine/rootfs/etc/profile.d/x.sh")
    }

    @Test
    fun theDistroTreeAndTheRootToolsAsk() {
        asks("app", "$files/atom4_linux/alpine/rootfs/usr/bin/ls")
        asks("app", "$files/atom4_linux/debian/rootfs/root/notes.txt")
        asks("app", "$files/atom4_linux/atom-chroot.sh")
        asks("app", "$noBackup/root-busybox/vi")
        asks("app", "$files/atom4_workspace/../atom4_linux/alpine/rootfs/bin/sh")
    }

    @Test
    fun ordinaryFilesDoNotNeedTheGuard() {
        free("app", "$files/atom4_workspace/hello.py")
        free("app", "$files/atom4_linux/workspace/app/main.kt")
        free("app", "/sdcard/Download/report.md")
        free("app", "$files/atom4_workspace/profile.txt")
        free("root", "/data/local/tmp/out.txt")
    }
}
