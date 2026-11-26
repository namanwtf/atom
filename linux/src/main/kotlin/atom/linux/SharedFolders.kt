package atom.linux

import kotlinx.serialization.Serializable

/** An Android directory shown inside Linux at /workspace/mounts/<name>. */
@Serializable
data class SharedFolder(val name: String, val hostPath: String) {
    val guestPath: String get() = "/workspace/mounts/$name"
}

/** Rules for shared folders. Pure, so it is unit-tested. */
object SharedFolderRules {
    const val MAX_FOLDERS = 8
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$")

    /** Host paths (and everything under them) that may never be shared. */
    val FORBIDDEN = listOf("/proc", "/sys", "/dev", "/system", "/vendor", "/apex", "/product", "/odm", "/metadata", "/data/app", "/data/data", "/data/user", "/data/user_de", "/data/misc", "/data/system", "/data/adb")

    sealed interface Problem {
        data object BadName : Problem
        data object DuplicateName : Problem
        data object DuplicatePath : Problem
        data object NotAbsolute : Problem
        data object Forbidden : Problem
        data object InsideLinux : Problem
        data object TooMany : Problem
    }

    /**
     * Checks [candidate] against the existing [folders]. [linuxDir] is the app's Linux directory (sharing it into
     * itself would loop). Existence and readability are checked by the caller, which knows the file system.
     */
    fun validate(candidate: SharedFolder, folders: List<SharedFolder>, linuxDir: String): Problem? {
        val name = candidate.name
        if (!NAME.matches(name) || name == "." || name == "..") return Problem.BadName
        val others = folders.filter { it !== candidate }
        if (others.size >= MAX_FOLDERS) return Problem.TooMany
        if (others.any { it.name.equals(name, ignoreCase = true) }) return Problem.DuplicateName
        val path = normalize(candidate.hostPath) ?: return Problem.NotAbsolute
        val linux = normalize(linuxDir)
        if (linux != null && (path == linux || path.startsWith("$linux/") || linux.startsWith("$path/"))) return Problem.InsideLinux
        if (path == "/" || FORBIDDEN.any { path == it || path.startsWith("$it/") }) return Problem.Forbidden
        if (others.any { normalize(it.hostPath) == path }) return Problem.DuplicatePath
        return null
    }

    /** Collapses "." / ".." / duplicate slashes; null unless absolute (and not escaping the root). */
    fun normalize(path: String): String? {
        if (!path.startsWith("/")) return null
        val parts = ArrayList<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += p
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** The folder above [path] for the folder picker, or null when [path] is already [root] (or outside it). */
    fun parentWithin(path: String, root: String): String? {
        val p = normalize(path) ?: return null
        val r = normalize(root) ?: return null
        if (p == r || !p.startsWith("$r/")) return null
        return p.substringBeforeLast('/').ifEmpty { "/" }
    }

    /** A default mount name from a directory path ("/storage/emulated/0/Download" -> "Download"). */
    fun suggestName(path: String): String =
        path.trimEnd('/').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.', '_', '-').take(32).ifEmpty { "folder" }

    /** Converts a document-tree URI from the system picker on primary storage into a file path, when possible. */
    fun pathFromTreeUri(uri: String): String? {
        val doc = java.net.URLDecoder.decode(uri.substringAfter("/tree/", ""), "UTF-8")
        if (doc.isEmpty()) return null
        val volume = doc.substringBefore(':')
        val rel = doc.substringAfter(':', "")
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}
