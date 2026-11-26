package atom.linux.launch

/** What to exec: argv[0] is the program, [env] is the complete environment. */
internal data class LaunchSpec(val argv: List<String>, val env: Map<String, String>, val cwd: String?)

internal data class ProotConfig(
    /** nativeLibraryDir/libproot.so */
    val proot: String,
    /** nativeLibraryDir/libproot_loader.so */
    val loader: String,
    val rootfs: String,
    /** proot's own scratch dir (PROOT_TMP_DIR). */
    val prootTmp: String,
    /** Host dir shown as /tmp (and /dev/shm) inside the guest. */
    val guestTmp: String,
    /** Guest /proc path -> host file with stand-in content, for /proc files Android hides from apps. */
    val fakeProc: Map<String, String> = emptyMap(),
    /** Shared storage to bind at /sdcard, or null when the app cannot read it. */
    val sdcard: String? = null,
    /** Further (host, guest) binds in order: /workspace first, then its shared-folder mounts. */
    val binds: List<Pair<String, String>> = emptyList(),
    val shell: String = "/bin/sh",
    val term: String = LaunchCommands.TERM,
)

internal data class ChrootConfig(
    /** The launcher script (see [LaunchCommands.chrootScript]) on disk. */
    val script: String,
    val rootfs: String,
    /** App-owned dir where the launcher records each session's mount namespace. */
    val stateDir: String,
    /** Unique per session; names the namespace record used for cleanup. */
    val token: String,
    /** File listing "host<TAB>guest" binds, one per line, written by the caller ([LaunchCommands.bindsFile]). */
    val bindsFile: String,
    val shell: String = "/bin/sh",
    val term: String = LaunchCommands.TERM,
    /** Environment for su itself (the guest gets a clean one). */
    val hostEnv: Map<String, String> = emptyMap(),
    val su: String = "su",
)

/** Builds the exact command lines for proot and chroot sessions. Pure, so it is unit-tested. */
internal object LaunchCommands {
    const val TERM = "xterm-256color"
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Exit status the chroot launcher uses when it cannot isolate its mounts (nothing was mounted). */
    const val EXIT_NO_PRIVATE_MOUNTS = 98

    fun guestEnv(term: String = TERM): Map<String, String> = linkedMapOf(
        "PATH" to GUEST_PATH,
        "HOME" to "/root",
        "USER" to "root",
        "LOGNAME" to "root",
        "TERM" to term,
        "COLORTERM" to "truecolor",
        "LANG" to "C.UTF-8",
        "TMPDIR" to "/tmp",
        // What /etc/profile.d/uv.sh sets for login shells; one-off commands run without a login shell.
        "UV_PYTHON_INSTALL_DIR" to "/opt/uv/python",
        "UV_PYTHON_BIN_DIR" to "/usr/local/bin",
    )

    /**
     * The guest program: a login shell for a terminal, or a plain `sh -c` for [command]. One-off commands get a fixed
     * environment ([guestEnv], PATH included) and no login shell, so start-up files (.profile, /etc/profile) that a
     * file tool could have written never run with them.
     */
    fun shellArgv(shell: String, command: String?): List<String> =
        if (command == null) listOf(shell, "-l") else listOf(shell, "-c", command)

    // ------------------------------------------------------------------ proot

