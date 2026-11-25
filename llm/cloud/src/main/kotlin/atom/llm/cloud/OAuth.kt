package atom.llm.cloud

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Tokens from an account sign-in. Stored only in the encrypted vault, never in backups. */
@Serializable
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAtMillis: Long? = null,
    val accountId: String? = null,
    val email: String? = null,
    val plan: String? = null,
) {
    fun expiresWithin(nowMillis: Long, marginMillis: Long): Boolean =
        expiresAtMillis != null && expiresAtMillis - marginMillis <= nowMillis
}

class OAuthException(message: String, val kind: Kind) : Exception(message) {
    enum class Kind { Denied, Expired, Network, Server, NoPlan, Mismatch }
}

object Pkce {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun verifier(): String = randomToken(32)

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun randomToken(bytes: Int = 16): String = ByteArray(bytes).also(random::nextBytes).let(encoder::encodeToString)
}

/** Reads claims from a JWT payload without verifying it; used only to label the signed-in account. */
object Jwt {
    fun claims(token: String?): JsonObject? {
        val payload = token?.split('.')?.getOrNull(1) ?: return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')) }.getOrNull()
            ?: return null
        return parseJsonOrNull(String(bytes, Charsets.UTF_8)) as? JsonObject
    }

    fun expiryMillis(token: String?): Long? = (claims(token)?.get("exp") as? JsonPrimitive)?.content?.toLongOrNull()?.times(1000)
}

/**
 * "Sign in with ChatGPT": the browser-based PKCE flow the vendor's own Codex CLI uses, with a loopback
 * callback on port 1455. Values match OpenAI's published Codex source; the flow is not a supported public API.
 */
object ChatGptAuth {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val PORT = 1455
    const val REDIRECT_URI = "http://localhost:$PORT/auth/callback"
    const val SCOPE = "openid profile email offline_access"
    const val BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val ORIGINATOR = "codex_cli_rs"
    private const val AUTH_CLAIM = "https://api.openai.com/auth"

    fun authorizeUrl(challenge: String, state: String): String =
        "$ISSUER/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("state", state)
            .addQueryParameter("originator", ORIGINATOR)
            .build()
            .toString()

    fun tokenRequest(code: String, verifier: String): Request = Request.Builder()
        .url("$ISSUER/oauth/token")
        .post(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("client_id", CLIENT_ID)
                .add("code_verifier", verifier)
                .build(),
        )
        .build()

    fun refreshRequest(refreshToken: String): Request = Request.Builder()
        .url("$ISSUER/oauth/token")
        .post(
            buildJsonObject {
                put("client_id", CLIENT_ID)
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
                put("scope", "openid profile email")
            }.toRequestBody(),
        )
        .build()

    fun parseTokens(body: String, nowMillis: Long, previous: OAuthTokens? = null): OAuthTokens {
        val root = parseJsonOrNull(body) as? JsonObject ?: throw OAuthException("Unexpected sign-in response", OAuthException.Kind.Server)
        val access = root.str("access_token") ?: throw OAuthException("Sign-in returned no access token", OAuthException.Kind.Server)
        val id = root.str("id_token") ?: previous?.idToken
        val auth = Jwt.claims(id)?.obj(AUTH_CLAIM) ?: Jwt.claims(access)?.obj(AUTH_CLAIM)
        return OAuthTokens(
            accessToken = access,
            refreshToken = root.str("refresh_token") ?: previous?.refreshToken,
            idToken = id,
            expiresAtMillis = root.int("expires_in")?.let { nowMillis + it * 1000L } ?: Jwt.expiryMillis(access),
            accountId = auth?.str("chatgpt_account_id") ?: previous?.accountId,
            email = Jwt.claims(id)?.str("email") ?: previous?.email,
            plan = auth?.str("chatgpt_plan_type") ?: previous?.plan,
        )
    }

    /** Headers the account backend expects next to the bearer token. */
    fun accountHeaders(tokens: OAuthTokens): List<HeaderEntry> = buildList {
        tokens.accountId?.let { add(HeaderEntry("ChatGPT-Account-ID", it)) }
        add(HeaderEntry("OpenAI-Beta", "responses=experimental"))
        add(HeaderEntry("originator", ORIGINATOR))
    }

    fun modelsRequest(tokens: OAuthTokens): Request = Request.Builder()
        .url("$BASE_URL/models?client_version=1.0.0")
        .header("Authorization", "Bearer ${tokens.accessToken}")
        .apply { accountHeaders(tokens).forEach { header(it.name, it.value) } }
        .build()

    fun parseModels(body: String): List<RemoteModel> {
        val root = parseJsonOrNull(body) as? JsonObject ?: return emptyList()
        val rows = root.arr("models").ifEmpty { root.arr("data") }
        return rows.mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val id = row.str("slug") ?: row.str("id") ?: return@mapNotNull null
            if (row.bool("supported_in_api") == false && row.str("visibility") == "hide") return@mapNotNull null
            val levels = row.arr("supported_reasoning_levels").mapNotNull { level ->
                level.asObject()?.str("effort") ?: (level as? JsonPrimitive)?.content
            }
            val context = row.int("context_window")
            RemoteModel(
                id = id,
                displayName = row.str("display_name"),
                capabilities = DetectedCapabilities(
                    reasoning = if (levels.isNotEmpty()) ReasoningInfo(true, Reasoning.parseLevels(levels, toggle = false)) else null,
                    contextWindow = context,
                    source = CapabilitySource.Remote,
                ),
            )
        }
    }
}

