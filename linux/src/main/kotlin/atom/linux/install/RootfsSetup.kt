package atom.linux.install

import atom.linux.Distro
import java.io.File

/** Post-extraction configuration of a root filesystem. Pure file operations, so it is unit-testable. */
internal object RootfsSetup {

    const val PROMPT_COLOR_USER = "1;32"
    const val PROMPT_COLOR_DIR = "1;34"

    fun configure(rootfs: File, distro: Distro, fs: FsOps = NioFsOps) {
        val etc = File(rootfs, "etc").apply { mkdirs() }

        // Mount points and dirs the launchers bind into.
        for (dir in listOf("dev", "proc", "sys", "tmp", "root", "sdcard", "dev/shm")) {
            val f = File(rootfs, dir)
            if (fs.isSymlink(f)) continue
            f.mkdirs()
        }
        runCatching { fs.chmod(File(rootfs, "tmp"), "1777".toInt(8)) }
        runCatching { fs.chmod(File(rootfs, "root"), "700".toInt(8)) }

        replaceFile(File(etc, "resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n", fs)
        replaceFile(
            File(etc, "hosts"),
            "127.0.0.1   localhost\n::1         localhost ip6-localhost ip6-loopback\n",
            fs,
        )
        replaceFile(File(etc, "motd"), "", fs)

        val host = distro.name.lowercase()
        File(etc, "profile.d").mkdirs()
        replaceFile(File(etc, "profile.d/atom.sh"), profileScript(host), fs)

        when (distro) {
            Distro.Alpine -> {
                val release = File(etc, "alpine-release").takeIf { it.isFile }?.readText()?.trim()
                replaceFile(File(etc, "apk/repositories"), alpineRepositories(release), fs)
            }
            Distro.Debian -> {
                // apt drops to the _apt user for downloads, which has no network group on Android.
                replaceFile(File(etc, "apt/apt.conf.d/99atom"), "APT::Sandbox::User \"root\";\n", fs)
            }
        }
        addAndroidGroups(File(etc, "group"))
    }

    /** The shell to log in with: bash when the distro has it, otherwise sh. */
    fun loginShell(rootfs: File): String =
        if (File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists()) "/bin/bash" else "/bin/sh"

    fun profileScript(host: String): String = """
        |# Written by Atom. Runs for every login shell.
        |export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        |export LANG=C.UTF-8
        |export TERM="${'$'}{TERM:-xterm-256color}"
        |export TMPDIR=/tmp
        |PS1='\[\033[${PROMPT_COLOR_USER}m\]\u@$host\[\033[0m\]:\[\033[${PROMPT_COLOR_DIR}m\]\w\[\033[0m\]\$ '
        |""".trimMargin()

    /** Points apk at the release the minirootfs came from, e.g. "3.24.4" -> v3.24. */
    fun alpineRepositories(release: String?): String {
        val branch = release?.split('.')?.takeIf { it.size >= 2 && it[0].all(Char::isDigit) && it[1].all(Char::isDigit) }
            ?.let { "v${it[0]}.${it[1]}" } ?: DistroSources.ALPINE_BRANCH
        return "https://dl-cdn.alpinelinux.org/alpine/$branch/main\nhttps://dl-cdn.alpinelinux.org/alpine/$branch/community\n"
    }

    /** Names for the Android groups an app process carries, so `id` and friends do not complain. */
    private fun addAndroidGroups(group: File) {
        if (!group.isFile) return
        val text = group.readText()
        val missing = ANDROID_GROUPS.filter { (name, gid) ->
            !text.lineSequence().any { it.startsWith("$name:") || it.split(':').getOrNull(2) == gid.toString() }
        }
        if (missing.isEmpty()) return
        val add = missing.joinToString("") { (name, gid) -> "$name:x:$gid:root\n" }
        group.writeText(if (text.endsWith("\n") || text.isEmpty()) text + add else text + "\n" + add)
    }

    private val ANDROID_GROUPS = listOf(
        "aid_inet" to 3003,
        "aid_net_raw" to 3004,
        "aid_sdcard_rw" to 1015,
        "aid_everybody" to 9997,
    )

    /** Writes [content] at [file], replacing a symlink rather than following it. */
    private fun replaceFile(file: File, content: String, fs: FsOps) {
        if (fs.isSymlink(file) || (file.exists() && !file.isFile)) file.delete()
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
