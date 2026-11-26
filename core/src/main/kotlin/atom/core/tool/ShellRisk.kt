package atom.core.tool

/**
 * Where a shell command runs, for [ShellRisk]. Paths are as the shell sees them.
 *
 * @property ownDirs folders the agent may delete in freely: the app's own scratch and work folders, tmp.
 * @property writableDirs further folders of the app's own that may be written but not deleted wholesale (its data
 *   folder, its folder in shared storage).
 * @property ownPackage the app's package; clearing or stopping it is not a threat to anything else.
 * @property cwd where the command starts, when known; relative paths resolve against it.
 * @property home what `~` expands to, when known.
 * @property distro the installed Linux distro: /etc, /usr, /bin... are the distro's own, so package installs and
 *   edits there are ordinary work. Android's system paths, /proc, /sys, /dev and shared storage stay protected.
 * @property privileged the shell can reach other apps and the system (Android root, Shizuku, the distro in chroot
 *   mode), so killing processes by id can hit system ones.
 */
data class ShellScope(
    val ownDirs: List<String>,
    val writableDirs: List<String> = emptyList(),
    val ownPackage: String? = null,
    val cwd: String? = null,
    val home: String? = null,
    val distro: Boolean = false,
    val privileged: Boolean = false,
) {
    companion object {
        /**
         * Android's own shell, as the app ([privileged] false) or as root or the adb shell user. The app's cache, its
         * agent workspace ([workDirs]) and /data/local/tmp may be deleted in; its data folder and its folder in shared
         * storage may be written.
         */
        fun android(
            packageName: String,
            dataDir: String,
            cacheDir: String,
            workDirs: List<String> = emptyList(),
            cwd: String? = null,
            privileged: Boolean = false,
        ) = ShellScope(
            ownDirs = listOf(cacheDir, "/data/local/tmp") + workDirs,
            writableDirs = listOf(
                dataDir, "/data/data/$packageName", "/data/user/0/$packageName", "/data/user_de/0/$packageName",
                "/sdcard/Android/data/$packageName", "/storage/emulated/0/Android/data/$packageName",
            ),
            ownPackage = packageName,
            cwd = cwd,
            privileged = privileged,
        )

        /** The installed Linux distro: root inside it, starting in /root, with /workspace as its work folder. */
        fun distro(packageName: String, privileged: Boolean) = ShellScope(
            ownDirs = listOf("/workspace", "/tmp", "/var/tmp", "/root"),
            ownPackage = packageName,
            cwd = "/root",
            home = "/root",
            distro = true,
            privileged = privileged,
        )
    }
}

/**
 * Decides, per command line, whether a shell call needs the user's approval. Conservative: [Risk.Sensitive] for
 * anything seriously dangerous to the system or the user's data (deleting outside the app's own folders, formatting,
 * rebooting, writing system paths or other apps' data, changing secure settings, piping downloads into a shell...)
 * and for anything it cannot parse or resolve with confidence (unbalanced quotes, `case`, `$VAR` in a path that is
 * deleted or written, `eval` of an expansion...). Everything else, including root commands that only read, is
 * [Risk.Act]: it runs without asking.
 *
 * It reads the whole line: `;`, `&&`, `||`, `|`, `&`, newlines, subshells, `$( )`, backticks, `<( )`, here-docs and
 * here-strings, `sh -c`, `su -c`, `env`, `sudo`, `busybox`, `xargs` and `find -exec`; each command in it is judged,
 * and the line is as risky as its riskiest part.
 */
object ShellRisk {
    fun classify(command: String, scope: ShellScope): Risk = try {
        Judge(scope).line(command)
        Risk.Act
    } catch (e: Dangerous) {
        Risk.Sensitive
    } catch (e: Unclear) {
        Risk.Sensitive
    } catch (e: RuntimeException) {
        Risk.Sensitive
    }

    /** The reason a line was judged dangerous or unclear, or null when it runs without asking. For tests and logs. */
    fun reason(command: String, scope: ShellScope): String? = try {
        Judge(scope).line(command)
        null
    } catch (e: Dangerous) {
        e.message
    } catch (e: Unclear) {
        "unclear: " + e.message
    } catch (e: RuntimeException) {
        "unclear: " + (e.message ?: e.javaClass.simpleName)
    }

    private class Dangerous(why: String) : Exception(why)
    private class Unclear(why: String) : Exception(why)

    /** One shell word after quote removal. [expands] means its value is only known at run time. */
    private class Word(
        val text: String,
        val expands: Boolean = false,
        val glob: Boolean = false,
        val tilde: Boolean = false,
        val procSubst: Boolean = false,
        val quoted: Boolean = false,
    ) {
        val literal: Boolean get() = !expands && !procSubst
        override fun toString() = text
    }

    private class Redirect(val op: String, val target: Word?)

