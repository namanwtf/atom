package atom.linux.install

import atom.linux.Distro
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal enum class Compression { Gzip, Xz }

/** A verified place to download a root filesystem from. */
internal data class RootfsSource(
    val distro: Distro,
    val release: String,
    val url: String,
    val sha256: String,
    val compression: Compression,
)

/**
 * Where the root filesystems come from.
 *
 * - Alpine: the official minirootfs from dl-cdn.alpinelinux.org, pinned to one release. Its SHA-256 is
 *   taken from the `.sha256` file Alpine publishes next to the tarball (checked 2026-11-25).
 * - Debian: the official-image builds of the Linux Containers project (images.linuxcontainers.org, the
 *   image server behind LXC/Incus). They rebuild the stable suite daily and publish SHA256SUMS beside each
 *   build; the newest build of the pinned suite is resolved at install time from their machine-readable
 *   index and verified against that build's SHA256SUMS.
 */
internal object DistroSources {
    const val ALPINE_VERSION = "3.24.4"
    const val ALPINE_BRANCH = "v3.24"
    const val ALPINE_URL =
        "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/releases/aarch64/alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"
    const val ALPINE_SHA256 = "4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de"

    /** Debian 13, the current stable release. */
    const val DEBIAN_SUITE = "trixie"
    const val LXC_BASE = "https://images.linuxcontainers.org"
    const val LXC_INDEX = "$LXC_BASE/meta/1.0/index-system"

    suspend fun resolve(distro: Distro, fetchText: suspend (String) -> String = ::httpGetText): RootfsSource =
        when (distro) {
            Distro.Alpine -> RootfsSource(distro, ALPINE_VERSION, ALPINE_URL, ALPINE_SHA256, Compression.Gzip)
            Distro.Debian -> {
                val index = fetchText(LXC_INDEX)
                val (build, path) = latestLxcBuild(index, "debian", DEBIAN_SUITE, "arm64")
                    ?: throw IOException("The Debian $DEBIAN_SUITE arm64 image is not listed on images.linuxcontainers.org right now.")
                val dir = LXC_BASE + path.trimEnd('/') + "/"
                val sums = fetchText(dir + "SHA256SUMS")
                val sha = sha256For(sums, "rootfs.tar.xz")
                    ?: throw IOException("The Debian image's SHA256SUMS has no entry for rootfs.tar.xz.")
                RootfsSource(distro, "13 ($DEBIAN_SUITE, build $build)", dir + "rootfs.tar.xz", sha, Compression.Xz)
            }
        }

    /**
     * Parses the LXC `index-system` CSV (`distro;release;arch;variant;build;path`) and returns the newest
     * `default` build for the given distro/release/arch as (build, path).
     */
    fun latestLxcBuild(index: String, distro: String, release: String, arch: String): Pair<String, String>? =
        index.lineSequence()
            .map { it.trim().split(';') }
            .filter { it.size >= 6 && it[0] == distro && it[1] == release && it[2] == arch && it[3] == "default" }
            .filter { it[5].startsWith("/images/") && !it[5].contains("..") }
            .maxByOrNull { it[4] }
            ?.let { it[4] to it[5] }

    /** Finds the hash for [fileName] in a `sha256sum` style listing. */
    fun sha256For(sums: String, fileName: String): String? =
        sums.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName }
            ?.get(0)?.lowercase()
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Atom-Linux/1")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode} from ${URL(url).host}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