    fun proot(c: ProotConfig, command: String?): LaunchSpec {
        val argv = ArrayList<String>()
        argv += c.proot
        argv += "--kill-on-exit" // the whole guest process tree dies with the session
        argv += "--link2symlink" // emulate hard links, which the app sandbox refuses
        argv += "-0" // fake root: package managers expect uid 0
        argv += listOf("-r", c.rootfs)
        argv += listOf("-b", "/dev", "-b", "/proc", "-b", "/sys")
        argv += listOf("-b", "/proc/self/fd:/dev/fd")
        argv += listOf("-b", "${c.guestTmp}:/tmp", "-b", "${c.guestTmp}:/dev/shm")
        for ((guest, host) in c.fakeProc.toSortedMap()) argv += listOf("-b", "$host:$guest")
        if (c.sdcard != null) argv += listOf("-b", "${c.sdcard}:/sdcard")
        for ((host, guest) in c.binds) argv += listOf("-b", "$host:$guest")
        argv += listOf("-w", "/root")
        argv += shellArgv(c.shell, command)

        val env = LinkedHashMap(guestEnv(c.term))
        env["PROOT_LOADER"] = c.loader
        env["PROOT_TMP_DIR"] = c.prootTmp
        return LaunchSpec(argv, env, c.rootfs)
    }

    // ------------------------------------------------------------------ chroot

    /** The `su -c` string that runs the launcher; every argument is single-quoted. */
    fun chrootInvocation(c: ChrootConfig, command: String?): String {
        val args = mutableListOf(
            "sh", c.script, c.rootfs, c.stateDir, c.token, c.bindsFile, c.shell, c.term,
            if (command == null) "login" else "command",
        )
        if (command != null) args += command
        return args.joinToString(" ") { shQuote(it) }
    }

    fun chroot(c: ChrootConfig, command: String?): LaunchSpec =
        LaunchSpec(listOf(c.su, "-c", chrootInvocation(c, command)), c.hostEnv, "/")

    /**
     * Root-side launcher. Re-execs itself in a new mount namespace and makes every mount private before
     * binding anything, so the binds exist only for this session and vanish when its last process exits;
     * nothing can leak into the app's or the global namespace. Prefers the root manager's busybox, falling
     * back to toybox. Records the namespace id so the app can kill stragglers later ([cleanupScript]).
     */
    fun chrootScript(): String = """
        |#!/system/bin/sh
        |# Atom chroot launcher (runs as root via su).
        |# Args: ROOTFS STATE_DIR TOKEN BINDS_FILE SHELL TERM MODE [COMMAND]
        |R="${'$'}1"; STATE="${'$'}2"; TOKEN="${'$'}3"; BINDS="${'$'}4"; GSHELL="${'$'}5"; GTERM="${'$'}6"; MODE="${'$'}7"; CMD="${'$'}8"
        |BB=""
        |for b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do
        |  [ -x "${'$'}b" ] && { BB="${'$'}b"; break; }
        |done
        |if [ -z "${'$'}ATOM_IN_NS" ]; then
        |  export ATOM_IN_NS=1
        |  if [ -n "${'$'}BB" ] && "${'$'}BB" unshare --help >/dev/null 2>&1; then
        |    exec "${'$'}BB" unshare -m --propagation private sh "${'$'}0" "${'$'}@"
        |  fi
        |  exec unshare -m sh "${'$'}0" "${'$'}@"
        |fi
        |# From here on we are alone in a fresh mount namespace.
        |if [ -n "${'$'}BB" ]; then
        |  "${'$'}BB" mount --make-rprivate / 2>/dev/null || mount --make-rprivate / 2>/dev/null || mount -o rprivate / 2>/dev/null
        |else
        |  mount --make-rprivate / 2>/dev/null || mount -o rprivate / 2>/dev/null
        |fi
        |if [ ${'$'}? -ne 0 ]; then
        |  echo "atom: cannot make mounts private; not starting the chroot" >&2
        |  exit $EXIT_NO_PRIVATE_MOUNTS
        |fi
        |readlink /proc/self/ns/mnt > "${'$'}STATE/${'$'}TOKEN.ns"
        |bindmnt() {
        |  [ -d "${'$'}2" ] || mkdir -p "${'$'}2" 2>/dev/null
        |  mount -o bind "${'$'}1" "${'$'}2" 2>/dev/null || { [ -n "${'$'}BB" ] && "${'$'}BB" mount --bind "${'$'}1" "${'$'}2"; } || echo "atom: could not bind ${'$'}1" >&2
        |}
        |bindmnt /dev "${'$'}R/dev"
        |bindmnt /dev/pts "${'$'}R/dev/pts"
        |mount -t proc proc "${'$'}R/proc" 2>/dev/null || bindmnt /proc "${'$'}R/proc"
        |mount -t sysfs sysfs "${'$'}R/sys" 2>/dev/null || bindmnt /sys "${'$'}R/sys"
        |TAB=${'$'}(printf '	')
        |while IFS="${'$'}TAB" read -r src dst; do
        |  case "${'$'}dst" in /*) ;; *) continue ;; esac
        |  case "${'$'}dst" in */../*|*/..) continue ;; esac
        |  [ -e "${'$'}src" ] && bindmnt "${'$'}src" "${'$'}R${'$'}dst"
        |done < "${'$'}BINDS"
        |cd "${'$'}R/root" 2>/dev/null || cd "${'$'}R"
        |CHROOT=chroot
        |command -v chroot >/dev/null 2>&1 || CHROOT="${'$'}BB chroot"
        |set -- HOME=/root USER=root LOGNAME=root TERM="${'$'}GTERM" COLORTERM=truecolor LANG=C.UTF-8 TMPDIR=/tmp PATH=$GUEST_PATH UV_PYTHON_INSTALL_DIR=/opt/uv/python UV_PYTHON_BIN_DIR=/usr/local/bin
        |if [ "${'$'}MODE" = login ]; then
        |  exec ${'$'}CHROOT "${'$'}R" /usr/bin/env -i "${'$'}@" "${'$'}GSHELL" -l
        |else
        |  exec ${'$'}CHROOT "${'$'}R" /usr/bin/env -i "${'$'}@" "${'$'}GSHELL" -c "${'$'}CMD"
        |fi
        |""".trimMargin()