    private class Simple(
        val words: List<Word>,
        val redirects: List<Redirect>,
        /** Its stdin is the previous command's output. */
        val piped: Boolean,
        /** Bodies of here-docs and here-strings fed to it; null when only known at run time. */
        val stdinText: List<String?>,
        /** Commands inside `$( )`, backticks and `<( )` in its words: they run first. */
        val subs: List<String> = emptyList(),
    )

    private val SHELLS = setOf("sh", "bash", "ash", "dash", "zsh", "ksh", "mksh", "hush", "fish")
    private val INTERPRETERS = setOf("python", "python3", "python2", "perl", "ruby", "node", "nodejs", "php", "lua", "tclsh", "awk")
    private val WRAPPERS = setOf("nohup", "time", "command", "builtin", "exec", "nice", "ionice", "stdbuf", "chrt", "taskset", "unbuffer", "doas")
    private val KEYWORDS = setOf("if", "then", "else", "elif", "fi", "do", "done", "while", "until", "!", "{", "}", "[[", "]]")
    private val ALWAYS = setOf(
        "dd", "mkfs", "mke2fs", "mkswap", "mkfs.ext4", "mkfs.ext2", "mkfs.ext3", "mkfs.vfat", "mkfs.fat", "mkfs.f2fs", "mkfs.exfat",
        "make_f2fs", "format", "fdisk", "sfdisk", "cfdisk", "parted", "gdisk", "sgdisk", "wipefs", "blkdiscard", "flash_image",
        "fastboot", "wipe", "recovery", "reboot", "shutdown", "poweroff", "halt", "setenforce", "setprop", "resetprop", "init",
        "iptables", "ip6tables", "iptables-restore", "ip6tables-restore", "nft", "ndc", "tc", "umount", "losetup", "vdc", "dpm",
        "locksettings", "ksud", "apd", "insmod", "rmmod", "modprobe", "swapon", "swapoff", "sysctl",
    )
    private val SYSTEM_PROCESSES = setOf(
        "system_server", "zygote", "zygote64", "surfaceflinger", "init", "servicemanager", "hwservicemanager", "vold", "netd",
        "lmkd", "logd", "adbd", "ueventd", "healthd", "installd", "keystore2", "keystore", "statsd", "audioserver", "cameraserver",
        "magiskd", "com.android.systemui", "com.android.phone",
    )
    private val DANGER_WORDS = listOf("MASTER_CLEAR", "FACTORY_RESET", "android.intent.action.REBOOT", "ACTION_REQUEST_SHUTDOWN", "REQUEST_SHUTDOWN")
    private val HARMLESS_DEVICES = setOf("/dev/null", "/dev/zero", "/dev/stdout", "/dev/stderr", "/dev/stdin", "/dev/tty", "/dev/random", "/dev/urandom", "/dev/full")
    private val PM_READS = setOf("list", "path", "dump", "help", "resolve-activity", "query-activities", "query-services", "query-receivers", "has-feature", "dump-profiles", "art")
    private val AM_KILLS = setOf("force-stop", "kill", "kill-all", "crash", "hang", "restart", "stop-user", "remove-task", "make-uid-idle", "set-inactive")

    private class Judge(private val scope: ShellScope) {
        private var cwd: String? = scope.cwd
        private var depth = 0

        fun line(command: String) {
            if (++depth > 8) throw Unclear("nested too deeply")
            try {
                for (cmd in Parser(command).parse()) {
                    for (sub in cmd.subs) substitution(sub)
                    simple(cmd)
                }
            } finally {
                depth--
            }
        }

        /** A `$( )`, backtick or process substitution: its command runs too, in the same place. */
        fun substitution(inner: String) {
            val saved = cwd
            line(inner)
            cwd = saved
        }

