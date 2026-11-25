package atom.app.engine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import atom.app.data.KeyVault
import atom.app.data.ProviderStore
import atom.app.data.vaultDataStore
import atom.llm.cloud.AuthMethod
import atom.llm.cloud.ChatGptAuth
import atom.llm.cloud.CloudEngines
import atom.llm.cloud.Family
import atom.llm.cloud.GrokAuth
import atom.llm.cloud.LoopbackCallback
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.OAuthException
import atom.llm.cloud.OAuthHttp
import atom.llm.cloud.OAuthTokens
import atom.llm.cloud.Pkce
import atom.llm.cloud.PresetCatalog
import atom.llm.cloud.Provider
import atom.llm.cloud.RemoteModel
import atom.llm.cloud.Wire
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

sealed interface AccountState {
    data object SignedOut : AccountState
    data object Working : AccountState
    data class AwaitingBrowser(val url: String) : AccountState
    data class DeviceCode(val userCode: String, val verificationUrl: String) : AccountState
    data class SignedIn(val email: String?, val plan: String?, val models: Int) : AccountState
    data class Failed(val message: String, val kind: OAuthException.Kind) : AccountState
}

/**
 * Account sign-in for providers that offer it: "Sign in with ChatGPT" (browser + loopback, PKCE) and xAI Grok
 * (device code). These reuse the vendors' own app logins, so they are marked risky in the UI. Work happens
 * only while a sign-in is in progress or a request needs a fresh token; nothing runs in the background.
 */
