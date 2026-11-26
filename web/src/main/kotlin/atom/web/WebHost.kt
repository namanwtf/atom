package atom.web

import android.content.Context
import android.net.Uri

/** One configured model provider that has an API key, as the web and image backends may reuse it. */
data class ProviderCredential(
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    /** True for a Gemini provider that talks to Vertex AI. */
    val isVertex: Boolean = false,
    /** Vertex only; blank means express mode (API key, no project). */
    val vertexProject: String = "",
    val vertexLocation: String = "global",
)

/** Read access to the model providers' keys. Implemented by the app over its key vault. */
interface ProviderKeys {
    /** Enabled providers with a readable API key. Never logged. */
    suspend fun all(): List<ProviderCredential>
}

/** Encrypted storage for this module's own credentials (web and image overrides). */
interface SecretStore {
    suspend fun get(name: String): String?

    /** null removes the value. */
    suspend fun put(name: String, value: String?)

    /** True when a value is stored but can no longer be decrypted; the UI then asks the user to re-enter it. */
    suspend fun isUnreadable(name: String): Boolean = false
}

/** Where a privileged file operation runs. */
enum class PrivilegedScope { Root, Linux }

class ExecResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
    val text: String get() = stdout.toString(Charsets.UTF_8)
}

/**
 * Runs one POSIX shell command as root (`su`) or inside the Linux environment, so the file tools can reach
 * paths the app user cannot. [stdin] is written to the command's standard input and then closed.
 */
interface PrivilegedFiles {
    suspend fun available(scope: PrivilegedScope): Boolean

    suspend fun exec(scope: PrivilegedScope, command: String, stdin: ByteArray? = null, timeoutMs: Long = 30_000): ExecResult
}

/** Everything :web needs from the app. Every member except the first two has a working default. */
interface WebHost {
    val providerKeys: ProviderKeys
    val secrets: SecretStore

    val privilegedFiles: PrivilegedFiles? get() = null

    /** The conversation a run belongs to ([atom.core.tool.ToolContext.taskId] is the run id). */
    fun conversationOf(taskId: String): String? = null

    /** Attaches [uri] (a generated image) to the chat composer for a follow-up edit. */
    fun attachToComposer(context: Context, uri: Uri) {}

    /**
     * Brings one of the app's screens forward (a route such as "web/sites/preview/<id>"), when the app is in front.
     * Returns false when it could not (the app is in the background); callers then leave it to the chat card.
     */
    fun openScreen(route: String): Boolean = false
}

internal object NoSecrets : SecretStore {
    override suspend fun get(name: String): String? = null
    override suspend fun put(name: String, value: String?): Unit =
        throw IllegalStateException("Secure storage is not available")
}

internal object NoKeys : ProviderKeys {
    override suspend fun all(): List<ProviderCredential> = emptyList()
}

object Web {
    @Volatile
    private var current: WebHost? = null

    private val fallback = object : WebHost {
        override val providerKeys: ProviderKeys = NoKeys
        override val secrets: SecretStore = NoSecrets
    }

    val host: WebHost get() = current ?: fallback

    /** Call once from Application.onCreate. */
    fun install(host: WebHost) {
        current = host
    }
}

/** Always read through to the installed host, so objects created before [Web.install] still work. */
internal object HostKeys : ProviderKeys {
    override suspend fun all(): List<ProviderCredential> = Web.host.providerKeys.all()
}

internal object HostSecrets : SecretStore {
    override suspend fun get(name: String): String? = Web.host.secrets.get(name)
    override suspend fun put(name: String, value: String?) = Web.host.secrets.put(name, value)
    override suspend fun isUnreadable(name: String): Boolean = Web.host.secrets.isUnreadable(name)
}