/** RFC 8628 device authorization as published by xAI's issuer metadata and its official Grok CLI. */
object GrokAuth {
    const val ISSUER = "https://auth.x.ai"
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
    const val BASE_URL = "https://cli-chat-proxy.grok.com/v1"
    const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    const val DEFAULT_INTERVAL_SECONDS = 5
    const val SLOW_DOWN_SECONDS = 5

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
    )

    sealed interface Poll {
        data object Pending : Poll
        data object SlowDown : Poll
        data class Done(val tokens: OAuthTokens) : Poll
        data class Failed(val error: OAuthException) : Poll
    }

    fun deviceCodeRequest(): Request = Request.Builder()
        .url("$ISSUER/oauth2/device/code")
        .post(FormBody.Builder().add("client_id", CLIENT_ID).add("scope", SCOPE).add("referrer", "grok-build").build())
        .build()

    fun parseDeviceCode(body: String): DeviceCode {
        val root = parseJsonOrNull(body) as? JsonObject ?: throw OAuthException("Unexpected device code response", OAuthException.Kind.Server)
        return DeviceCode(
            deviceCode = root.str("device_code") ?: throw OAuthException("No device code returned", OAuthException.Kind.Server),
            userCode = root.str("user_code") ?: throw OAuthException("No user code returned", OAuthException.Kind.Server),
            verificationUri = root.str("verification_uri") ?: root.str("verification_url") ?: "$ISSUER/device",
            verificationUriComplete = root.str("verification_uri_complete"),
            expiresInSeconds = root.int("expires_in") ?: 600,
            intervalSeconds = (root.int("interval") ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1),
        )
    }

    fun pollRequest(deviceCode: String): Request = Request.Builder()
        .url("$ISSUER/oauth2/token")
        .post(FormBody.Builder().add("grant_type", DEVICE_GRANT).add("device_code", deviceCode).add("client_id", CLIENT_ID).build())
        .build()

    fun parsePoll(code: Int, body: String, nowMillis: Long): Poll {
        val root = parseJsonOrNull(body) as? JsonObject
        if (code in 200..299 && root?.str("access_token") != null) return Poll.Done(parseTokens(root, nowMillis, null))
        return when (val error = root?.str("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            "access_denied" -> Poll.Failed(OAuthException("Sign-in was denied", OAuthException.Kind.Denied))
            "expired_token" -> Poll.Failed(OAuthException("The code expired. Start sign-in again.", OAuthException.Kind.Expired))
            else -> Poll.Failed(
                OAuthException(root?.str("error_description") ?: error ?: "Sign-in failed (HTTP $code)", OAuthException.Kind.Server),
            )
        }
    }

    fun refreshRequest(refreshToken: String): Request = Request.Builder()
        .url("$ISSUER/oauth2/token")
        .post(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", CLIENT_ID).build())
        .build()

    /** RFC 7009 revocation at the issuer's advertised revocation endpoint. */
    fun revokeRequest(token: String, hint: String): Request = Request.Builder()
        .url("$ISSUER/oauth2/revoke")
        .post(FormBody.Builder().add("token", token).add("token_type_hint", hint).add("client_id", CLIENT_ID).build())
        .build()

    fun parseTokens(body: String, nowMillis: Long, previous: OAuthTokens?): OAuthTokens {
        val root = parseJsonOrNull(body) as? JsonObject ?: throw OAuthException("Unexpected sign-in response", OAuthException.Kind.Server)
        return parseTokens(root, nowMillis, previous)
    }

    private fun parseTokens(root: JsonObject, nowMillis: Long, previous: OAuthTokens?): OAuthTokens {
        val access = root.str("access_token") ?: throw OAuthException("Sign-in returned no access token", OAuthException.Kind.Server)
        val id = root.str("id_token") ?: previous?.idToken
        val claims = Jwt.claims(id)
        return OAuthTokens(
            accessToken = access,
            refreshToken = root.str("refresh_token") ?: previous?.refreshToken,
            idToken = id,
            expiresAtMillis = root.int("expires_in")?.let { nowMillis + it * 1000L } ?: Jwt.expiryMillis(access),
            accountId = claims?.str("sub") ?: previous?.accountId,
            email = claims?.str("email") ?: previous?.email,
            plan = previous?.plan,
        )
    }

    fun modelsRequest(tokens: OAuthTokens): Request = Request.Builder()
        .url("$BASE_URL/models")
        .header("Authorization", "Bearer ${tokens.accessToken}")
        .build()
}

/** Runs OAuth HTTP exchanges, returning status and body so callers can read OAuth error envelopes. */
object OAuthHttp {
    suspend fun execute(request: Request, http: OkHttpClient = CloudHttp.client): Pair<Int, String> =
        try {
            rawFetch(http, request)
        } catch (e: IOException) {
            throw OAuthException("Network error: ${e.message ?: e::class.simpleName}", OAuthException.Kind.Network)
        }

    fun classify(code: Int, body: String): OAuthException {
        val root = parseJsonOrNull(body) as? JsonObject
        val error = root?.str("error") ?: (root?.obj("error")?.str("code"))
        val detail = root?.str("error_description") ?: errorMessage(body) ?: "HTTP $code"
        return when {
            error == "invalid_grant" || code == 401 -> OAuthException("Session expired. Sign in again. ($detail)", OAuthException.Kind.Expired)
            code == 403 -> OAuthException("This account's plan does not include access. ($detail)", OAuthException.Kind.NoPlan)
            else -> OAuthException("Sign-in failed: $detail", OAuthException.Kind.Server)
        }
    }
}