        private fun simple(cmd: Simple) {
            for (r in cmd.redirects) redirect(r)
            var words = cmd.words.dropWhile { isAssignment(it) || it.text in KEYWORDS }
            if (words.isEmpty()) return
            if (words.any { w -> DANGER_WORDS.any { w.text.contains(it, ignoreCase = true) } }) throw Dangerous("factory reset or shutdown intent")
            words = unwrap(words)
            if (words.isEmpty()) return
            val head = words[0]
            if (!head.literal || head.glob) throw Unclear("the command name is only known at run time")
            val name = head.text.substringAfterLast('/').lowercase()
            val args = words.drop(1)
            when {
                name == "case" || name == "select" || name == "function" -> throw Unclear("$name blocks are not analysed")
                name == "for" -> return
                name == "cd" || name == "pushd" -> cd(args)
                name == "popd" -> cwd = null
                name == "busybox" || name == "toybox" -> simple(Simple(args, emptyList(), cmd.piped, cmd.stdinText))
                name == "sudo" -> simple(Simple(skipFlags(args, setOf("-u", "-g", "-h", "-p", "-C", "-U")), emptyList(), cmd.piped, cmd.stdinText))
                name == "env" -> simple(Simple(envArgs(args), emptyList(), cmd.piped, cmd.stdinText))
                name == "timeout" -> simple(Simple(skipFlags(args, setOf("-s", "-k", "--signal", "--kill-after")).drop(1), emptyList(), cmd.piped, cmd.stdinText))
                name == "chroot" -> simple(Simple(skipFlags(args, emptySet()).drop(1), emptyList(), cmd.piped, cmd.stdinText))
                name == "su" -> su(args, cmd)
                name in SHELLS -> shell(args, cmd)
                name == "eval" -> {
                    if (args.any { !it.literal }) throw Unclear("eval of an expansion")
                    line(args.joinToString(" ") { it.text })
                }
                name == "source" || name == "." -> if (args.firstOrNull()?.procSubst == true) throw Dangerous("sourcing a command's output")
                name in INTERPRETERS -> interpreter(name, args, cmd)
                name == "xargs" -> xargs(args)
                name == "find" -> find(args)
                else -> command(name, args)
            }
        }

        private fun isAssignment(w: Word): Boolean {
            val eq = w.text.indexOf('=')
            return eq > 0 && w.text.substring(0, eq).all { it.isLetterOrDigit() || it == '_' } && !w.text[0].isDigit()
        }

        private fun unwrap(words: List<Word>): List<Word> {
            var w = words
            while (w.isNotEmpty()) {
                val n = w[0].text.substringAfterLast('/').lowercase()
                if (n !in WRAPPERS) break
                w = w.drop(1)
                // nice -n 5, ionice -c 3, stdbuf -oL, chrt -f 10, taskset 0x1: drop their options and values.
                while (w.isNotEmpty() && w[0].text.startsWith("-")) {
                    val flag = w[0].text
                    w = w.drop(1)
                    if (flag in setOf("-n", "-c", "-p", "-f", "-r") && w.isNotEmpty() && w[0].text.all { it.isDigit() }) w = w.drop(1)
                }
                if ((n == "taskset" || n == "chrt") && w.isNotEmpty()) w = w.drop(1)
            }
            return w
        }

        private fun skipFlags(args: List<Word>, withValue: Set<String>): List<Word> {
            var i = 0
            while (i < args.size && args[i].text.startsWith("-")) {
                if (args[i].text == "--") return args.drop(i + 1)
                if (args[i].text in withValue) i++
                i++
            }
            return args.drop(i)
        }

        private fun envArgs(args: List<Word>): List<Word> {
            var i = 0
            while (i < args.size) {
                val t = args[i].text
                when {
                    t == "-u" || t == "--unset" || t == "-C" || t == "--chdir" -> i += 2
                    t.startsWith("-") -> i++
                    isAssignment(args[i]) -> i++
                    else -> break
                }
            }
            return args.drop(i)
        }

        private fun cd(args: List<Word>) {
            val target = args.firstOrNull { !it.text.startsWith("-") || it.text == "-" }
            cwd = when {
                target == null -> scope.home
                target.text == "-" || !target.literal -> null
                else -> resolve(target)
            }
        }

        private fun su(args: List<Word>, cmd: Simple) {
            var i = 0
            while (i < args.size) {
                val t = args[i].text
                when {
                    t == "-c" || t == "--command" || (t.length > 1 && t[0] == '-' && t[1] != '-' && 'c' in t && t != "-cn") -> {
                        val body = args.getOrNull(i + 1) ?: throw Unclear("su -c without a command")
                        if (!body.literal) throw Unclear("su -c of an expansion")
                        line(body.text)
                        return
                    }
                    t.startsWith("--command=") -> {
                        line(t.removePrefix("--command="))
                        return
                    }
                    t.startsWith("-") -> i++
                    // su 0 cmd..., su root cmd..., su shell -c ...
                    t == "0" || t == "root" || t == "shell" || t == "system" || t.all { it.isDigit() } -> i++
                    else -> {
                        simple(Simple(args.drop(i), emptyList(), cmd.piped, cmd.stdinText))
                        return
                    }
                }
            }
            stdinScript(cmd, "su")
        }

        private fun shell(args: List<Word>, cmd: Simple) {
            var i = 0
            while (i < args.size) {
                val t = args[i].text
                when {
                    t == "-c" || (t.length > 1 && t[0] == '-' && t[1] != '-' && 'c' in t) -> {
                        val body = args.getOrNull(i + 1) ?: throw Unclear("sh -c without a command")
                        if (!body.literal) throw Unclear("sh -c of an expansion")
                        substitution(body.text)
                        return
                    }
                    args[i].procSubst -> throw Dangerous("running another command's output as a script")
                    t == "-s" || t == "-" -> break
                    t.startsWith("-") || t.startsWith("+") -> i++
                    // A script file: like running any program the agent wrote.
                    else -> return
                }
            }
            stdinScript(cmd, "a shell")
        }

