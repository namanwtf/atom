package atom.extend.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import atom.extend.SecretRead
import atom.extend.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Default [SecretStore]: AES-256-GCM with a non-exportable Android Keystore key; ciphertext in private prefs. */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences("extend_secrets", Context.MODE_PRIVATE)

    override suspend fun read(key: String): SecretRead = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext SecretRead.Missing
        try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false) ?: return@withContext SecretRead.Unreadable, GCMParameterSpec(128, bytes, 0, IV))
            SecretRead.Value(cipher.doFinal(bytes, IV, bytes.size - IV).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            SecretRead.Unreadable
        }
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) { "Could not save the secret" }
    }

    override suspend fun remove(key: String) {
        withContext(Dispatchers.IO) { prefs.edit().remove(key).commit() }
    }

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "atom_extend_secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
