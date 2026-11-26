package atom.core.home

/** What readiness needs to know about one configured cloud provider. Never carries a key value. */
data class ProviderFacts(
    val id: String,
    val enabled: Boolean,
    /** Local servers and keyless endpoints work without a key. */
    val keyOptional: Boolean,
    /** Signs in with an account (ChatGPT, Grok) instead of an API key. */
    val accountAuth: Boolean,
    /** Model ids the provider lists; empty means "any id is accepted". */
    val modelIds: List<String> = emptyList(),
)

/**
 * Whether the home chat has a model it can actually send to. A fresh install pre-selects a provider without a key;
 * that selection is not usable, so the capsule reads "Not selected" and sending is blocked until the user adds a
 * key, signs in, picks another provider or installs an on-device model.
 */
object ModelReadiness {
    /** The provider can authenticate: a saved key, an account sign-in, or no key needed at all. */
    fun hasCredentials(p: ProviderFacts, savedKeys: Set<String>, signedIn: Set<String>): Boolean = when {
        p.accountAuth -> p.id in signedIn
        p.keyOptional -> true
        else -> p.id in savedKeys
    }

    /**
     * A cloud selection is usable when its provider is enabled, lists the model (or lists none) and can
     * authenticate. A selection whose provider is not configured is only usable as an ad-hoc custom endpoint
     * ([customBaseUrl] set), which runs without a stored provider.
     */
    fun cloudReady(
        providerId: String,
        model: String,
        providers: List<ProviderFacts>,
        savedKeys: Set<String>,
        signedIn: Set<String>,
        customBaseUrl: String? = null,
        customId: String = "custom",
    ): Boolean {
        val p = providers.firstOrNull { it.id == providerId }
            ?: return providerId == customId && !customBaseUrl.isNullOrBlank()
        if (!p.enabled) return false
        if (p.modelIds.isNotEmpty() && model !in p.modelIds) return false
        return hasCredentials(p, savedKeys, signedIn)
    }

    /**
     * An on-device selection is usable while its model is still installed and the optional on-device AI pack (which
     * carries the engines) is installed too.
     */
    fun localReady(installedId: String, installed: Collection<String>, packReady: Boolean = true): Boolean =
        packReady && installedId in installed

    /** True when at least one provider or on-device model could be picked and used. */
    fun anyUsable(
        providers: List<ProviderFacts>,
        savedKeys: Set<String>,
        signedIn: Set<String>,
        installedCount: Int,
        packReady: Boolean = true,
    ): Boolean = (packReady && installedCount > 0) || providers.any { it.enabled && hasCredentials(it, savedKeys, signedIn) }
}
