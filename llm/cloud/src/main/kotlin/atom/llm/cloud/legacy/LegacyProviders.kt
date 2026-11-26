package atom.llm.cloud.legacy

import atom.core.legacy.LegacyEffort
import atom.core.legacy.LegacyModelRow
import atom.core.legacy.LegacyProviderRow
import atom.core.llm.ReasoningEffort
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.CapabilitySource
import atom.llm.cloud.ChatGptAuth
import atom.llm.cloud.DetectedCapabilities
import atom.llm.cloud.Family
import atom.llm.cloud.GrokAuth
import atom.llm.cloud.HeaderEntry
import atom.llm.cloud.Jwt
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.OAuthTokens
import atom.llm.cloud.PresetCatalog
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.ProviderPreset
import atom.llm.cloud.ReasoningInfo
import atom.llm.cloud.ReasoningMode
import atom.llm.cloud.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Maps the previous app's provider rows (`model_providers`, `provider_models`) and account sign-ins onto Atom 4's
 * [ProviderConfig] and [OAuthTokens]. Pure: the app reads the rows and writes the results through its own stores.
 */
object LegacyProviders {
    /** Old built-in ids to Atom 4 preset ids. */
    val PRESET_IDS: Map<String, String> = mapOf(
        "builtin-openai" to "openai",
        "builtin-anthropic" to "anthropic",
        "builtin-dashscope" to "qwen",
        "builtin-deepseek" to "deepseek",
        "builtin-kimi" to "moonshot",
        "builtin-mimo" to "mimo",
        "builtin-minimax" to "minimax",
        "builtin-stepfun" to "stepfun",
        "builtin-siliconflow" to "siliconflow",
        "builtin-openrouter" to "openrouter",
        "builtin-xai" to "xai",
        "builtin-gemini" to "gemini",
    )

    const val VERTEX_DEFAULT_URL = "https://aiplatform.googleapis.com/v1beta1"

