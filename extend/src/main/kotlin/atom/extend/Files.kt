package atom.extend

import java.io.File
import java.security.MessageDigest

internal object Files {
    /** Writes through a sibling temp file and a rename, so a crash never leaves a half-written file. */
    fun writeAtomic(file: File, text: String) = writeAtomic(file, text.toByteArray(Charsets.UTF_8))

    fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }

    fun readOrNull(file: File): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
