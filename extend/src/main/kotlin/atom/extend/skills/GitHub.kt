package atom.extend.skills

import atom.extend.HttpGet
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

enum class GitHubKind { Repo, Tree, Blob }

/**
 * A parsed GitHub location. For tree and blob URLs the split between branch and path is ambiguous when the
 * branch has slashes, so [candidates] lists every (ref, path) split, shortest ref first.
 */
data class GitHubLocation(val owner: String, val repo: String, val kind: GitHubKind, val rest: List<String>) {
    val candidates: List<Pair<String, String>>
        get() = if (rest.isEmpty()) emptyList() else (1..rest.size).map { rest.take(it).joinToString("/") to rest.drop(it).joinToString("/") }

    companion object {
        private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

        fun parse(input: String): GitHubLocation? {
            val text = input.trim().removeSuffix("/")
            if (!text.contains("://") && Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$").matches(text)) {
                val (o, r) = text.split('/')
                return GitHubLocation(o, r.removeSuffix(".git"), GitHubKind.Repo, emptyList()).takeIf { valid(it) }
            }
            val uri = runCatching { URI(if (text.contains("://")) text else "https://$text") }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("https", "http")) return null
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            val segs = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
            if (segs.size < 2) return null
            val owner = segs[0]
            val repo = segs[1].removeSuffix(".git")
            val loc = when (host) {
                "github.com" -> when {
                    segs.size == 2 -> GitHubLocation(owner, repo, GitHubKind.Repo, emptyList())
                    segs[2] == "tree" && segs.size > 3 -> GitHubLocation(owner, repo, GitHubKind.Tree, segs.drop(3))
                    segs[2] == "blob" && segs.size > 4 -> GitHubLocation(owner, repo, GitHubKind.Blob, segs.drop(3))
                    else -> return null
                }
                "raw.githubusercontent.com" -> {
                    val rest = segs.drop(2).let { if (it.size > 2 && it[0] == "refs" && it[1] == "heads") it.drop(2) else it }
                    if (rest.size < 2) return null
                    GitHubLocation(owner, repo, GitHubKind.Blob, rest)
                }
                else -> return null
            }
            return loc.takeIf { valid(it) }
        }

        private fun valid(l: GitHubLocation) =
            NAME.matches(l.owner) && NAME.matches(l.repo) && listOf(l.owner, l.repo).none { it == "." || it == ".." }
    }
}

data class RemoteFile(val path: String, val size: Long)

/** Finds and downloads skills from a public GitHub repository through the REST API and raw file URLs. */
class GitHubSkillSource(
    private val http: HttpGet,
    private val limits: SkillLimits = SkillLimits(),
    private val api: String = "https://api.github.com",
    private val raw: String = "https://raw.githubusercontent.com",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val headers = mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")

    class Found(val location: GitHubLocation, val ref: String, val skillDirs: List<String>, val files: List<RemoteFile>, val truncated: Boolean)

    suspend fun discover(loc: GitHubLocation): Found {
        val splits = if (loc.kind == GitHubKind.Repo) listOf(defaultBranch(loc) to "") else loc.candidates.take(6)
        for ((ref, path) in splits) {
            val tree = http.get("$api/repos/${loc.owner}/${loc.repo}/git/trees/${encodePath(ref)}?recursive=1", headers, 8L shl 20)
            if (tree.code == 404 || tree.code == 422) continue
            failOn(tree.code)
            val obj = json.parseToJsonElement(tree.text).jsonObject
            val files = obj["tree"]?.jsonArray.orEmpty().mapNotNull { e ->
                val o = e.jsonObject
                if (o["type"]?.jsonPrimitive?.content != "blob") return@mapNotNull null
                RemoteFile(o["path"]?.jsonPrimitive?.content ?: return@mapNotNull null, o["size"]?.jsonPrimitive?.longOrNull ?: 0)
            }
            val truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true
            return Found(loc, ref, skillDirs(files, path, loc.kind), files, truncated)
        }
        throw SkillException("That branch or path does not exist in ${loc.owner}/${loc.repo}.")
    }

    private suspend fun defaultBranch(loc: GitHubLocation): String {
        val reply = http.get("$api/repos/${loc.owner}/${loc.repo}", headers, 1L shl 20)
        if (reply.code == 404) throw SkillException("${loc.owner}/${loc.repo} was not found or is private.")
        failOn(reply.code)
        return json.parseToJsonElement(reply.text).jsonObject["default_branch"]?.jsonPrimitive?.content ?: "main"
    }

    private fun failOn(code: Int) {
        when {
            code == 403 || code == 429 -> throw SkillException("GitHub's rate limit was reached (60 requests per hour without sign-in). Try again later.")
            code !in 200..299 -> throw SkillException("GitHub answered with HTTP $code.")
        }
    }

    /** Downloads [dir] (a repository path holding SKILL.md) into [target]. */
    suspend fun download(found: Found, dir: String, target: File): StagedSkill = coroutineScope {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val files = found.files.filter { it.path.startsWith(prefix) }
        if (files.size > limits.maxFiles) throw SkillException("\"${dir.ifEmpty { found.location.repo }}\" has more than ${limits.maxFiles} files.")
        files.firstOrNull { it.size > limits.maxFileBytes }?.let { throw SkillException("\"${it.path}\" is larger than ${limits.maxFileBytes shr 20} MiB.") }
        if (files.sumOf { it.size } > limits.maxTotalBytes) throw SkillException("\"$dir\" is larger than ${limits.maxTotalBytes shr 20} MiB.")
        val base = target.canonicalFile.apply { mkdirs() }
        val gate = Semaphore(4)
        files.map { f ->
            async {
                val rel = SkillZip.safeRelativePath(f.path.removePrefix(prefix)) ?: throw SkillException("Unsafe path \"${f.path}\".")
                val out = File(base, rel).canonicalFile
                if (!out.path.startsWith(base.path + File.separator)) throw SkillException("Unsafe path \"${f.path}\".")
                val reply = gate.withPermit {
                    http.get("$raw/${found.location.owner}/${found.location.repo}/${encodePath(found.ref)}/${encodePath(f.path)}", emptyMap(), limits.maxFileBytes)
                }
                if (reply.code !in 200..299) throw SkillException("Could not download \"${f.path}\" (HTTP ${reply.code}).")
                out.parentFile?.mkdirs()
                out.writeBytes(reply.body)
            }
        }.forEach { it.await() }
        val manifest = SkillManifest.parse(File(base, SkillManifest.FILE).takeIf { it.isFile }?.readText()
            ?: throw SkillException("\"$dir\" has no SKILL.md.")).getOrElse { throw it }
        StagedSkill(manifest, base)
    }

    companion object {
        /** Outermost directories holding a SKILL.md under [path] (or the skill [path] itself points into). */
        fun skillDirs(files: List<RemoteFile>, path: String, kind: GitHubKind): List<String> {
            val all = files.map { it.path }.filter { it.substringAfterLast('/') == SkillManifest.FILE }.map { it.substringBeforeLast('/', "") }.toSet()
            if (kind == GitHubKind.Blob) {
                val dir = if (path.substringAfterLast('/') == SkillManifest.FILE) path.substringBeforeLast('/', "") else path.substringBeforeLast('/', "")
                return listOfNotNull(dir.takeIf { it in all })
            }
            val under = all.filter { path.isEmpty() || it == path || it.startsWith("$path/") }
            return under.filter { d -> under.none { o -> o != d && (o.isEmpty() || d.startsWith("$o/")) } }.sorted()
        }

        fun encodePath(path: String): String =
            path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