        /** A shell reading its commands from stdin: judge a here-doc we can see, refuse a pipe we cannot. */
        private fun stdinScript(cmd: Simple, what: String) {
            if (cmd.piped) throw Dangerous("piping into $what")
            for (text in cmd.stdinText) substitution(text ?: throw Unclear("$what reading text only known at run time"))
        }

        private fun interpreter(name: String, args: List<Word>, cmd: Simple) {
            if (args.any { it.procSubst }) throw Dangerous("running another command's output as a script")
            if (!cmd.piped || name == "awk") return
            val script = args.firstOrNull { !it.text.startsWith("-") }
            val inline = args.any { a ->
                val t = a.text
                t == "--eval" || t == "--print" || (t.length > 1 && t[0] == '-' && t[1] != '-' && ('c' in t || 'e' in t || t == "-p"))
            }
            if ((script == null || script.text == "-") && !inline) throw Dangerous("piping into $name")
        }

        private fun xargs(args: List<Word>) {
            val rest = skipFlags(args, setOf("-n", "-L", "-P", "-s", "-d", "-E", "-I", "-a", "--max-args", "--max-procs", "--delimiter", "--arg-file"))
            if (rest.isEmpty()) return
            simple(Simple(rest + Word("{}", expands = true), emptyList(), false, emptyList()))
        }

        private fun find(args: List<Word>) {
            val starts = args.takeWhile { !it.text.startsWith("-") && it.text != "(" && it.text != "!" }
            // find walks inside its start folders, so an own folder itself is fine as a start.
            val startOwn = starts.isNotEmpty() && starts.all { isOwn(it, allowSelf = true) }
            var i = starts.size
            while (i < args.size) {
                when (args[i].text) {
                    "-delete" -> if (!startOwn) throw Dangerous("find -delete outside the app's own folders")
                    "-exec", "-execdir", "-ok", "-okdir" -> {
                        val end = (i + 1 until args.size).firstOrNull { args[it].text == ";" || args[it].text == "+" } ?: args.size
                        val inner = args.subList(i + 1, end).map { w ->
                            if (w.text.contains("{}")) {
                                if (startOwn) Word(resolveText(starts[0]).trimEnd('/') + "/found") else Word("{}", expands = true)
                            } else {
                                w
                            }
                        }
                        if (inner.isNotEmpty()) simple(Simple(inner, emptyList(), false, emptyList()))
                        i = end
                    }
                    "-fprint", "-fprintf", "-fls", "-fprint0" -> args.getOrNull(i + 1)?.let { write(it, "find") }
                }
                i++
            }
        }

