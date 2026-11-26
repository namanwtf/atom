package atom.core.legacy

/** The previous app's default provider and model, from its DataStore `atom_settings`. */
data class LegacySelection(
    val providerId: String? = null,
    /** A `provider_models.id`, not an API model name. */
    val modelRowId: String? = null,
    /** Last model row chosen per provider id. */
    val perProvider: Map<String, String> = emptyMap(),
) {
    /**
     * The model the old app would have used, by its own rule: the selected provider when it exists and is enabled,
     * else the first enabled provider by sort order; then the selected model when it belongs to that provider and is
     * enabled, else the provider's remembered model, else its enabled model with the lowest sort order.
     */
    fun resolve(providers: List<LegacyProviderRow>, models: List<LegacyModelRow>): LegacyModelRow? {
        val provider = providers.firstOrNull { it.id == providerId && it.enabled }
            ?: providers.filter { it.enabled }.minByOrNull { it.sortOrder }
            ?: return null
        val own = models.filter { it.providerId == provider.id && it.enabled }
        return own.firstOrNull { it.id == modelRowId }
            ?: perProvider[provider.id]?.let { remembered -> own.firstOrNull { it.id == remembered } }
            ?: own.minByOrNull { it.sortOrder }
    }

    companion object {
        /**
         * The Atom 4 (provider id, API model name) behind an old model row id, or null when the row is gone (a
         * deleted model), its provider was not imported ([providerIds] maps old to new ids), or the imported provider
         * does not have that model ([hasModel]). Null means "follow the default".
         */
        fun target(
            modelRowId: String?,
            models: List<LegacyModelRow>,
            providerIds: Map<String, String>,
            hasModel: (providerId: String, model: String) -> Boolean,
        ): Pair<String, String>? {
            val row = modelRowId?.let { id -> models.firstOrNull { it.id == id } } ?: return null
            val provider = providerIds[row.providerId] ?: return null
            val model = row.modelId.trim()
            return if (model.isNotEmpty() && hasModel(provider, model)) provider to model else null
        }

        const val KEY_PROVIDER = "selected_provider_id"
        const val KEY_MODEL = "selected_model_id"
        const val KEY_PER_PROVIDER = "selected_model_id_by_provider."

        fun from(prefs: Map<String, PreferencesProto.Value>): LegacySelection {
            fun str(key: String) = (prefs[key] as? PreferencesProto.Value.Str)?.value?.takeIf { it.isNotBlank() }
            val per = prefs.entries.mapNotNull { (key, value) ->
                if (!key.startsWith(KEY_PER_PROVIDER)) return@mapNotNull null
                val id = key.removePrefix(KEY_PER_PROVIDER).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val model = (value as? PreferencesProto.Value.Str)?.value?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                id to model
            }.toMap()
            return LegacySelection(str(KEY_PROVIDER), str(KEY_MODEL), per)
        }

        /** The selection in a preferences file, or an empty selection when the file is missing or unreadable. */
        fun fromFile(bytes: ByteArray?): LegacySelection =
            bytes?.let { runCatching { from(PreferencesProto.decode(it)) }.getOrNull() } ?: LegacySelection()
    }
}
