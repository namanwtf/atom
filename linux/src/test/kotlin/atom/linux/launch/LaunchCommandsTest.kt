package atom.linux.launch

import atom.linux.Distro
import atom.linux.install.DistroSources
import atom.linux.install.RootfsSetup
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchCommandsTest {

    private val proot = ProotConfig(
        proot = "/data/app/lib/arm64/libproot.so",
        loader = "/data/app/lib/arm64/libproot_loader.so",
        rootfs = "/data/user/0/app/files/linux/alpine/rootfs",
        prootTmp = "/data/user/0/app/files/linux/.proot-tmp",
        guestTmp = "/data/user/0/app/files/linux/tmp",
        fakeProc = mapOf("/proc/stat" to "/fp/stat", "/proc/loadavg" to "/fp/loadavg"),
        sdcard = "/storage/emulated/0",
        shell = "/bin/sh",
    )

    @Test
    fun prootCommandLine() {
        val spec = LaunchCommands.proot(proot, null)
        assertEquals(
            listOf(
                "/data/app/lib/arm64/libproot.so", "--kill-on-exit", "--link2symlink", "-0",
                "-r", "/data/user/0/app/files/linux/alpine/rootfs",
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/proc/self/fd:/dev/fd",
                "-b", "/data/user/0/app/files/linux/tmp:/tmp", "-b", "/data/user/0/app/files/linux/tmp:/dev/shm",
                "-b", "/fp/loadavg:/proc/loadavg", "-b", "/fp/stat:/proc/stat",
                "-b", "/storage/emulated/0:/sdcard",
                "-w", "/root", "/bin/sh", "-l",
            ),
            spec.argv,
        )
        assertEquals("/data/app/lib/arm64/libproot_loader.so", spec.env["PROOT_LOADER"])
        assertEquals("/data/user/0/app/files/linux/.proot-tmp", spec.env["PROOT_TMP_DIR"])
        assertEquals("xterm-256color", spec.env["TERM"])
        assertEquals("/root", spec.env["HOME"])
        assertEquals("C.UTF-8", spec.env["LANG"])
        assertEquals(LaunchCommands.GUEST_PATH, spec.env["PATH"])
    }

    @Test
    fun prootCommandWithoutSdcardRunsCommand() {
        val spec = LaunchCommands.proot(proot.copy(sdcard = null, fakeProc = emptyMap()), "echo hi && uname -a")
        assertFalse(spec.argv.any { it.endsWith(":/sdcard") })
        assertEquals(listOf("/bin/sh", "-l", "-c", "echo hi && uname -a"), spec.argv.takeLast(4))
    }

    @Test
    fun chrootInvocationQuotesEverything() {
        val c = ChrootConfig(
            script = "/data/x/atom-chroot.sh",
            rootfs = "/data/x/debian/rootfs",
            stateDir = "/data/x/run",
            token = "abc123",
            guestTmp = "/data/x/tmp",
            sdcard = null,
            shell = "/bin/bash",
            hostEnv = mapOf("PATH" to "/system/bin"),
        )
        val login = LaunchCommands.chroot(c, null)
        assertEquals(listOf("su", "-c"), login.argv.take(2))
        assertEquals(
            "'sh' '/data/x/atom-chroot.sh' '/data/x/debian/rootfs' '/data/x/run' 'abc123' '/data/x/tmp' '-' " +
                "'/bin/bash' 'xterm-256color' 'login'",
            login.argv[2],
        )
        val cmd = LaunchCommands.chroot(c, "echo 'it''s' \$HOME")
        assertTrue(cmd.argv[2].endsWith("'command' 'echo '\\''it'\\'''\\''s'\\'' \$HOME'"))
        assertEquals(mapOf("PATH" to "/system/bin"), cmd.env)
    }

    @Test
    fun chrootScriptIsolatesMountsBeforeBinding() {
        val s = LaunchCommands.chrootScript()
        val unshare = s.indexOf("unshare -m")
        val privateMounts = s.indexOf("--make-rprivate")
        val firstBind = s.indexOf("bindmnt /dev ")
        assertTrue(unshare in 0 until privateMounts)
        assertTrue(privateMounts < firstBind)
        assertTrue(s.contains("exit ${LaunchCommands.EXIT_NO_PRIVATE_MOUNTS}"))
        assertTrue(s.contains("/usr/bin/env -i"))
        assertTrue(s.contains("ns/mnt"))
        // Everything is bound inside the rootfs only.
        Regex("bindmnt (\\S+) (\\S+)").findAll(s).filter { !it.value.startsWith("bindmnt()") }.forEach {
            assertTrue(it.groupValues[2].startsWith("\"\$R/"), it.value)
        }
    }

    @Test
    fun cleanupNeverTouchesOwnOrInitNamespace() {
        val s = LaunchCommands.cleanupScript("/data/x/run", "tok")
        assertTrue(s.contains("'/data/x/run/tok.ns'"))
        assertTrue(s.contains("\"\$NS\" != \"\$SELF\""))
        assertTrue(s.contains("\"\$NS\" != \"\$INIT\""))
        assertTrue(s.contains("kill -9"))
    }

    @Test
    fun mountListingMatchesOnlyTheTree() {
        val s = LaunchCommands.listMountsScript("/data/x/linux/")
        assertTrue(s.startsWith("D='/data/x/linux';"))
        assertTrue(s.contains("\"\$D\"/*"))
    }

    @Test
    fun shellQuoting() {
        assertEquals("'a b'", LaunchCommands.shQuote("a b"))
        assertEquals("'it'\\''s'", LaunchCommands.shQuote("it's"))
    }

    @Test
    fun resolvesDebianFromLxcIndex() = runTest {
        val index = """
            debian;bookworm;arm64;default;20261118_05:24;/images/debian/bookworm/arm64/default/20261118_05:24/
            debian;trixie;amd64;default;20261118_05:24;/images/debian/trixie/amd64/default/20261118_05:24/
            debian;trixie;arm64;default;20261117_05:24;/images/debian/trixie/arm64/default/20261117_05:24/
            debian;trixie;arm64;default;20261118_05:24;/images/debian/trixie/arm64/default/20261118_05:24/
            debian;trixie;arm64;cloud;20261119_05:24;/images/debian/trixie/arm64/cloud/20261119_05:24/
        """.trimIndent()
        val sums = "a9ae0ee8503cfa8787d21d35892ec3f74f1c457f570367d92ba090e8f263628c  rootfs.tar.xz\n" +
            "ddf6254e1791ff9212d79135cc686d95908e8b0f6eaa3997372f02969f61711d  rootfs.squashfs\n"
        val fetched = mutableListOf<String>()
        val src = DistroSources.resolve(Distro.Debian) { url ->
            fetched += url
            if (url.endsWith("index-system")) index else sums
        }
        assertEquals(
            "https://images.linuxcontainers.org/images/debian/trixie/arm64/default/20261118_05:24/rootfs.tar.xz",
            src.url,
        )
        assertEquals("a9ae0ee8503cfa8787d21d35892ec3f74f1c457f570367d92ba090e8f263628c", src.sha256)
        assertEquals(DistroSources.LXC_INDEX, fetched.first())
        val alpine = DistroSources.resolve(Distro.Alpine) { error("no network for Alpine") }
        assertTrue(alpine.url.endsWith("alpine-minirootfs-3.24.4-aarch64.tar.gz"))
        assertEquals(64, alpine.sha256.length)
    }

    @Test
    fun rootfsSetupWritesConfig() {
        val root = Files.createTempDirectory("atom-rootfs").toFile()
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/alpine-release").writeText("3.24.4\n")
            File(root, "etc/group").writeText("root:x:0:root\n")
            Files.createSymbolicLink(File(root, "etc/resolv.conf").toPath(), File("/nonexistent/resolv.conf").toPath())
            RootfsSetup.configure(root, Distro.Alpine)
            assertFalse(Files.isSymbolicLink(File(root, "etc/resolv.conf").toPath()))
            assertTrue(File(root, "etc/resolv.conf").readText().contains("nameserver 1.1.1.1"))
            assertEquals(
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.24/community\n",
                File(root, "etc/apk/repositories").readText(),
            )
            assertTrue(File(root, "etc/profile.d/atom.sh").readText().contains("@alpine"))
            assertTrue(File(root, "etc/group").readText().contains("aid_inet:x:3003:root"))
            assertEquals("", File(root, "etc/motd").readText())
            assertEquals("/bin/sh", RootfsSetup.loginShell(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
