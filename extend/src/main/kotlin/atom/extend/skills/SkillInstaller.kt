package atom.extend.skills

import java.io.File
import java.io.InputStream

class GitHubInstall(val report: InstallReport, val source: String, val truncated: Boolean)

class SkillInstaller(private val store: SkillStore, private val github: GitHubSkillSource) {

    suspend fun importZip(input: InputStream, allowReplace: suspend (List<String>) -> Boolean): InstallReport {
        val staging = store.newStaging()
        val staged = try {
            SkillZip.extract(input, staging)
        } catch (e: Exception) {
            store.discard(staging)
            throw e
        }
        return store.install(listOf(staged), allowReplace)
    }

    /** Installs every skill under [url] (at most [MAX_PER_INSTALL]); [names] narrows the selection by folder or skill name. */
    suspend fun installGitHub(url: String, names: Set<String>?, allowReplace: suspend (List<String>) -> Boolean): GitHubInstall {
        val loc = GitHubLocation.parse(url)
            ?: throw SkillException("Not a GitHub repository, tree or blob URL: $url")
        val found = github.discover(loc)
        var dirs = found.skillDirs
        if (dirs.isEmpty()) throw SkillException("No SKILL.md found at that location${if (found.truncated) " (the repository listing was truncated; use a narrower tree URL)" else ""}.")
        if (!names.isNullOrEmpty()) {
            val wanted = names.map { it.trim().lowercase() }.toSet()
            dirs = dirs.filter { d -> d.lowercase() in wanted || d.substringAfterLast('/').lowercase() in wanted }
            if (dirs.isEmpty()) throw SkillException("None of ${names.joinToString()} were found. Available: ${listing(found.skillDirs)}")
        }
        if (dirs.size > MAX_PER_INSTALL) {
            throw SkillException(
                "Found ${dirs.size} skills; at most $MAX_PER_INSTALL install at once. Pass 'names' or a narrower tree URL. " +
                    "Available: ${listing(dirs)}",
            )
        }
        val staging = store.newStaging()
        val staged = try {
            dirs.mapIndexed { i, d -> github.download(found, d, File(staging, "s$i")) }
        } catch (e: Exception) {
            store.discard(staging)
            throw e
        }
        val report = store.install(staged, allowReplace)
        return GitHubInstall(report, "${loc.owner}/${loc.repo}@${found.ref}", found.truncated)
    }

    private fun listing(dirs: List<String>): String =
        dirs.take(40).joinToString { it.substringAfterLast('/').ifEmpty { "(repository root)" } } + if (dirs.size > 40) ", …" else ""

    companion object {
        const val MAX_PER_INSTALL = 20
    }
}
