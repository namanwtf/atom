package atom.web.files

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
     *
     * Paths are compared both as written and canonicalized ([canonical]: symlinks resolved, also for a file that does
     * not exist yet), so `/data/data/<pkg>/...`, `/proc/self/root/...` or a symlinked folder cannot reach the distro
     * tree or a start-up file under another spelling.
     */
    fun reason(
        scope: String,
        path: String,
        filesDir: String,
        noBackupDir: String,
        canonical: (String) -> String = ::canonical,
    ): String? {
        if (scope == "linux") return "it writes inside the Linux environment, whose files its shells and tools run"
        val spellings = linkedSetOf(normalize(path), safely(canonical, path))
        val files = linkedSetOf(normalize(filesDir), safely(canonical, filesDir))
        val noBackup = linkedSetOf(normalize(noBackupDir), safely(canonical, noBackupDir))
        for (p in spellings) {
            val parts = p.split('/').filter { it.isNotEmpty() }
            val name = parts.lastOrNull().orEmpty()
            when {
                name in STARTUP_NAMES || ".git" in parts || p.endsWith("/.config/git/config") ->
                    return "$name is a start-up or settings file that a shell or git runs on its own"
                parts.windowed(2).any { it[0] == "etc" && (it[1] == "profile" || it[1] == "profile.d" || it[1] == "environment") } ->
                    return "it is a start-up file that every login shell runs"
                scope == "app" && files.any { f -> p.startsWith("$f/atom4_linux/") && !p.startsWith("$f/atom4_linux/workspace/") } ->
                    return "it writes inside the Linux distro's own files"
                scope == "app" && noBackup.any { d -> p == "$d/root-busybox" || p.startsWith("$d/root-busybox/") } ->
                    return "it writes into the root shell's own tools"
            }
        }
        return null
    }

    private fun safely(canonical: (String) -> String, path: String): String = runCatching { normalize(canonical(path)) }.getOrElse { normalize(path) }

    /**
     * [path] with every symlink resolved. For a path that does not exist yet, its nearest existing parent is resolved
     * and the rest appended; a dangling symlink is followed to where a write would land.
     */
    fun canonical(path: String): String = canonical(Paths.get(path).toAbsolutePath(), 0)

    private fun canonical(p: Path, depth: Int): String {
        if (depth > MAX_LINKS) return p.normalize().toString()
        if (Files.isSymbolicLink(p)) {
            val target = Files.readSymbolicLink(p)
            return canonical((p.parent?.resolve(target) ?: target).toAbsolutePath(), depth + 1)
        }
        if (Files.exists(p)) return p.toRealPath().toString()
        val parent = p.parent ?: return p.normalize().toString()
        val base = canonical(parent, depth)
        return when (val name = p.fileName?.toString().orEmpty()) {
            "", "." -> base
            ".." -> Paths.get(base).parent?.toString() ?: "/"
            else -> base.trimEnd('/') + "/" + name
        }
    }

    private const val MAX_LINKS = 16

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