class SubscriptionAuth internal constructor(
    private val context: Context,
    private val vault: KeyVault,
    private val providers: ProviderStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val jobs = ConcurrentHashMap<String, Job>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _states = MutableStateFlow<Map<String, AccountState>>(emptyMap())

    val states: StateFlow<Map<String, AccountState>> = _states.asStateFlow()

    private fun set(providerId: String, state: AccountState) = _states.update { it + (providerId to state) }

    /** Loads the stored status for [providerId] (cheap; called when its screen opens). */
    fun refreshStatus(providerId: String) {
        if (jobs[providerId]?.isActive == true) return
        scope.launch {
            val tokens = tokens(providerId)
            val models = providers.get(providerId)?.models?.count { it.fromAccount } ?: 0
            set(providerId, tokens?.let { AccountState.SignedIn(it.email, it.plan, models) } ?: AccountState.SignedOut)
        }
    }

    fun signIn(providerId: String, method: AuthMethod, reauthorize: Boolean = false) {
        jobs.remove(providerId)?.cancel()
        jobs[providerId] = scope.launch {
            try {
                set(providerId, AccountState.Working)
                val previous = tokens(providerId)
                val fresh = when (method) {
                    AuthMethod.ChatGpt -> chatGpt(providerId)
                    AuthMethod.Grok -> grok(providerId)
                    AuthMethod.ApiKey -> return@launch
                }
                if (reauthorize && previous?.accountId != null && fresh.accountId != null && previous.accountId != fresh.accountId) {
                    throw OAuthException("That is a different account. Sign out first to switch accounts.", OAuthException.Kind.Mismatch)
                }
                saveTokens(providerId, fresh)
                providers.update(providerId) { it.copy(auth = method, enabled = true) }
                val count = syncModels(providerId, method, fresh)
                set(providerId, AccountState.SignedIn(fresh.email, fresh.plan, count))
            } catch (e: CancellationException) {
                set(providerId, AccountState.SignedOut)
                throw e
            } catch (e: OAuthException) {
                set(providerId, AccountState.Failed(e.message ?: "Sign-in failed", e.kind))
            } catch (e: Exception) {
                set(providerId, AccountState.Failed(e.message ?: e::class.simpleName.orEmpty(), OAuthException.Kind.Server))
            } finally {
                jobs.remove(providerId)
            }
        }
    }

    fun cancel(providerId: String) {
        jobs.remove(providerId)?.cancel()
        refreshStatus(providerId)
    }

    /** Forgets the tokens; Grok tokens are also revoked at xAI. The provider goes back to API-key auth. */
    fun signOut(providerId: String) {
        jobs.remove(providerId)?.cancel()
        scope.launch {
            val config = providers.get(providerId)
            val tokens = tokens(providerId)
            if (config?.auth == AuthMethod.Grok && tokens != null) {
                runCatching {
                    tokens.refreshToken?.let { OAuthHttp.execute(GrokAuth.revokeRequest(it, "refresh_token")) }
                    OAuthHttp.execute(GrokAuth.revokeRequest(tokens.accessToken, "access_token"))
                }
            }
            vault.setOauthTokens(providerId, null)
            providers.update(providerId) { c -> c.copy(auth = AuthMethod.ApiKey, models = c.models.filterNot { it.fromAccount && !it.builtIn }) }
            set(providerId, AccountState.SignedOut)
        }
    }

    fun syncModelsNow(providerId: String) {
        scope.launch {
            val config = providers.get(providerId) ?: return@launch
            try {
                val tokens = validTokens(providerId)
                val count = syncModels(providerId, config.auth, tokens)
                set(providerId, AccountState.SignedIn(tokens.email, tokens.plan, count))
            } catch (e: OAuthException) {
                set(providerId, AccountState.Failed(e.message ?: "", e.kind))
            }
        }
    }

    /** Tokens for a request, refreshed when they expire within five minutes. */
    suspend fun validTokens(providerId: String): OAuthTokens = lock(providerId).withLock {
        val config = providers.get(providerId)
        val current = tokens(providerId)
            ?: throw OAuthException(
                if (vault.isUnreadable(providerId)) "Your sign-in could not be decrypted on this phone. Sign in again." else "Not signed in.",
                OAuthException.Kind.Expired,
            )
        if (!current.expiresWithin(System.currentTimeMillis(), REFRESH_MARGIN_MS)) return@withLock current
        val refresh = current.refreshToken ?: throw OAuthException("Session expired. Sign in again.", OAuthException.Kind.Expired)
        val request = if (config?.auth == AuthMethod.Grok) GrokAuth.refreshRequest(refresh) else ChatGptAuth.refreshRequest(refresh)
        val (code, body) = OAuthHttp.execute(request)
        if (code !in 200..299) {
            val error = OAuthHttp.classify(code, body)
            if (error.kind == OAuthException.Kind.Expired) {
                vault.setOauthTokens(providerId, null)
                set(providerId, AccountState.Failed(error.message ?: "", error.kind))
            }
            throw error
        }
        val now = System.currentTimeMillis()
        val next = if (config?.auth == AuthMethod.Grok) GrokAuth.parseTokens(body, now, current) else ChatGptAuth.parseTokens(body, now, current)
        saveTokens(providerId, next)
        next
    }

    suspend fun tokens(providerId: String): OAuthTokens? =
        vault.oauthTokens(providerId)?.let { runCatching { json.decodeFromString(OAuthTokens.serializer(), it) }.getOrNull() }

    private suspend fun saveTokens(providerId: String, tokens: OAuthTokens) =
        vault.setOauthTokens(providerId, json.encodeToString(OAuthTokens.serializer(), tokens))

    private suspend fun chatGpt(providerId: String): OAuthTokens {
        val verifier = Pkce.verifier()
        val state = Pkce.randomToken()
        val callback = LoopbackCallback(ChatGptAuth.PORT).bind()
        try {
            val url = ChatGptAuth.authorizeUrl(Pkce.challenge(verifier), state)
            set(providerId, AccountState.AwaitingBrowser(url))
            openBrowser(url)
            val result = callback.await(LOGIN_TIMEOUT_MS)
            if (result.error != null) {
                val kind = if (result.error == "access_denied") OAuthException.Kind.Denied else OAuthException.Kind.Server
                throw OAuthException(result.errorDescription ?: "Sign-in was cancelled (${result.error})", kind)
            }
            if (result.state != state) throw OAuthException("The sign-in response did not match this request. Try again.", OAuthException.Kind.Server)
            val code = result.code ?: throw OAuthException("No authorization code was returned", OAuthException.Kind.Server)
            set(providerId, AccountState.Working)
            val (status, body) = OAuthHttp.execute(ChatGptAuth.tokenRequest(code, verifier))
            if (status !in 200..299) throw OAuthHttp.classify(status, body)
            return ChatGptAuth.parseTokens(body, System.currentTimeMillis())
        } finally {
            callback.close()
        }
    }

    private suspend fun grok(providerId: String): OAuthTokens {
        val (status, body) = OAuthHttp.execute(GrokAuth.deviceCodeRequest())
        if (status !in 200..299) throw OAuthHttp.classify(status, body)
        val device = GrokAuth.parseDeviceCode(body)
        val url = device.verificationUriComplete ?: device.verificationUri
        set(providerId, AccountState.DeviceCode(device.userCode, url))
        openBrowser(url)
        val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
        var interval = device.intervalSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval)
            val (code, reply) = OAuthHttp.execute(GrokAuth.pollRequest(device.deviceCode))
            when (val poll = GrokAuth.parsePoll(code, reply, System.currentTimeMillis())) {
                GrokAuth.Poll.Pending -> Unit
                GrokAuth.Poll.SlowDown -> interval += GrokAuth.SLOW_DOWN_SECONDS * 1000L
                is GrokAuth.Poll.Done -> return poll.tokens
                is GrokAuth.Poll.Failed -> throw poll.error
            }
        }
        throw OAuthException("The code expired. Start sign-in again.", OAuthException.Kind.Expired)
    }

    /** Replaces the account-synced models, keeping the user's own. Returns the synced count. */
    private suspend fun syncModels(providerId: String, method: AuthMethod, tokens: OAuthTokens): Int {
        val remote: List<RemoteModel> = when (method) {
            AuthMethod.ChatGpt -> {
                val (code, body) = OAuthHttp.execute(ChatGptAuth.modelsRequest(tokens))
                if (code == 403) throw OAuthHttp.classify(code, body)
                ChatGptAuth.parseModels(body).takeIf { code in 200..299 }.orEmpty().ifEmpty {
                    PresetCatalog.byId("openai")!!.models.map { RemoteModel(it.id, it.displayName, it.toConfig().detected) }
                }
            }
            AuthMethod.Grok -> try {
                val proxy = Provider("grok", "Grok", Wire.OpenAiChat, GrokAuth.BASE_URL, "", null, true, Family.XAI)
                CloudEngines.listRemoteModels(proxy, tokens.accessToken)
            } catch (e: atom.core.llm.LlmException) {
                if (e.statusCode == 403) throw OAuthException("This account's plan does not include Grok API access.", OAuthException.Kind.NoPlan)
                throw OAuthException(e.message ?: "Could not load models", OAuthException.Kind.Server)
            }
            AuthMethod.ApiKey -> emptyList()
        }
        if (remote.isEmpty()) return 0
        providers.update(providerId) { config ->
            val own = config.models.filterNot { it.fromAccount }
            val synced = remote.filter { r -> own.none { it.id == r.id } }.map {
                ModelConfig(id = it.id, displayName = it.displayName ?: it.id, detected = it.capabilities, fromAccount = true)
            }
            val models = own + synced
            config.copy(models = models, defaultModel = config.defaultModel?.takeIf { d -> models.any { it.id == d } } ?: models.firstOrNull()?.id)
        }
        return remote.size
    }

    fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The URL stays on screen so the user can open it elsewhere.
        }
    }

    private fun lock(providerId: String) = locks.getOrPut(providerId) { Mutex() }

    companion object {
        private const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L
        private const val REFRESH_MARGIN_MS = 5 * 60 * 1000L

        const val CHATGPT_USAGE_URL = "https://chatgpt.com/codex/settings/usage"
        const val GROK_USAGE_URL = "https://accounts.x.ai"

        @Volatile
        private var instance: SubscriptionAuth? = null

        fun get(context: Context): SubscriptionAuth = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                SubscriptionAuth(app, KeyVault(app.vaultDataStore), ProviderStore.get(app))
            }.also { instance = it }
        }
    }
}
