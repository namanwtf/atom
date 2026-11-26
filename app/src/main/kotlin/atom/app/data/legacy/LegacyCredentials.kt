package atom.app.data.legacy

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import atom.core.legacy.LegacyFiles
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The previous app's account sign-ins: one AES-GCM file per provider under `no_backup/provider_credentials/`, sealed
 * with its Android Keystore key (same app, same UID, so the key is still there after the update). Layout: a 12-byte
 * IV, then ciphertext and the 128-bit tag; the provider id is the AAD. Files and the key are only read, never changed.
 * Plaintext (tokens) is returned to the caller and never logged.
 */
internal object LegacyCredentials {
    sealed interface Read {
        data object Missing : Read

        /** No key, a wrong tag, a damaged file: the user signs in again. */
        data object Undecryptable : Read

        /** The file is there but reading it failed (a Keystore or storage error): the import tries again later. */
        data object Failed : Read

        class Plain(val json: String) : Read
    }

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val MIN_BYTES = 13L
    private const val MAX_BYTES = 262_144L

    fun read(context: Context, providerId: String): Read {
        val dir = File(context.noBackupFilesDir, LegacyFiles.CREDENTIALS_DIR)
        val name = LegacyFiles.credentialFileName(providerId)
        // AtomicFile: a ".bak" copy is the valid one when present; ".new" is an unfinished write and is ignored.
        val file = listOf(File(dir, "$name.bak"), File(dir, name)).firstOrNull { it.isFile } ?: return Read.Missing
        if (file.length() !in MIN_BYTES..MAX_BYTES) return Read.Undecryptable
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return Read.Failed
        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = keyStore.getKey(LegacyFiles.CREDENTIALS_ALIAS, null) as? SecretKey ?: return Read.Undecryptable
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
            Read.Plain(String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8))
        } catch (e: AEADBadTagException) {
            // Wrong key or damaged file: this never gets better.
            Read.Undecryptable
        } catch (e: KeyPermanentlyInvalidatedException) {
            Read.Undecryptable
        } catch (e: Exception) {
            Read.Failed
        }
    }
}
