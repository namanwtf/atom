package atom.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal val Context.vaultDataStore: DataStore<Preferences> by preferencesDataStore(name = "vault")

/**
 * Secrets (provider API keys, account sign-in tokens, Hugging Face token) encrypted with a non-exportable AES-256-GCM key in the
 * Android Keystore. Ciphertext lives in DataStore; plaintext only exists transiently in memory.
 * Nothing in this class logs, and callers must never log what it returns.
 */
class KeyVault(private val store: DataStore<Preferences>) {

    private val data: Flow<Preferences> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /** Provider ids that have a stored key. Exposes presence only, never values. */
    val savedProviders: Flow<Set<String>> = data
        .map { prefs ->
            prefs.asMap().keys.mapNotNullTo(mutableSetOf()) { key ->
                key.name.takeIf { it.startsWith(API_PREFIX) }?.removePrefix(API_PREFIX)
            }
        }
        .distinctUntilChanged()

    val hasHfToken: Flow<Boolean> = data.map { it[HF_TOKEN] != null }.distinctUntilChanged()

    /** Provider ids with stored account sign-in tokens. These are never exported in a backup. */
    val signedInProviders: Flow<Set<String>> = data
        .map { prefs -> prefs.asMap().keys.mapNotNullTo(mutableSetOf()) { it.name.takeIf { n -> n.startsWith(OAUTH_PREFIX) }?.removePrefix(OAUTH_PREFIX) } }
        .distinctUntilChanged()

    /**
     * Provider ids whose stored key or token could no longer be decrypted (for example after a restore to a new
     * phone). The UI asks the user to enter them again instead of treating them as empty.
     */
    val unreadable: Flow<Set<String>> = data
        .map { prefs ->
            prefs.asMap().keys.mapNotNullTo(mutableSetOf()) {
                it.name.takeIf { n -> n.startsWith(STALE_PREFIX + API_PREFIX) || n.startsWith(STALE_PREFIX + OAUTH_PREFIX) }
                    ?.removePrefix(STALE_PREFIX)?.substringAfter('.')
            }
        }
        .distinctUntilChanged()

    suspend fun apiKey(providerId: String): String? = read(apiKeyName(providerId))

    suspend fun setApiKey(providerId: String, key: String?) = write(apiKeyName(providerId), key)

    suspend fun isUnreadable(providerId: String): Boolean {
        val prefs = data.first()
        return prefs[staleName(apiKeyName(providerId))] != null || prefs[staleName(oauthName(providerId))] != null
    }

    /** Serialized [atom.llm.cloud.OAuthTokens] for an account sign-in. */
    suspend fun oauthTokens(providerId: String): String? = read(oauthName(providerId))

    suspend fun setOauthTokens(providerId: String, json: String?) = write(oauthName(providerId), json)

    /** Gives a duplicated provider the original's API key. Account tokens are never copied. */
    suspend fun copyApiKey(from: String, to: String) {
        apiKey(from)?.let { setApiKey(to, it) }
    }

    suspend fun removeProvider(providerId: String) {
        store.edit {
            it.remove(apiKeyName(providerId))
            it.remove(oauthName(providerId))
            it.remove(staleName(apiKeyName(providerId)))
            it.remove(staleName(oauthName(providerId)))
        }
    }

    /**
     * A feature module's own secret ([namespace] e.g. "speech", "web"). These live under their own prefix, so
     * provider lists, the provider backup and [removeProvider] never see them. Unlike [apiKey], a value that can no
     * longer be decrypted reads as [SecretRead.Unreadable] (also on later reads) until it is written again.
     */
    suspend fun readSecret(namespace: String, name: String): SecretRead {
        val key = moduleName(namespace, name)
        val prefs = data.first()
        val blob = prefs[key] ?: return if (prefs[staleName(key)] != null) SecretRead.Unreadable else SecretRead.Missing
        val plain = withContext(Dispatchers.Default) { runCatching { decrypt(blob) }.getOrNull() }
        if (plain == null) {
            store.edit {
                it.remove(key)
                it[staleName(key)] = "1"
            }
            return SecretRead.Unreadable
        }
        return SecretRead.Value(plain)
    }

    /** Blank or null removes the secret (and its unreadable marker). */
    suspend fun writeSecret(namespace: String, name: String, value: String?) = write(moduleName(namespace, name), value)

    /** Presence only, without decrypting. An unreadable value counts as present. */
    suspend fun hasSecret(namespace: String, name: String): Boolean {
        val key = moduleName(namespace, name)
        val prefs = data.first()
        return prefs[key] != null || prefs[staleName(key)] != null
    }

    sealed interface SecretRead {
        data object Missing : SecretRead
        data class Value(val value: String) : SecretRead
        data object Unreadable : SecretRead
    }

    suspend fun hfToken(): String? = read(HF_TOKEN)

    suspend fun setHfToken(token: String?) = write(HF_TOKEN, token)

    private fun apiKeyName(providerId: String) = stringPreferencesKey(API_PREFIX + providerId)

    private fun oauthName(providerId: String) = stringPreferencesKey(OAUTH_PREFIX + providerId)

    private fun moduleName(namespace: String, name: String) = stringPreferencesKey("$MODULE_PREFIX$namespace.$name")

    private fun staleName(key: Preferences.Key<String>) = stringPreferencesKey(STALE_PREFIX + key.name)

    private suspend fun read(key: Preferences.Key<String>): String? {
        val blob = data.first()[key] ?: return null
        val plain = withContext(Dispatchers.Default) { runCatching { decrypt(blob) }.getOrNull() }
        if (plain == null) {
            // The keystore key was invalidated (e.g. device restored); the ciphertext is unrecoverable, so it is
            // dropped and a marker asks the user to enter the secret again.
            store.edit {
                it.remove(key)
                it[staleName(key)] = "1"
            }
        }
        return plain
    }

    private suspend fun write(key: Preferences.Key<String>, value: String?) {
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty()) {
            store.edit {
                it.remove(key)
                it.remove(staleName(key))
            }
            return
        }
        val blob = withContext(Dispatchers.Default) { encrypt(trimmed) }
        store.edit {
            it[key] = blob
            it.remove(staleName(key))
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + sealed.size)
        out[0] = iv.size.toByte()
        iv.copyInto(out, 1)
        sealed.copyInto(out, 1 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val ivLen = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val sealed = bytes.copyOfRange(1 + ivLen, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "atom.vault.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val API_PREFIX = "api."
        const val OAUTH_PREFIX = "oauth."
        const val STALE_PREFIX = "stale."
        /** Feature-module secrets (speech, web); never exported by the provider backup. */
        const val MODULE_PREFIX = "mod."
        val HF_TOKEN = stringPreferencesKey("hf.token")
    }
}
