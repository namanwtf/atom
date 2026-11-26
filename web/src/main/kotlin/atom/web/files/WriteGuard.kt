package atom.web.files

/**
 * Writes the file tools always ask about, whatever else allows them: anything a shell or git would later run on its
 * own. A planted `.profile` runs with the next login shell, a `.git/config` with the next `git status`, a file in the
 * distro's tree with whatever reads it, and a link in the root shell's tool folder as root. Pure, so it is tested.
 */
internal object WriteGuard {
    /** Shell and tool start-up files, by name, wherever they are. */
    private val STARTUP_NAMES = setOf(
        ".profile", ".bashrc", ".bash_profile", ".bash_login", ".bash_logout", ".mkshrc", ".kshrc", ".shrc", ".ashrc",
        ".zshrc", ".zprofile", ".zshenv", ".zlogin", ".zlogout", ".envrc", ".inputrc", ".gitconfig", ".gitattributes",
        ".gitmodules", "mkshrc", "bash.bashrc", ".pythonrc", ".npmrc", ".curlrc", ".wgetrc",
    )

    /**
     * Why writing [path] (absolute; as [scope] sees it: "app", "root" or "linux") must ask, or null. [filesDir] and
     * [noBackupDir] are the app's, for "app" paths.
     */
    fun reason(scope: String, path: String, filesDir: String, noBackupDir: String): String? {
        val p = normalize(path)
        val parts = p.split('/').filter { it.isNotEmpty() }
        val name = parts.lastOrNull().orEmpty()
        return when {
            scope == "linux" -> "it writes inside the Linux environment, whose files its shells and tools run"
            name in STARTUP_NAMES || ".git" in parts || p.endsWith("/.config/git/config") ->
                "$name is a start-up or settings file that a shell or git runs on its own"
            parts.windowed(2).any { it[0] == "etc" && (it[1] == "profile" || it[1] == "profile.d" || it[1] == "environment") } ->
                "it is a start-up file that every login shell runs"
            scope == "app" && p.startsWith(normalize(filesDir) + "/atom4_linux/") && !p.startsWith(normalize(filesDir) + "/atom4_linux/workspace/") ->
                "it writes inside the Linux distro's own files"
            scope == "app" && (p == normalize(noBackupDir) + "/root-busybox" || p.startsWith(normalize(noBackupDir) + "/root-busybox/")) ->
                "it writes into the root shell's own tools"
            else -> null
        }
    }

    private fun normalize(path: String): String {
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
}
