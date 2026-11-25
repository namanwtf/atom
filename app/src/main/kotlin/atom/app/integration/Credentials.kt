package atom.app.integration

import android.content.Context
import atom.app.data.KeyVault
import atom.app.data.ProviderStore
import atom.llm.cloud.Wire
import atom.voice.speech.CredentialUnreadableException
import atom.voice.speech.SpeechSecret
import atom.voice.speech.VoiceCredentials
import atom.web.ProviderCredential
import atom.web.ProviderKeys
import atom.web.SecretStore as WebSecretStore

/**
 * Speech-service secrets in the app's Keystore vault, under their own "mod.speech." prefix: never listed as
 * providers and never written to a backup. A value that can no longer be decrypted throws, so the voice screens
 * ask for it again instead of treating it as empty.
 */
class VaultVoiceCredentials(private val context: Context, private val vault: KeyVault) : VoiceCredentials {
    override suspend fun get(secret: SpeechSecret): String? = when (val r = vault.readSecret(NAMESPACE, secret.name)) {
        KeyVault.SecretRead.Missing -> null
        is KeyVault.SecretRead.Value -> r.value
        KeyVault.SecretRead.Unreadable -> throw CredentialUnreadableException(secret)
    }

    override suspend fun set(secret: SpeechSecret, value: String?) = vault.writeSecret(NAMESPACE, secret.name, value)

    override suspend fun has(secret: SpeechSecret): Boolean = vault.hasSecret(NAMESPACE, secret.name)

    /** A chat provider's key, matched by id first, then by the preset it was created from (e.g. a renamed copy). */
    override suspend fun providerKey(providerId: String): String? {
        vault.apiKey(providerId)?.let { return it }
        val providers = ProviderStore.get(context).all()
        val match = providers.firstOrNull { it.enabled && (it.presetId == providerId || it.family == providerId) } ?: return null
        return vault.apiKey(match.id)
    }

    private companion object {
        const val NAMESPACE = "speech"
    }
}

/** The web module's own key overrides (search / reader / image backends), under "mod.web.". Not backed up. */
class VaultWebSecrets(private val vault: KeyVault) : WebSecretStore {
    override suspend fun get(name: String): String? = (vault.readSecret(NAMESPACE, name) as? KeyVault.SecretRead.Value)?.value

    override suspend fun put(name: String, value: String?) = vault.writeSecret(NAMESPACE, name, value)

    override suspend fun isUnreadable(name: String): Boolean = vault.readSecret(NAMESPACE, name) == KeyVault.SecretRead.Unreadable

    private companion object {
        const val NAMESPACE = "web"
    }
}

/** Enabled model providers with a readable API key, for the web and image backends to reuse. */
class VaultProviderKeys(private val context: Context, private val vault: KeyVault) : ProviderKeys {
    override suspend fun all(): List<ProviderCredential> =
        ProviderStore.get(context).all().filter { it.enabled }.mapNotNull { p ->
            val key = vault.apiKey(p.id)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProviderCredential(
                providerId = p.id,
                displayName = p.displayName,
                baseUrl = p.baseUrl,
                apiKey = key,
                isVertex = p.wire == Wire.Vertex,
                vertexProject = p.vertexProject,
                vertexLocation = p.vertexLocation,
            )
        }
}