        private fun command(name: String, args: List<Word>) {
            val plain = args.filterNot { it.text.startsWith("-") }
            val sub = plain.firstOrNull()?.text?.lowercase()
            when {
                name in ALWAYS || name.startsWith("mkfs.") -> throw Dangerous(name)
                name == "mount" -> if (args.isNotEmpty()) throw Dangerous("mount")
                name == "stop" || name == "start" -> if (!scope.distro) throw Dangerous("$name stops or starts Android services")
                name == "magisk" -> if (args.any { it.text !in setOf("-v", "-V", "-c", "--path", "--version") }) throw Dangerous("magisk")
                name == "rm" || name == "shred" || name == "unlink" || name == "srm" -> {
                    if (plain.isEmpty()) return
                    for (p in plain) if (!isOwn(p)) throw Dangerous("$name of ${p.text} outside the app's own folders")
                }
                name == "rmdir" || name == "touch" || name == "mkdir" || name == "truncate" || name == "tee" || name == "ln" ||
                    name == "mknod" || name == "mkfifo" || name == "patch" || name == "chmod" || name == "chown" || name == "chgrp" ||
                    name == "chattr" || name == "chcon" || name == "restorecon" || name == "setfacl" || name == "mv" -> {
                    val targets = if (name == "chmod" || name == "chown" || name == "chgrp" || name == "chcon" || name == "setfacl") plain.drop(1) else plain
                    for (p in targets) write(p, name)
                }
                name == "cp" || name == "install" || name == "rsync" || name == "scp" -> {
                    val t = args.indexOfFirst { it.text == "-t" || it.text == "--target-directory" }
                    val dest = if (t >= 0) args.getOrNull(t + 1) else plain.lastOrNull()?.takeIf { plain.size >= 2 }
                    dest?.let { write(it, name) }
                }
                name == "sed" -> if (args.any { it.text.startsWith("-i") || it.text.startsWith("--in-place") }) {
                    // Without -e/-f the first plain word is the script; with them, script words look like s/../../.
                    val explicitScript = args.any { it.text == "-e" || it.text == "-f" || it.text == "--expression" }
                    for (p in if (explicitScript) plain.filterNot { isScriptLike(it) } else plain.drop(1)) write(p, name)
                }
                name == "tar" -> {
                    val c = args.indexOfFirst { it.text == "-C" || it.text == "--directory" }
                    if (c >= 0) args.getOrNull(c + 1)?.let { write(it, name) }
                }
                name == "unzip" -> args.indexOfFirst { it.text == "-d" }.takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let { write(it, name) }
                name == "curl" -> {
                    val o = args.indexOfFirst { it.text == "-o" || it.text == "--output" }
                    if (o >= 0) args.getOrNull(o + 1)?.let { write(it, name) }
                }
                name == "wget" -> {
                    val o = args.indexOfFirst { it.text == "-O" || it.text == "--output-document" || it.text == "-P" || it.text == "--directory-prefix" }
                    if (o >= 0) args.getOrNull(o + 1)?.let { write(it, name) }
                }
                name == "sqlite3" -> plain.firstOrNull()?.let { write(it, name) }
                name == "kill" || name == "killall" || name == "pkill" -> kill(name, args)
                name == "pm" -> pm(plain)
                name == "cmd" -> when (sub) {
                    "package" -> pm(plain.drop(1))
                    "settings" -> settings(plain.drop(1))
                    "activity" -> am(plain.drop(1))
                    "device_config" -> deviceConfig(plain.drop(1))
                    "appops" -> if (plain.getOrNull(1)?.text in setOf("set", "reset")) throw Dangerous("cmd appops changes permissions")
                    "user", "role", "power", "overlay", "lock_settings", "deviceidle" -> {
                        val verb = plain.getOrNull(1)?.text?.lowercase().orEmpty()
                        val changes = listOf("remove", "set", "reboot", "shutdown", "add", "enable", "disable", "reset", "create", "clear", "force")
                        if (changes.any { verb.startsWith(it) }) throw Dangerous("cmd $sub $verb")
                    }
                    else -> Unit
                }
                name == "am" -> am(plain)
                name == "settings" -> settings(plain)
                name == "device_config" -> deviceConfig(plain)
                name == "svc" -> if (sub == "power") throw Dangerous("svc power")
                name == "content" -> if (sub in setOf("insert", "update", "delete", "call", "write")) throw Dangerous("content $sub changes another app's data")
                name == "appops" -> if (sub == "set" || sub == "reset") throw Dangerous("appops $sub")
                name == "sm" -> if (sub != null && !sub.startsWith("list") && !sub.startsWith("get") && !sub.startsWith("has") && !sub.startsWith("is")) throw Dangerous("sm $sub")
                name == "ip" -> if (plain.any { it.text in setOf("add", "del", "delete", "flush", "change", "replace", "set", "append") }) throw Dangerous("ip changes networking")
            }
        }

        private fun isScriptLike(w: Word) = w.text.startsWith("s/") || w.text.startsWith("/") && w.text.endsWith("/d")

        private fun kill(name: String, args: List<Word>) {
            val targets = args.filterNot { it.text.startsWith("-") }
            if (targets.any { !it.literal }) {
                if (scope.privileged) throw Unclear("$name of a process only known at run time")
                return
            }
            if (targets.any { t -> t.text.lowercase() in SYSTEM_PROCESSES || t.text == "1" || t.text == "0" }) throw Dangerous("$name of a system process")
            if (name == "kill" && args.size >= 2 && args.last().text == "-1") throw Dangerous("kill of every process")
            if (scope.privileged && name != "kill") throw Dangerous("$name by name as a privileged user")
            if (scope.privileged && targets.any { it.text.all(Char::isDigit) }) throw Dangerous("kill by process id as a privileged user")
        }

        private fun pm(plain: List<Word>) {
            val sub = plain.firstOrNull()?.text?.lowercase() ?: return
            if (sub in PM_READS || sub.startsWith("list") || sub.startsWith("get-") || sub.startsWith("query-")) return
            if (sub in setOf("uninstall", "clear", "force-stop", "disable", "disable-user", "suspend", "hide", "reset-permissions", "revoke")) {
                val pkg = plain.drop(1).lastOrNull { it.text.contains('.') }?.text
                if (pkg != null && pkg == scope.ownPackage && sub != "uninstall") return
            }
            throw Dangerous("pm $sub")
        }

        private fun am(plain: List<Word>) {
            val sub = plain.firstOrNull()?.text?.lowercase() ?: return
            if (sub !in AM_KILLS) return
            val pkg = plain.drop(1).lastOrNull()?.text
            if ((sub == "force-stop" || sub == "kill") && pkg != null && pkg == scope.ownPackage) return
            throw Dangerous("am $sub")
        }

