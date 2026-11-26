package atom.general.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A release file could not be fetched or failed a check; the message is shown to the user. */
class ReleaseFileException(message: String) : Exception(message)

/**
 * Files of this repository's GitHub Releases: the app update and the optional on-device AI pack both come from
 * here, and only from here. Every download must be a complete upload under `https://github.com/<slug>/releases/
 * download/`, match the SHA-256 file published next to it, and is then checked against the running app's identity
 * ([UpdateVerify]). No proxies, no telemetry, nothing runs in the background.
 */
object ReleaseFiles {
    /** The release tagged [tag] (for example "v4.0.2"), or null when the repository has no such release. */
    suspend fun releaseByTag(slug: String, tag: String): GithubRelease? = withContext(Dispatchers.IO) {
        if (!Releases.isValidSlug(slug)) throw ReleaseFileException("invalid repository")
        val (code, body) = apiGet("https://api.github.com/repos/${slug.trim()}/releases/tags/$tag")
        when (code) {
            200 -> Releases.parseRelease(body).takeIf { !it.draft }
            404 -> null
            403, 429 -> throw ReleaseFileException("GitHub rate limit reached, try again later")
            else -> throw ReleaseFileException("GitHub answered HTTP $code")
        }
    }

    /** The file [name] of [release] and its `<name>.sha256`, both complete uploads of this repository. */
    fun asset(slug: String, release: GithubRelease, name: String, maxBytes: Long = Releases.MAX_APK_BYTES): ReleaseApk? {
        val prefix = "https://github.com/${slug.trim()}/releases/download/"
        fun usable(a: GithubAsset) = a.state == "uploaded" && a.downloadUrl.startsWith(prefix) &&
            !a.downloadUrl.substring(prefix.length).contains("..")
        val file = release.assets.firstOrNull { it.name == name && usable(it) && it.size in 1..maxBytes } ?: return null
        val sha = release.assets.firstOrNull { it.name == "$name.sha256" && usable(it) } ?: return null
        return ReleaseApk(file.name, file.downloadUrl, file.size, sha.downloadUrl)
    }

    /**
     * Downloads [apk] into [dir] (a folder that holds only these downloads; it is cleaned first) and checks it
     * against its published SHA-256. Returns the complete file. Blocking: call on an I/O thread.
     */
    fun download(apk: ReleaseApk, dir: File, progress: (done: Long, total: Long) -> Unit): File {
        cleanDir(dir)
        val shaText = fetchSmall(apk.sha256Url)
        val expected = UpdateVerify.parseSha256(shaText, apk.name) ?: throw ReleaseFileException("checksum file is unreadable")
        val part = File(dir, apk.name + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        val conn = open(apk.url)
        try {
            if (conn.responseCode != 200) throw ReleaseFileException("GitHub answered HTTP ${conn.responseCode}")
            if (conn.url.protocol != "https") throw ReleaseFileException("insecure redirect")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > apk.size || done > Releases.MAX_APK_BYTES) throw ReleaseFileException("download is larger than published")
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            progress(done, apk.size)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (done != apk.size) {
            part.delete()
            throw ReleaseFileException("download is incomplete")
        }
        if (!UpdateVerify.hashMatches(expected, UpdateVerify.hex(md.digest()))) {
            part.delete()
            throw ReleaseFileException("checksum does not match")
        }
        val target = File(dir, apk.name)
        if (!part.renameTo(target)) {
            part.delete()
            throw ReleaseFileException("could not save the download")
        }
        return target
    }

    /** Package name, versionCode and signing certificates of the running app. */
    fun installedFacts(context: Context): UpdateVerify.ApkFacts? = facts(runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull())

    /** The same facts read from the APK file at [path] (not installed), or null when it is not a readable APK. */
    fun archiveFacts(context: Context, path: String): UpdateVerify.ApkFacts? = facts(runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull())

    private fun facts(info: PackageInfo?): UpdateVerify.ApkFacts? {
        if (info == null) return null
        val signing = info.signingInfo ?: return null
        fun digest(s: Signature) = UpdateVerify.sha256Hex(s.toByteArray())
        val current = signing.apkContentsSigners.orEmpty().map(::digest).toSet()
        val history = if (signing.hasMultipleSigners()) emptyList() else signing.signingCertificateHistory.orEmpty().map(::digest)
        return UpdateVerify.ApkFacts(info.packageName, info.longVersionCode, current, history)
    }

    /** Flat, non-recursive clean: download folders hold plain files only. */
    private fun cleanDir(dir: File) {
        dir.mkdirs()
        dir.listFiles().orEmpty().forEach { if (it.isFile) it.delete() }
    }

    internal fun apiGet(url: String): Pair<Int, String> {
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

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        // github.com answers release downloads with a redirect to its own download host; https only.
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("User-Agent", "Atom-Android")
        return conn
    }

    private fun fetchSmall(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw ReleaseFileException("GitHub answered HTTP ${conn.responseCode}")
            if (conn.url.protocol != "https") throw ReleaseFileException("insecure redirect")
            return conn.inputStream.use { s ->
                val buf = ByteArray(4096)
                var len = 0
                while (len < buf.size) {
                    val n = s.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                }
                String(buf, 0, len, Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }
}
