package atom.web.files

import atom.core.io.deleteTreeNoFollow
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

    /** How Android resolves the other spellings of the app's folder. */
    private val android = { p: String -> p.replace("/data/data/io.github.namanwtf.atom/", "/data/user/0/io.github.namanwtf.atom/").replace(Regex("^/proc/self/root/"), "/") }

    @Test
    fun otherSpellingsOfTheAppFolderStillAsk() {
        for (p in listOf(
            "/data/data/io.github.namanwtf.atom/files/atom4_linux/debian/rootfs/etc/apt/apt.conf.d/99x",
            "/proc/self/root/data/user/0/io.github.namanwtf.atom/files/atom4_linux/alpine/rootfs/usr/bin/which",
            "/data/data/io.github.namanwtf.atom/no_backup/root-busybox/vi",
        )) {
            assertNotNull(WriteGuard.reason("app", p, files, noBackup, android), p)
        }
        assertNull(WriteGuard.reason("app", "/data/data/io.github.namanwtf.atom/files/atom4_workspace/a.py", files, noBackup, android))
    }

    @Test
    fun aSymlinkedParentOrFileIsResolved() {
        val tmp = java.nio.file.Files.createTempDirectory("guard").toFile()
        try {
            val appFiles = java.io.File(tmp, "files")
            val bin = java.io.File(appFiles, "atom4_linux/alpine/rootfs/usr/bin").apply { mkdirs() }
            java.io.File(bin, "which").writeText("#!/bin/sh\n")
            val alias = java.io.File(tmp, "alias").toPath()
            java.nio.file.Files.createSymbolicLink(alias, appFiles.toPath())
            val nb = java.io.File(tmp, "no_backup").path
            // Through a symlinked folder, for an existing file and for one that does not exist yet.
            assertNotNull(WriteGuard.reason("app", "$alias/atom4_linux/alpine/rootfs/usr/bin/which", appFiles.path, nb))
            assertNotNull(WriteGuard.reason("app", "$alias/atom4_linux/alpine/rootfs/etc/apt/apt.conf.d/99x", appFiles.path, nb))
            // An innocent name that links into the distro tree, also when the target does not exist (a dangling link).
            val work = java.io.File(appFiles, "atom4_workspace").apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(java.io.File(work, "notes.txt").toPath(), java.io.File(bin, "which").toPath())
            java.nio.file.Files.createSymbolicLink(java.io.File(work, "later.txt").toPath(), java.io.File(bin, "newtool").toPath())
            assertNotNull(WriteGuard.reason("app", "${work.path}/notes.txt", appFiles.path, nb))
            assertNotNull(WriteGuard.reason("app", "${work.path}/later.txt", appFiles.path, nb))
            assertNull(WriteGuard.reason("app", "${work.path}/plain.txt", appFiles.path, nb))
        } finally {
            tmp.deleteTreeNoFollow()
        }
    }
}