        private fun settings(plain: List<Word>) {
            val p = plain.dropWhile { it.text.all(Char::isDigit) }
            val sub = p.firstOrNull()?.text?.lowercase() ?: return
            if (sub == "get" || sub == "list") return
            val ns = p.getOrNull(1)?.text?.lowercase()
            if (sub == "reset") throw Dangerous("settings reset")
            if ((sub == "put" || sub == "delete") && ns != "system") throw Dangerous("settings $sub ${ns ?: ""}".trim())
        }

        private fun deviceConfig(plain: List<Word>) {
            val sub = plain.firstOrNull()?.text?.lowercase() ?: return
            if (sub != "get" && sub != "list") throw Dangerous("device_config $sub")
        }

        private fun redirect(r: Redirect) {
            val t = r.target ?: return
            if (r.op.startsWith("<") && !r.op.contains(">")) return
            write(t, "a redirect")
        }

        /** [w] is created, changed or moved: refuse system paths, devices and other apps' data. */
        fun write(w: Word, what: String) {
            if (!w.literal || (w.tilde && scope.home == null)) throw Unclear("$what to a path only known at run time")
            val path = resolve(w) ?: return
            if (isProtected(path)) throw Dangerous("$what into $path")
        }

        /** True when [w] surely names something inside one of the app's own folders (or one of them, with [allowSelf]). */
        fun isOwn(w: Word, allowSelf: Boolean = false): Boolean {
            if (!w.literal || (w.tilde && scope.home == null)) return false
            val path = resolve(w) ?: return false
            return scope.ownDirs.any { d -> val dir = normalize(d); dir != "/" && (path.startsWith("$dir/") || (allowSelf && path == dir)) }
        }

        private fun resolveText(w: Word): String = resolve(w) ?: w.text

        private fun resolve(w: Word): String? {
            var t = w.text
            if (w.tilde) t = (scope.home ?: return null) + t.removePrefix("~")
            return when {
                t.startsWith("/") -> normalize(t)
                else -> cwd?.let { normalize("$it/$t") }
            }
        }

        private fun isProtected(path: String): Boolean {
            if (path == "/") return true
            if (path in HARMLESS_DEVICES || path.startsWith("/dev/fd/") || path.startsWith("/proc/self/fd/")) return false
            if ((scope.ownDirs + scope.writableDirs).any { d -> val dir = normalize(d); path == dir || path.startsWith("$dir/") }) return false
            if (path == "/data/local/tmp" || path.startsWith("/data/local/tmp/")) return false
            val system = mutableListOf(
                "/system", "/vendor", "/product", "/odm", "/oem", "/system_ext", "/apex", "/boot", "/recovery", "/persist",
                "/metadata", "/efs", "/mnt/vendor", "/proc", "/sys", "/dev", "/data", "/vendor_dlkm", "/odm_dlkm", "/linkerconfig",
            )
            if (!scope.distro) system += listOf("/etc", "/bin", "/sbin", "/lib", "/lib64", "/init", "/config", "/cache", "/acct", "/debug_ramdisk", "/second_stage_resources")
            if (system.any { path == it || path.startsWith("$it/") || (it == "/init" && path.startsWith("/init.")) }) return true
            // Other apps' folders in shared storage.
            val shared = Regex("^/(?:sdcard|storage/emulated/\\d+|storage/self/primary|mnt/sdcard|mnt/user/\\d+/emulated/\\d+)/Android/(data|obb|media)/([^/]+)").find(path)
            if (shared != null && shared.groupValues[2] != scope.ownPackage) return true
            return false
        }
    }

