package atom.general.update

import java.io.InputStream
import java.security.MessageDigest

/**
 * The pure checks the updater runs before it hands an APK to the system installer. No Android types, so every
 * rule here is unit-tested.
 */
object UpdateVerify {
    private val HEX64 = Regex("[0-9A-Fa-f]{64}")

    /**
     * Reads a `.sha256` file: `sha256sum` output (`<hex>  <name>` or `<hex> *<name>`) or a bare hex digest. When a
     * line names a file, it must be [fileName]. Returns the lower-case digest, or null when none is usable.
     */
    fun parseSha256(text: String, fileName: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val hex = line.substringBefore(' ').substringBefore('\t')
            if (!HEX64.matches(hex)) return null
            val named = line.substring(hex.length).trim().removePrefix("*")
            if (named.isNotEmpty() && named != fileName) continue
            return hex.lowercase()
        }
        return null
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** True when [actual] is the digest [expected] names (case-insensitive, both must be 64 hex characters). */
    fun hashMatches(expected: String, actual: String): Boolean =
        HEX64.matches(expected) && HEX64.matches(actual) &&
            MessageDigest.isEqual(expected.lowercase().toByteArray(), actual.lowercase().toByteArray())

    /** What the updater knows about one APK: the installed app or a downloaded file. */
    data class ApkFacts(
        val packageName: String,
        val versionCode: Long,
        /** SHA-256 of each current signing certificate. */
        val signers: Set<String>,
        /** Signing lineage, oldest first (single-signer APKs with key rotation). Empty when unknown. */
        val signerHistory: List<String> = emptyList(),
    )

    sealed interface ApkCheck {
        data object Ok : ApkCheck
        data object Unreadable : ApkCheck
        data class WrongPackage(val found: String) : ApkCheck
        data class NotNewer(val found: Long, val installed: Long) : ApkCheck
        data object SignerMismatch : ApkCheck
    }

    /**
     * The downloaded APK must be the same app, signed by the same certificate as the installed one (or by a
     * rotated key whose lineage contains it), and have a higher versionCode. Android enforces the first two at
     * install time anyway; checking first gives a clear message and never opens the installer on a bad file.
     */
    fun check(installed: ApkFacts, candidate: ApkFacts?): ApkCheck {
        if (candidate == null || candidate.signers.isEmpty() || installed.signers.isEmpty()) return ApkCheck.Unreadable
        if (candidate.packageName != installed.packageName) return ApkCheck.WrongPackage(candidate.packageName)
        if (!signersMatch(installed, candidate)) return ApkCheck.SignerMismatch
        if (candidate.versionCode <= installed.versionCode) {
            return ApkCheck.NotNewer(candidate.versionCode, installed.versionCode)
        }
        return ApkCheck.Ok
    }

    sealed interface PackCheck {
        data object Ok : PackCheck
        data object Unreadable : PackCheck
        data class WrongPackage(val found: String) : PackCheck
        data class WrongVersion(val found: Long, val app: Long) : PackCheck
        data object SignerMismatch : PackCheck
    }

    /**
     * The on-device AI pack is code that runs inside the app, so it must be the pack ([packPackage]), built for
     * exactly this app version, and signed by exactly the app's own certificate set (no rotation is accepted).
     */
    fun checkPack(app: ApkFacts, pack: ApkFacts?, packPackage: String): PackCheck {
        if (pack == null || pack.signers.isEmpty() || app.signers.isEmpty()) return PackCheck.Unreadable
        if (pack.packageName != packPackage) return PackCheck.WrongPackage(pack.packageName)
        val a = app.signers.map { it.lowercase() }.toSet()
        val b = pack.signers.map { it.lowercase() }.toSet()
        if (a != b) return PackCheck.SignerMismatch
        if (pack.versionCode != app.versionCode) return PackCheck.WrongVersion(pack.versionCode, app.versionCode)
        return PackCheck.Ok
    }

    private fun signersMatch(installed: ApkFacts, candidate: ApkFacts): Boolean {
        val a = installed.signers.map { it.lowercase() }.toSet()
        val b = candidate.signers.map { it.lowercase() }.toSet()
        if (a == b) return true
        // Key rotation: one signer each, and the new APK's lineage includes the installed app's certificate.
        return a.size == 1 && b.size == 1 && candidate.signerHistory.any { it.equals(a.single(), ignoreCase = true) }
    }
}
