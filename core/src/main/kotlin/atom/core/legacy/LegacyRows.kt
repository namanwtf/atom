package atom.core.legacy

import atom.core.llm.ReasoningEffort
import java.security.MessageDigest

/**
 * Rows of the previous Atom database (`databases/atom.db`, schema version 30) as the importer reads them. Missing
 * columns take the defaults listed here, which are the old schema's own defaults. Chunked values are already
 * resolved. Nothing here writes to the old database.
 */
data class LegacyProviderRow(
    val id: String,
    val type: String = "openai_compatible",
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val sortOrder: Int = 0,
    val systemPrompt: String? = null,
    val customHeadersJson: String = "[]",
    val customBodyJson: String = "[]",
    val createdAt: Long = 0,
    val endpointMode: String = "",
    val hostedWebSearch: Boolean = false,
    val anthropicVersion: String = "2023-06-01",
    val authMode: String = AUTH_API_KEY,
    val vertexProject: String = "",
    val vertexLocation: String = "global",
) {
    val isGemini: Boolean get() = type == TYPE_GEMINI

    /** Signed in with a vendor account instead of an API key. Gemini rows always behave as API-key rows. */
    val isOAuth: Boolean get() = !isGemini && (authMode == AUTH_CHATGPT || authMode == AUTH_GROK)

    /** The plaintext key the row holds, when it is an API-key row with a key. */
    val usableApiKey: String? get() = apiKey.takeIf { !isOAuth && it.isNotBlank() }

    companion object {
        const val TYPE_OPENAI = "openai_compatible"
        const val TYPE_ANTHROPIC = "anthropic"
        const val TYPE_GEMINI = "gemini"
        const val TYPE_CUSTOM = "custom"
        const val AUTH_API_KEY = "api_key"
        const val AUTH_CHATGPT = "openai_chatgpt"
        const val AUTH_GROK = "xai_grok"
    }
}

data class LegacyModelRow(
    /** Local row id; conversations and the selection refer to this, not to [modelId]. */
    val id: String,
    val providerId: String,
    /** The API model name. */
    val modelId: String,
    val displayName: String = "",
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val sortOrder: Int = 0,
    val contextWindow: Int? = null,
    val contextWindowOverride: Int? = null,
    val inputModalitiesJson: String = "",
    val attachment: Int? = null,
    val reasoning: Int? = null,
    val reasoningCapabilitiesJson: String = "null",
    val reasoningOverride: Int? = null,
    val reasoningCapabilitiesOverrideJson: String = "null",
    /** `manual`, `remote` or `catalog` (case-insensitive). */
    val source: String = "manual",
)

data class LegacyConversationRow(
    val id: String,
    val title: String = "",
    val reasoningEffort: String = "default",
    /** Resolved `roleplay_json`; non-empty means a character chat. */
    val roleplayJson: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** `provider_models.id` last used, or null to follow the default. May name a deleted model. */
    val modelRowId: String? = null,
    val pinned: Boolean = false,
)

/** One visible timeline row (`conversation_messages`). */
data class LegacyMessageRow(
    val id: String,
    val sortIndex: Long,
    val type: String,
    val content: String = "",
    /** Local copies of this user row's images, already saved by the caller (opaque paths). */
    val imagePaths: List<String> = emptyList(),
    /** Images that could not be kept; each becomes an "[image]" note in the text. */
    val droppedImages: Int = 0,
    val edited: Boolean = false,
    val elapsedSeconds: Long? = null,
    val toolName: String? = null,
    val toolStatus: String? = null,
    val argumentsSummary: String? = null,
    val resultSummary: String? = null,
    /** True when [content] was a damaged chunk reference and is empty for that reason. */
    val damaged: Boolean = false,
) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val THINKING = "thinking"
        const val TOOL = "tool"
        const val TOOL_SUMMARY = "tool_summary"
        const val SYSTEM_NOTICE = "system_notice"
    }
}

/** The old app's reasoning effort strings, with the aliases it accepted on read. */
enum class LegacyEffort {
    Off, Default, Minimal, Low, Medium, High, XHigh, Max;

    /** The Atom 4 effort: null means "model default". There is no minimal level, so it becomes Low. */
    val effort: ReasoningEffort?
        get() = when (this) {
            Off -> ReasoningEffort.Off
            Default -> null
            Minimal, Low -> ReasoningEffort.Low
            Medium -> ReasoningEffort.Medium
            High -> ReasoningEffort.High
            XHigh -> ReasoningEffort.XHigh
            Max -> ReasoningEffort.Max
        }

    companion object {
        /** Unknown values read as [Default], as in the old app. */
        fun parse(raw: String?): LegacyEffort = when (raw?.trim()?.lowercase()) {
            "off", "none" -> Off
            "minimal" -> Minimal
            "low" -> Low
            "medium" -> Medium
            "high" -> High
            "xhigh", "x-high", "extra_high", "extra-high" -> XHigh
            "max" -> Max
            else -> Default
        }
    }
}

/** File names the old app used. */
object LegacyFiles {
    const val DATABASE = "atom.db"
    const val SETTINGS = "datastore/atom_settings.preferences_pb"
    const val CREDENTIALS_DIR = "provider_credentials"
    const val CREDENTIALS_ALIAS = "atom_provider_credentials_v1"

    /** The credential file of [providerId] under `no_backup/provider_credentials/`: lowercase hex SHA-256 + `.bin`. */
    fun credentialFileName(providerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(providerId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) } + ".bin"
    }
}