    internal fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += part
            }
        }
        return "/" + out.joinToString("/")
    }

    /** A small POSIX sh lexer: words, operators, quotes, expansions and substitutions; refuses what it cannot follow. */
    private class Parser(private val s: String) {
        private var i = 0
        private val commands = ArrayList<Simple>()
        private var words = ArrayList<Word>()
        private var redirects = ArrayList<Redirect>()
        private var stdinText = ArrayList<String?>()
        private var subs = ArrayList<String>()
        private var piped = false
        /** A here-doc waiting for its body: the declaring command's index in [commands], and its slot in its stdinText. */
        private class Heredoc(val delimiter: String, val expanding: Boolean, val owner: Int, val slot: Int)

        private val heredocs = ArrayList<Heredoc>()

        private val sb = StringBuilder()
        private var inWord = false
        private var expands = false
        private var glob = false
        private var tilde = false
        private var procSubst = false
        private var quoted = false

        fun parse(): List<Simple> {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == ' ' || c == '\t' -> { endWord(); i++ }
                    c == '\n' -> { endWord(); endCommand(pipeNext = false); i++; readHeredocs() }
                    c == '#' && !inWord -> { while (i < s.length && s[i] != '\n') i++ }
                    c == '\'' -> { inWord = true; quoted = true; val end = s.indexOf('\'', i + 1); if (end < 0) throw Unclear("unbalanced quote"); sb.append(s, i + 1, end); i = end + 1 }
                    c == '"' -> doubleQuoted()
                    c == '\\' -> {
                        if (i + 1 < s.length && s[i + 1] == '\n') { i += 2 } else { inWord = true; if (i + 1 < s.length) sb.append(s[i + 1]); i += 2 }
                    }
                    c == '$' -> dollar()
                    c == '`' -> { inWord = true; expands = true; val end = backtickEnd(i); subs.add(s.substring(i + 1, end).replace("\\`", "`")); i = end + 1 }
                    c == ';' -> { if (s.startsWith(";;", i)) throw Unclear("case syntax"); endWord(); endCommand(false); i++ }
                    c == '&' -> when {
                        s.startsWith("&&", i) -> { endWord(); endCommand(false); i += 2 }
                        s.startsWith("&>>", i) -> { endWord(); redirectOp(">>"); i += 3 }
                        s.startsWith("&>", i) -> { endWord(); redirectOp(">"); i += 2 }
                        else -> { endWord(); endCommand(false); i++ }
                    }
                    c == '|' -> when {
                        s.startsWith("||", i) -> { endWord(); endCommand(false); i += 2 }
                        s.startsWith("|&", i) -> { endWord(); endCommand(true); i += 2 }
                        else -> { endWord(); endCommand(true); i++ }
                    }
                    (c == '<' || c == '>') && i + 1 < s.length && s[i + 1] == '(' && !inWord -> {
                        val end = parenEnd(i + 1)
                        subs.add(s.substring(i + 2, end))
                        inWord = true; expands = true; procSubst = true
                        i = end + 1
                    }
                    c == '<' || c == '>' -> redirection()
                    c == '(' -> {
                        if (inWord) throw Unclear("function or array syntax")
                        endCommand(false); i++
                    }
                    c == ')' -> { endWord(); endCommand(false); i++ }
                    else -> {
                        if (!inWord && c == '~') tilde = true
                        if (c == '*' || c == '?' || c == '[') glob = true
                        inWord = true
                        sb.append(c)
                        i++
                    }
                }
            }
            endWord()
            if (heredocs.isNotEmpty()) throw Unclear("here-doc without its body")
            endCommand(false)
            return commands
        }

        private fun doubleQuoted() {
            inWord = true
            quoted = true
            i++
            while (true) {
                if (i >= s.length) throw Unclear("unbalanced quote")
                val c = s[i]
                when {
                    c == '"' -> { i++; return }
                    c == '\\' && i + 1 < s.length && s[i + 1] in "\$`\"\\\n" -> { if (s[i + 1] != '\n') sb.append(s[i + 1]); i += 2 }
                    c == '$' -> dollar()
                    c == '`' -> { expands = true; val end = backtickEnd(i); subs.add(s.substring(i + 1, end).replace("\\`", "`")); i = end + 1 }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun dollar() {
            inWord = true
            val n = s.getOrNull(i + 1)
            when {
                n == '(' && s.getOrNull(i + 2) == '(' -> { expands = true; i = parenEnd(i + 1) + 1 }
                n == '(' -> { expands = true; val end = parenEnd(i + 1); subs.add(s.substring(i + 2, end)); i = end + 1 }
                n == '{' -> { expands = true; val end = s.indexOf('}', i + 2); if (end < 0) throw Unclear("unbalanced \${"); i = end + 1 }
                n != null && (n.isLetterOrDigit() || n in "_@*#?\$!-") -> {
                    expands = true
                    i++
                    if (s[i].isLetter() || s[i] == '_') { while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++ } else i++
                }
                n == '\'' -> { // $'...' ANSI-C quoting
                    val end = s.indexOf('\'', i + 2)
                    if (end < 0) throw Unclear("unbalanced quote")
                    if (s.substring(i + 2, end).contains('\\')) expands = true
                    sb.append(s, i + 2, end)
                    i = end + 1
                }
                else -> { sb.append('$'); i++ }
            }
        }

        /** Index of the `)` closing the `(` at [open], skipping quotes and nested parentheses. */
        private fun parenEnd(open: Int): Int {
            var depth = 0
            var j = open
            while (j < s.length) {
                when (s[j]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return j }
                    '\'' -> { j = s.indexOf('\'', j + 1); if (j < 0) throw Unclear("unbalanced quote") }
                    '"' -> {
                        j++
                        while (j < s.length && s[j] != '"') { if (s[j] == '\\') j++; j++ }
                        if (j >= s.length) throw Unclear("unbalanced quote")
                    }
                    '\\' -> j++
                }
                j++
            }
            throw Unclear("unbalanced parenthesis")
        }

        private fun backtickEnd(open: Int): Int {
            var j = open + 1
            while (j < s.length) {
                if (s[j] == '\\') j++ else if (s[j] == '`') return j
                j++
            }
            throw Unclear("unbalanced backtick")
        }

        private fun redirection() {
            // A word of digits right before is a file descriptor, not an argument.
            if (inWord && sb.isNotEmpty() && sb.all { it.isDigit() } && !expands) {
                sb.setLength(0); inWord = false
            } else {
                endWord()
            }
            val ops = listOf("<<<", "<<-", "<<", "<>", "<&", ">>", ">|", ">&", "<", ">")
            val op = ops.first { s.startsWith(it, i) }
            i += op.length
            when (op) {
                "<<", "<<-" -> heredoc()
                "<<<" -> {
                    skipBlanks()
                    val w = readWord()
                    // Only matters when a shell reads it; null marks text known only at run time.
                    stdinText.add(if (w.literal) w.text else null)
                }
                ">&", "<&" -> {
                    skipBlanks()
                    val w = readWord()
                    // 2>&1, >&- : descriptors. >&file writes to a file.
                    if (!(w.text.all(Char::isDigit) || w.text == "-")) redirects.add(Redirect(">", w))
                }
                else -> redirectOp(op)
            }
        }

        private fun redirectOp(op: String) {
            skipBlanks()
            redirects.add(Redirect(op, readWord()))
        }

        private fun heredoc() {
            skipBlanks()
            val w = readWord()
            if (w.text.isEmpty()) throw Unclear("here-doc without a delimiter")
            // An unquoted delimiter means the body expands, and a `$( )` in it would run.
            heredocs += Heredoc(w.text, !w.quoted, commands.size, stdinText.size)
            stdinText.add(null)
        }

        private fun readHeredocs() {
            while (heredocs.isNotEmpty()) {
                val doc = heredocs.removeAt(0)
                val delimiter = doc.delimiter
                val body = StringBuilder()
                while (true) {
                    if (i >= s.length) throw Unclear("here-doc without its end")
                    val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                    val lineText = s.substring(i, end)
                    i = minOf(end + 1, s.length)
                    if (lineText.trimStart('\t') == delimiter) break
                    body.append(lineText).append('\n')
                }
                val text = body.toString()
                if (doc.expanding && (text.contains("\$(") || text.contains('`'))) throw Unclear("a here-doc with a command substitution")
                attach(doc, text)
            }
        }

        /** A here-doc body belongs to the command that declared it, which has ended by now. */
        private fun attach(doc: Heredoc, text: String) {
            val c = commands.getOrNull(doc.owner) ?: throw Unclear("here-doc outside a command")
            if (doc.slot !in c.stdinText.indices) throw Unclear("here-doc outside a command")
            commands[doc.owner] = Simple(c.words, c.redirects, c.piped, c.stdinText.toMutableList().also { it[doc.slot] = text }, c.subs)
        }

        private fun skipBlanks() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        }

        /** Reads one word at [i] (for a redirect target or a here-doc delimiter). */
        private fun readWord(): Word {
            endWord()
            val startI = i
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == ';' || c == '&' || c == '|' || c == '<' || c == '>' || c == '(' || c == ')') break
                when (c) {
                    '\'' -> { inWord = true; quoted = true; val end = s.indexOf('\'', i + 1); if (end < 0) throw Unclear("unbalanced quote"); sb.append(s, i + 1, end); i = end + 1 }
                    '"' -> doubleQuoted()
                    '\\' -> { inWord = true; quoted = true; if (i + 1 < s.length) sb.append(s[i + 1]); i += 2 }
                    '$' -> dollar()
                    '`' -> { inWord = true; expands = true; val end = backtickEnd(i); subs.add(s.substring(i + 1, end)); i = end + 1 }
                    else -> {
                        if (i == startI && c == '~') tilde = true
                        if (c == '*' || c == '?' || c == '[') glob = true
                        inWord = true; sb.append(c); i++
                    }
                }
            }
            if (!inWord) throw Unclear("missing word after an operator")
            val w = Word(sb.toString(), expands, glob, tilde, procSubst, quoted)
            reset()
            return w
        }

        private fun endWord() {
            if (inWord) words.add(Word(sb.toString(), expands, glob, tilde, procSubst, quoted))
            reset()
        }

        private fun reset() {
            sb.setLength(0)
            inWord = false; expands = false; glob = false; tilde = false; procSubst = false; quoted = false
        }

        private fun endCommand(pipeNext: Boolean) {
            if (words.isNotEmpty() || redirects.isNotEmpty() || stdinText.isNotEmpty() || subs.isNotEmpty()) {
                commands += Simple(words, redirects, piped, stdinText, subs)
            }
            words = ArrayList(); redirects = ArrayList(); stdinText = ArrayList(); subs = ArrayList()
            piped = pipeNext
        }
    }
}