    /**
     * Root-side cleanup for one session: kills every process still in the session's private mount
     * namespace (its mounts then disappear with it) and removes the record. Refuses to act on the
     * caller's or init's namespace.
     */
    fun cleanupScript(stateDir: String, token: String): String {
        val record = shQuote("$stateDir/$token.ns")
        return """
            |F=$record
            |[ -f "${'$'}F" ] || exit 0
            |NS=${'$'}(cat "${'$'}F")
            |SELF=${'$'}(readlink /proc/self/ns/mnt)
            |INIT=${'$'}(readlink /proc/1/ns/mnt)
            |if [ -n "${'$'}NS" ] && [ "${'$'}NS" != "${'$'}SELF" ] && [ "${'$'}NS" != "${'$'}INIT" ]; then
            |  for p in /proc/[0-9]*; do
            |    [ "${'$'}(readlink ${'$'}p/ns/mnt 2>/dev/null)" = "${'$'}NS" ] && kill -9 "${'$'}{p#/proc/}" 2>/dev/null
            |  done
            |fi
            |rm -f "${'$'}F"
            |""".trimMargin()
    }

    /** Content of the binds file the chroot launcher reads: "host<TAB>guest" per line; tabs/newlines rejected. */
    fun bindsFile(binds: List<Pair<String, String>>): String = binds
        .filter { (h, g) -> h.none { it == '\t' || it == '\n' } && g.none { it == '\t' || it == '\n' } && g.startsWith("/") }
        .joinToString("") { (h, g) -> "$h\t$g\n" }

    /** Prints every mount point under [dir] (one per line) as seen from the namespace it runs in. */
    fun listMountsScript(dir: String): String {
        val d = shQuote(dir.trimEnd('/'))
        // Plain sh (no awk on older toybox): field 2 of /proc/mounts is the mount point.
        return "D=$d; while read -r _ mp _; do case \"\$mp\" in \"\$D\"|\"\$D\"/*) echo \"\$mp\";; esac; done < /proc/mounts"
    }

    fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
