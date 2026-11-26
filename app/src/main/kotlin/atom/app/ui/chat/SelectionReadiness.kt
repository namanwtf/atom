package atom.app.ui.chat

import atom.app.AppGraph
import atom.app.data.EngineSelection
import atom.app.engine.ModelInfo
import atom.app.ondevice.OnDevicePack
import atom.app.ondevice.PackState
import atom.core.home.ModelReadiness
import atom.core.home.ProviderFacts
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.Providers
import atom.models.InstalledModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Snapshot of what decides whether a model selection is usable. Never holds key values. */
internal data class Readiness(
    val providers: List<ProviderConfig>,
    val savedKeys: Set<String>,
    val signedIn: Set<String>,
    val installed: List<InstalledModel>,
    val unreadable: Set<String> = emptySet(),
    /** The on-device AI pack is installed (or still being checked at start-up): on-device models can run. */
    val packReady: Boolean = true,
) {
    val facts: List<ProviderFacts> = providers.map { p ->
        ProviderFacts(p.id, p.enabled, p.keyOptional, accountAuth = p.auth != AuthMethod.ApiKey, modelIds = p.models.map { it.id })
    }

    /** True when [selection] can authenticate and still exists in the provider list or the installed models. */
    fun usable(selection: EngineSelection): Boolean = when (selection) {
        is EngineSelection.Cloud -> ModelReadiness.cloudReady(
            selection.providerId,
            selection.model,
            facts,
            savedKeys,
            signedIn,
            customBaseUrl = selection.baseUrl,
            customId = Providers.CUSTOM_ID,
        )
        is EngineSelection.Local -> ModelReadiness.localReady(selection.installedModelId, installed.map { it.id }, packReady)
    }

    companion object {
        /** The provider list, key presence, sign-ins and installed models, kept current. */
        fun of(graph: AppGraph): Flow<Readiness> = combine(
            graph.providers.providers,
            graph.vault.savedProviders,
            graph.vault.signedInProviders,
            graph.models.installed.combine(OnDevicePack.state) { installed, pack -> installed to packUsable(pack) },
            graph.vault.unreadable,
        ) { providers, saved, signedIn, local, unreadable ->
            Readiness(providers, saved, signedIn, local.first, unreadable, packReady = local.second)
        }

        /** Ready, or still checking at start-up (so a saved on-device choice does not flash "Not selected"). */
        fun packUsable(state: PackState): Boolean = state is PackState.Ready || state == PackState.Checking
    }
}

/**
 * Why a send to [selection] cannot go out, or null when it can. The home chat and the mini chat both use this, so
 * they agree on "Not selected" and "Sign in to …". [ready] null (still loading) counts as usable.
 */
internal fun notReadyReason(selection: EngineSelection?, ready: Readiness?, info: ModelInfo): NotReady? {
    if (selection == null) return NotReady.Configure
    if (selection is EngineSelection.Local && ready != null && !ready.packReady) return NotReady.NeedsPack
    val exists = runCatching { info.exists(selection) }.getOrDefault(true)
    if (exists && (ready == null || ready.usable(selection))) return null
    val provider = (selection as? EngineSelection.Cloud)?.let { runCatching { info.provider(it) }.getOrNull() }
    return when {
        provider != null && provider.enabled && provider.auth != AuthMethod.ApiKey && ready != null && provider.id !in ready.signedIn -> NotReady.SignIn
        provider != null && ready != null && provider.id in ready.unreadable -> NotReady.KeyUnreadable
        else -> NotReady.Configure
    }
}
