package atom.web.sites

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The chat card of a site preview: [site_preview]'s result ends with a `Site preview: <id>` line. */
object SiteCards {
    const val MARKER = "Site preview: "

    fun siteOf(toolText: String): String? =
        toolText.lineSequence().lastOrNull { it.startsWith(MARKER) }?.removePrefix(MARKER)?.trim()?.takeIf { SitePaths.isId(it) }

    fun previewRoute(id: String) = "web/sites/preview/$id"
}

/**
 * The app's website projects (`files/sites/<id>/`) and their preview servers.
 *
 * A server starts on demand (the preview screen, or the agent's site_preview) and stops on its own: when the last
 * preview screen goes away and nothing asked for a page for a minute (see [SiteServer]). Each site keeps the port it
 * had while the app runs, so a reopened preview and the agent's browser see the same address.
 */
object Sites {
    private const val PREFS = "atom4_sites"

    @Volatile
    private var store: SiteStore? = null
    private val servers = HashMap<String, SiteServer>()
    private val ports = HashMap<String, Int>()
    private val _changes = MutableStateFlow(0L)
    private val _visible = MutableStateFlow<Set<String>>(emptySet())

    /** Bumped whenever a site's files change, for lists to refresh. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Sites whose preview screen is showing. */
    val visible: StateFlow<Set<String>> = _visible.asStateFlow()

    fun store(context: Context): SiteStore = store ?: synchronized(this) {
        store ?: SiteStore(File(context.applicationContext.filesDir, "sites").apply { mkdirs() }).also { store = it }
    }

    /** The running server of [id], started if needed. Call off the main thread (it binds a socket). */
    fun serve(context: Context, id: String): SiteServer = synchronized(this) {
        servers[id]?.takeIf { it.isRunning }?.let { return it }
        val s = store(context)
        if (!s.exists(id)) throw IllegalArgumentException("There is no site $id")
        val server = SiteServer(s.dir(id), preferredPort = ports[id] ?: 0, onStopped = { stopped -> forget(id, stopped) })
        server.start()
        servers[id] = server
        ports[id] = server.port
        server
    }

    private fun forget(id: String, server: SiteServer) = synchronized(this) {
        if (servers[id] === server) servers.remove(id)
    }

    /** The running server of [id], if any (nothing is started). */
    fun running(id: String): SiteServer? = synchronized(this) { servers[id]?.takeIf { it.isRunning } }

    /** A preview screen shows [id]: its server runs until [release]. */
    fun acquire(context: Context, id: String): SiteServer {
        val server = serve(context, id)
        server.acquire()
        _visible.value = _visible.value + id
        return server
    }

    fun release(id: String) {
        val server = running(id)
        if (server == null || server.holders <= 1) _visible.value = _visible.value - id
        server?.release()
    }

    /** Files of [id] changed: open pages reload, lists refresh. */
    fun changed(id: String) {
        running(id)?.changed()
        _changes.value = _changes.value + 1
    }

    fun stop(id: String) {
        running(id)?.stop()
    }

    fun delete(context: Context, id: String): Boolean {
        stop(id)
        val ok = store(context).delete(id)
        val p = prefs(context)
        val editor = p.edit()
        p.all.filter { it.value == id }.keys.forEach { editor.remove(it) }
        editor.apply()
        _changes.value = _changes.value + 1
        return ok
    }

    /** The site a conversation last worked on, so the agent need not repeat it. */
    fun current(context: Context, conversation: String): String? =
        prefs(context).getString(conversation, null)?.takeIf { store(context).exists(it) }

    fun setCurrent(context: Context, conversation: String, id: String) {
        prefs(context).edit().putString(conversation, id).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
