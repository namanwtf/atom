package atom.app.engine

import android.content.Context
import atom.app.data.EngineSelection
import atom.app.data.ProviderStore
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.PresetCatalog
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.Reasoning
import atom.llm.cloud.ReasoningInfo
import atom.models.ModelStore

/**
 * Read-only facts about a selection for the runtime and the UI: which reasoning levels it takes, its context
 * window, whether it still exists, and how to label it. All lookups are synchronous reads of loaded state.
 */
class ModelInfo internal constructor(
    private val providers: ProviderStore,
    private val localModels: () -> ModelStore,
) {
    fun provider(selection: EngineSelection.Cloud): ProviderConfig? = providers.peek(selection.providerId)

    fun model(selection: EngineSelection.Cloud): ModelConfig? = provider(selection)?.model(selection.model)

    fun reasoning(selection: EngineSelection?): ReasoningInfo = when (selection) {
        is EngineSelection.Cloud -> (model(selection) ?: ModelConfig(selection.model)).reasoning
        is EngineSelection.Local, null -> ReasoningInfo.None
    }

    /** The explicit efforts the selection accepts; empty for non-reasoning and forced-reasoning models. */
    fun reasoningLevels(selection: EngineSelection?): List<ReasoningEffort> = reasoning(selection).levels

    fun supportsReasoning(selection: EngineSelection?): Boolean = reasoning(selection).supported

    /** [saved] brought into what [selection] accepts (nearest level, or model default). */
    fun snapReasoning(selection: EngineSelection?, saved: ReasoningEffort?): ReasoningEffort? = Reasoning.snap(saved, reasoning(selection))

    /** Configured or detected context window in tokens; null when unknown (automatic compaction stays off). */
    fun contextWindow(selection: EngineSelection?): Int? = when (selection) {
        is EngineSelection.Cloud -> (model(selection) ?: ModelConfig(selection.model)).effectiveContextWindow
        is EngineSelection.Local -> RoutedEngine.LOCAL_CONTEXT_WINDOW
        null -> null
    }

    fun vision(selection: EngineSelection?): Boolean? = when (selection) {
        is EngineSelection.Cloud -> (model(selection) ?: ModelConfig(selection.model)).vision
        else -> null
    }

    /** True when the provider (enabled) or on-device model behind [selection] is still there. */
    fun exists(selection: EngineSelection): Boolean = when (selection) {
        is EngineSelection.Cloud -> {
            val config = provider(selection)
            if (config == null) {
                selection.providerId == atom.llm.cloud.Providers.CUSTOM_ID && !selection.baseUrl.isNullOrBlank()
            } else {
                config.enabled && (config.models.isEmpty() || config.model(selection.model) != null)
            }
        }
        is EngineSelection.Local -> localModels().installed.value.any { it.id == selection.installedModelId }
    }

    fun providerName(selection: EngineSelection.Cloud): String =
        provider(selection)?.displayName ?: PresetCatalog.byId(selection.providerId)?.displayName ?: selection.providerId

    fun modelName(selection: EngineSelection): String = when (selection) {
        is EngineSelection.Cloud -> model(selection)?.displayName
            ?: PresetCatalog.model(selection.providerId, selection.model)?.displayName
            ?: selection.model
        is EngineSelection.Local -> localModels().installed.value.firstOrNull { it.id == selection.installedModelId }?.name
            ?: selection.installedModelId
    }

    /** "Provider / Model", as the settings row shows it. */
    fun label(selection: EngineSelection): String = when (selection) {
        is EngineSelection.Cloud -> "${providerName(selection)} / ${modelName(selection)}"
        is EngineSelection.Local -> modelName(selection)
    }

    companion object {
        @Volatile
        private var instance: ModelInfo? = null

        fun get(context: Context): ModelInfo = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                ModelInfo(ProviderStore.get(app)) { ModelStore.get(app) }
            }.also { instance = it }
        }
    }
}
