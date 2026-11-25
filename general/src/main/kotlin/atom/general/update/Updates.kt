package atom.general.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The fields of a GitHub release this app uses. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
)

sealed interface UpdateStatus {
    /** No repository configured: nothing is ever fetched. */
    data object NotConfigured : UpdateStatus
    data object NoReleases : UpdateStatus
    data class UpToDate(val current: String) : UpdateStatus
    data class Available(val latest: String, val current: String, val releaseUrl: String) : UpdateStatus
    data class Failed(val reason: String) : UpdateStatus
}

object Releases {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val SLUG = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

    fun isValidSlug(slug: String): Boolean = SLUG.matches(slug.trim())

    /** Parses one release object (`/releases/latest`). Throws on malformed JSON. */
    fun parseRelease(body: String): GithubRelease = json.decodeFromString(GithubRelease.serializer(), body)

    /** Parses a release list (`/releases`) and returns the newest published, non-pre-release one. */
    fun parseLatestFromList(body: String): GithubRelease? =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GithubRelease.serializer()), body)
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { r -> SemVer.parse(r.tagName)?.let { it to r } }
            .maxByOrNull { it.first }
            ?.second

    /** Release page to open: GitHub's own link when it is one, else the repo's latest-release page. */
    fun releasePage(slug: String, release: GithubRelease): String =
        release.htmlUrl.takeIf { it.startsWith("https://github.com/") } ?: "https://github.com/$slug/releases/latest"

    /** Compares the running version with [release]. Pure; the network part lives in [UpdateChecker]. */
    fun evaluate(slug: String, current: String, release: GithubRelease?): UpdateStatus {
        if (release == null || release.draft) return UpdateStatus.NoReleases
        val latest = SemVer.parse(release.tagName)
        val mine = SemVer.parse(current)
        val latestLabel = latest?.toString() ?: release.tagName.trim()
        val newer = if (latest != null && mine != null) latest > mine else latestLabel != current.trim()
        return if (newer) {
            UpdateStatus.Available(latestLabel, current, releasePage(slug, release))
        } else {
            UpdateStatus.UpToDate(current)
        }
    }
}

/**
 * Checks GitHub Releases on demand only (About opened or Check tapped). No background job, no polling; a result
 * is reused for a few minutes so re-opening About does not refetch.
 */
object UpdateChecker {
    private const val CACHE_MS = 10 * 60 * 1000L
    private var cached: Triple<String, Long, UpdateStatus>? = null

    fun cachedFor(slug: String): UpdateStatus? = cached?.takeIf {
        it.first == slug && System.currentTimeMillis() - it.second < CACHE_MS
    }?.third

    suspend fun check(slug: String, currentVersion: String, force: Boolean = false): UpdateStatus {
        val repo = slug.trim()
        if (repo.isEmpty()) return UpdateStatus.NotConfigured
        if (!Releases.isValidSlug(repo)) return UpdateStatus.Failed("Invalid repository \"$repo\"")
        if (!force) cachedFor(repo)?.let { return it }
        val status = withContext(Dispatchers.IO) {
            try {
                val (code, body) = get("https://api.github.com/repos/$repo/releases/latest")
                when (code) {
                    200 -> Releases.evaluate(repo, currentVersion, Releases.parseRelease(body))
                    404 -> UpdateStatus.NoReleases
                    403, 429 -> UpdateStatus.Failed("GitHub rate limit reached, try again later")
                    else -> UpdateStatus.Failed("GitHub answered HTTP $code")
                }
            } catch (e: IOException) {
                UpdateStatus.Failed("No connection")
            } catch (e: Exception) {
                UpdateStatus.Failed("Unexpected reply from GitHub")
            }
        }
        if (status !is UpdateStatus.Failed) cached = Triple(repo, System.currentTimeMillis(), status)
        return status
    }

    private fun get(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "Atom-Android")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { s -> s.readBytes().toString(Charsets.UTF_8).take(1_000_000) } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }
}