    /** Header names the old app never sent (it filtered them at send time); they are not imported. */
    private val DROPPED_HEADERS = setOf(
        "host", "content-length", "connection", "transfer-encoding", "authorization", "proxy-authorization",
        "x-api-key", "x-goog-api-key", "anthropic-version", "api-key", "cookie", "set-cookie",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The Atom 4 preset an old built-in row corresponds to; null for custom rows. */
    fun presetIdFor(row: LegacyProviderRow): String? = if (row.builtIn) PRESET_IDS[row.id] else null

    fun wireFor(row: LegacyProviderRow, preset: ProviderPreset? = presetIdFor(row)?.let(PresetCatalog::byId)): Wire {
        val wire = when {
            row.type == LegacyProviderRow.TYPE_ANTHROPIC -> Wire.Anthropic
            row.isGemini -> if (row.endpointMode.trim() == "vertex_ai") Wire.Vertex else Wire.Gemini
            row.isOAuth -> Wire.OpenAiResponses
            row.endpointMode.trim() == "responses" -> Wire.OpenAiResponses
            else -> Wire.OpenAiChat
        }
        return if (preset != null && wire !in preset.wires) preset.wire else wire
    }

    /** The account sign-in an OAuth row asks for; [AuthMethod.ApiKey] for everything else. */
    fun requestedAuth(row: LegacyProviderRow): AuthMethod = when {
        !row.isOAuth -> AuthMethod.ApiKey
        row.authMode == LegacyProviderRow.AUTH_CHATGPT -> AuthMethod.ChatGpt
        else -> AuthMethod.Grok
    }

    /** The vendor family, resolved in the old app's order: sign-in, Gemini, built-in id, base URL host, type. */
    fun familyFor(row: LegacyProviderRow, preset: ProviderPreset?): String {
        if (row.isOAuth) return if (row.authMode == LegacyProviderRow.AUTH_CHATGPT) Family.OPENAI else Family.XAI
        if (row.isGemini) return Family.GEMINI
        preset?.let { return it.family }
        val host = row.baseUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        val byHost = when {
            host == "api.openai.com" -> Family.OPENAI
            host == "api.x.ai" || host == "cli-chat-proxy.grok.com" -> Family.XAI
            host == "api.anthropic.com" -> Family.ANTHROPIC
            host == "api.deepseek.com" -> Family.DEEPSEEK
            host == "api.moonshot.cn" || host == "api.moonshot.ai" -> Family.MOONSHOT
            host == "api.xiaomimimo.com" -> Family.MIMO
            host == "api.minimax.io" || host == "api.minimaxi.com" -> Family.MINIMAX
            host == "api.stepfun.com" -> Family.STEPFUN
            host == "dashscope.aliyuncs.com" || host.endsWith(".maas.aliyuncs.com") -> Family.QWEN
            host == "api.siliconflow.cn" -> Family.SILICONFLOW
            host == "openrouter.ai" -> Family.OPENROUTER
            else -> null
        }
        return byHost ?: if (row.type == LegacyProviderRow.TYPE_ANTHROPIC) Family.ANTHROPIC else Family.fromBaseUrl(row.baseUrl)
    }

    /**
     * The Atom 4 provider for [row] and its [models] (all rows; only this provider's enabled ones are used, in sort
     * order). The id is the preset id for built-ins and blank for custom providers (the store derives one). Auth is
     * always [AuthMethod.ApiKey] here: the importer switches it to the account only once the sign-in is imported.
     * [seededPrompt] is the old default system prompt, replaced by Atom 4's; [rememberedModelRowId] becomes the
     * provider's default model.
     */
    fun toConfig(
        row: LegacyProviderRow,
        models: List<LegacyModelRow>,
        seededPrompt: String? = null,
        rememberedModelRowId: String? = null,
    ): ProviderConfig {
        val presetId = presetIdFor(row)
        val preset = presetId?.let(PresetCatalog::byId)
        val wire = wireFor(row, preset)
        val own = models.filter { it.providerId == row.id && it.enabled && it.modelId.isNotBlank() }.sortedBy { it.sortOrder }
        val imported = own.map { model(it, preset) }.distinctBy { it.id }
        val all = if (preset == null) imported else imported + preset.models.filter { p -> imported.none { it.id == p.id } }.map { it.toConfig() }
        val remembered = rememberedModelRowId?.let { id -> own.firstOrNull { it.id == id }?.modelId }
        val rawUrl = row.baseUrl.trim()
        val baseUrl = when {
            wire == Wire.Vertex -> rawUrl.takeIf { it.isNotEmpty() && !it.contains("googleapis.com") }.orEmpty()
            rawUrl.isEmpty() -> preset?.baseUrl ?: if (wire == Wire.Gemini) PresetCatalog.GEMINI_BASE_URL else ""
            else -> rawUrl
        }
        val (project, location) = vertexTarget(row)
        val prompt = row.systemPrompt?.trim().orEmpty()
        val systemPrompt = when {
            prompt.isEmpty() -> if (preset != null) PresetCatalog.DEFAULT_SYSTEM_PROMPT else ""
            prompt == seededPrompt -> PresetCatalog.DEFAULT_SYSTEM_PROMPT
            else -> row.systemPrompt.orEmpty()
        }
        val gemini = wire == Wire.Gemini || wire == Wire.Vertex
        return ProviderConfig(
            id = presetId.orEmpty(),
            displayName = row.name.trim().ifEmpty { preset?.displayName ?: "Provider" },
            wire = wire,
            baseUrl = baseUrl,
            enabled = row.enabled,
            presetId = presetId,
            family = familyFor(row, preset),
            systemPrompt = systemPrompt,
            headers = headers(row.customHeadersJson),
            extraBody = extraBody(row.customBodyJson),
            models = all,
            defaultModel = remembered?.takeIf { r -> all.any { it.id == r } } ?: all.firstOrNull()?.id,
            vertexProject = if (wire == Wire.Vertex) project else "",
            vertexLocation = if (wire == Wire.Vertex) location else "global",
            hostedWebSearch = row.hostedWebSearch && !gemini,
            googleSearch = row.hostedWebSearch && gemini,
            anthropicVersion = row.anthropicVersion.trim().takeIf { wire == Wire.Anthropic && it.isNotEmpty() && it != "2023-06-01" }.orEmpty(),
            auth = AuthMethod.ApiKey,
            keyHelpUrl = preset?.keyHelpUrl,
            supportsModelList = preset?.supportsModelList ?: (wire != Wire.Vertex),
        )
    }

    /**
     * Folds an imported built-in into the Atom 4 provider of the same preset. When the user already set that
     * provider up in Atom 4 ([userConfigured]: it has a key or a sign-in), only models it lacks are added; otherwise
     * the old settings replace the untouched preset, keeping the Atom 4 id and any models only Atom 4 has.
     */
    fun merge(existing: ProviderConfig, imported: ProviderConfig, userConfigured: Boolean): ProviderConfig {
        if (userConfigured) {
            val added = imported.models.filter { m -> existing.models.none { it.id == m.id } }
            return if (added.isEmpty()) existing else existing.copy(models = existing.models + added)
        }
        val models = imported.models + existing.models.filter { m -> imported.models.none { it.id == m.id } }
        return imported.copy(
            id = existing.id,
            presetId = existing.presetId ?: imported.presetId,
            models = models,
            defaultModel = imported.defaultModel?.takeIf { d -> models.any { it.id == d } } ?: existing.defaultModel,
            auth = existing.auth,
        )
    }

    /** One old model row as an Atom 4 model: catalog metadata as detected values, user overrides as overrides. */
    fun model(row: LegacyModelRow, preset: ProviderPreset? = null): ModelConfig {
        val source = when (row.source.trim().lowercase()) {
            "catalog" -> CapabilitySource.Catalog
            "remote" -> CapabilitySource.Remote
            else -> CapabilitySource.Manual
        }
        val caps = when (row.reasoning) {
            0 -> ReasoningInfo.None
            else -> capabilities(row.reasoningCapabilitiesJson)
        }
        val vision = when {
            row.attachment == 1 || "image" in modalities(row.inputModalitiesJson) -> true
            row.attachment == 0 -> false
            else -> null
        }
        val (mode, levels) = when (row.reasoningOverride) {
            0 -> ReasoningMode.Off to null
            1 -> ReasoningMode.On to capabilities(row.reasoningCapabilitiesOverrideJson)?.levels
            else -> ReasoningMode.Auto to null
        }
        // The old app often copied the advertised window into the override column; only a different value is the
        // user's own override. Without an advertised value the column is treated as detected metadata.
        val override = row.contextWindowOverride?.takeIf { it > 0 && row.contextWindow != null && it != row.contextWindow }
        val catalog = preset?.models?.firstOrNull { it.id == row.modelId }?.toConfig()
        if (catalog != null) {
            // Atom 4's own catalog entry, with what the user changed by hand.
            return catalog.copy(contextWindow = override, reasoningMode = mode, reasoningLevels = levels)
        }
        return ModelConfig(
            id = row.modelId.trim(),
            displayName = row.displayName.trim().ifEmpty { row.modelId.trim() },
            contextWindow = override,
            reasoningMode = mode,
            reasoningLevels = levels,
            detected = DetectedCapabilities(
                reasoning = caps,
                contextWindow = (row.contextWindow ?: row.contextWindowOverride)?.takeIf { it > 0 },
                vision = vision,
                source = source,
            ),
            fromAccount = false,
        )
    }

    /**
     * An old reasoning capabilities object (`supportedEfforts`, `defaultEffort`, `mandatory`, `canDisable`, ...) as
     * Atom 4 reasoning levels. The literal `null` (unknown) gives null so Atom 4's own detection fills it in.
     */
    fun capabilities(raw: String?): ReasoningInfo? {
        if (raw.isNullOrBlank() || raw.trim() == "null") return null
        val o = parse(raw) as? JsonObject ?: return null
        val efforts = (o["supportedEfforts"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .map(LegacyEffort::parse)
            .filter { it != LegacyEffort.Off && it != LegacyEffort.Default }
            .mapNotNull { it.effort }
        val mandatory = o.bool("mandatory") == true
        val canDisable = o.bool("canDisable") ?: false
        val levels = (efforts + listOfNotNull(ReasoningEffort.Off.takeIf { canDisable && !mandatory })).distinct().sorted()
        val default = o.str("defaultEffort")?.let(LegacyEffort::parse)?.takeIf { it != LegacyEffort.Default }?.effort
        return ReasoningInfo(supported = true, levels = levels, serverDefault = default)
    }

    fun headers(raw: String?): List<HeaderEntry> = (parse(raw) as? JsonArray).orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (name.lowercase() in DROPPED_HEADERS) return@mapNotNull null
        HeaderEntry(name, o.str("value").orEmpty())
    }

    /** The old `[{"key": ..., "value": ...}]` body fields as one JSON object; blank when there are none. */
    fun extraBody(raw: String?): String {
        val fields = (parse(raw) as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val key = o.str("key")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            key to (o["value"] ?: JsonNull)
        }
        return if (fields.isEmpty()) "" else JsonObject(fields.toMap()).toString()
    }

    /** Vertex project and location: the row's columns, else what a `/projects/<p>/locations/<l>` base URL names. */
    fun vertexTarget(row: LegacyProviderRow): Pair<String, String> {
        val match = VERTEX_PATH.find(row.baseUrl)
        val project = row.vertexProject.trim().ifEmpty { match?.groupValues?.get(1).orEmpty() }
        val location = row.vertexLocation.trim().ifEmpty { match?.groupValues?.get(2).orEmpty() }.ifEmpty { "global" }
        return project to location
    }

    private val VERTEX_PATH = Regex("/projects/([^/]+)/locations/([^/?#]+)")

    sealed interface SignIn {
        data class Usable(val tokens: OAuthTokens, val method: AuthMethod) : SignIn

        /** The file holds no usable tokens (signed out, expired and wiped, other client, wrong mode). */
        data class SignInAgain(val reason: String) : SignIn
    }

    /**
     * A decrypted old credential (`ProviderCredential` JSON) for OAuth [row], checked with the old app's own rules:
     * a subject and an access token, the same mode as the row, OpenAI's client id and an account id for ChatGPT,
     * xAI's client id and the `grok-cli:access` scope for Grok. Expired access tokens are kept when a refresh token
     * exists; Atom 4 refreshes them on first use.
     */
    fun signIn(plaintext: String, row: LegacyProviderRow, nowMillis: Long): SignIn {
        val o = parse(plaintext) as? JsonObject ?: return SignIn.SignInAgain("unreadable")
        val mode = o.str("authMode")
        if (mode != row.authMode || !row.isOAuth) return SignIn.SignInAgain("mode mismatch")
        val subject = o.str("subject")?.takeIf { it.isNotBlank() } ?: return SignIn.SignInAgain("signed out")
        val access = o.str("accessToken")?.takeIf { it.isNotBlank() } ?: return SignIn.SignInAgain("signed out")
        val refresh = o.str("refreshToken")?.takeIf { it.isNotBlank() }
        val idToken = o.str("idToken")?.takeIf { it.isNotBlank() }
        // 0 is the "unknown" default; the access token's own expiry claim is used then, so Atom 4 still refreshes.
        val expires = (o["expiresAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: Jwt.expiryMillis(access)
        if (expires != null && expires <= nowMillis && refresh == null) return SignIn.SignInAgain("expired")
        val email = o.str("email")?.takeIf { it.isNotBlank() }
        val clientId = o.str("clientId")
        return when (mode) {
            LegacyProviderRow.AUTH_CHATGPT -> {
                if (clientId != ChatGptAuth.CLIENT_ID) return SignIn.SignInAgain("other client")
                val account = o.str("accountId")?.takeIf { it.isNotBlank() } ?: return SignIn.SignInAgain("no account")
                val plan = (Jwt.claims(access)?.get(OPENAI_AUTH_CLAIM) as? JsonObject)?.str("chatgpt_plan_type")
                    ?: (Jwt.claims(idToken)?.get(OPENAI_AUTH_CLAIM) as? JsonObject)?.str("chatgpt_plan_type")
                SignIn.Usable(OAuthTokens(access, refresh, idToken, expires, account, email, plan), AuthMethod.ChatGpt)
            }
            else -> {
                if (clientId != GrokAuth.CLIENT_ID) return SignIn.SignInAgain("other client")
                val scopes = (o["scopes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                if (GROK_SCOPE !in scopes) return SignIn.SignInAgain("missing scope")
                SignIn.Usable(OAuthTokens(access, refresh, idToken, expires, subject, email, null), AuthMethod.Grok)
            }
        }
    }

    private const val OPENAI_AUTH_CLAIM = "https://api.openai.com/auth"
    private const val GROK_SCOPE = "grok-cli:access"

    private fun modalities(raw: String): List<String> =
        (parse(raw) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.lowercase() }

    private fun parse(raw: String?): JsonElement? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
