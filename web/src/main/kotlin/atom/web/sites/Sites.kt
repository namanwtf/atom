package atom.web.sites

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** The chat card of a site preview: [site_preview]'s result ends with a `Site preview: <id>` line. */
object SiteCards {
    const val MARKER = "Site preview: "

    fun siteOf(toolText: String): String? =
        toolText.lineSequence().lastOrNull { it.startsWith(MARKER) }?.removePrefix(MARKER)?.trim()?.takeIf { SitePaths.isId(it) }

    fun previewRoute(id: String) = "web/sites/preview/$id"
}

/**
 * The app's website projects (`files/sites/<id>/`), the address each is shown at and which previews are open.
 *
 * Nothing runs for a site: Atom's WebViews answer its address themselves from storage ([SiteWeb]), so there is no
 * server to start, keep alive or stop, and no port other apps could reach.
 */
object Sites {
    private const val PREFS = "atom4_sites"

    @Volatile
    private var store: SiteStore? = null
    private val shownCount = HashMap<String, Int>()
    private val _changes = MutableStateFlow(0L)
    private val _visible = MutableStateFlow<Set<String>>(emptySet())
    private val _versions = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Bumped whenever a site's files change, for lists to refresh. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Sites whose preview screen is showing. */
    val visible: StateFlow<Set<String>> = _visible.asStateFlow()

    /** A counter per site, bumped when its files change: an open preview reloads. */
    val versions: StateFlow<Map<String, Long>> = _versions.asStateFlow()

    fun store(context: Context): SiteStore = store ?: synchronized(this) {
        store ?: SiteStore(File(context.applicationContext.filesDir, "sites").apply { mkdirs() }).also { store = it }
    }

    /** The address of site [id] ("https://<id>-<tag>.invalid/"). Reads (and the first time writes) a small file. */
    fun url(context: Context, id: String): String {
        val s = store(context)
        if (!s.exists(id)) throw IllegalArgumentException("There is no site $id")
        return SiteOrigin.url(id, s.originTag(id))
    }

    /** A preview screen shows [id] until [hidden]. */
    fun shown(id: String) = synchronized(this) {
        shownCount[id] = (shownCount[id] ?: 0) + 1
        _visible.value = _visible.value + id
    }

    fun hidden(id: String) = synchronized(this) {
        val n = (shownCount[id] ?: 0) - 1
        if (n <= 0) {
            shownCount.remove(id)
            _visible.value = _visible.value - id
        } else {
            shownCount[id] = n
        }
    }

    /** Files of [id] changed: open previews reload, lists refresh. */
    fun changed(id: String) {
        _versions.update { it + (id to ((it[id] ?: 0L) + 1)) }
        _changes.value = _changes.value + 1
    }

    fun delete(context: Context, id: String): Boolean {
        val s = store(context)
        val origin = s.existingOriginTag(id)?.let { SiteOrigin.origin(id, it) }
        val paths = runCatching { SiteOrigin.cookiePaths(s.files(id).map { it.path }) }.getOrDefault(listOf("/"))
        val ok = s.delete(id)
        origin?.let { SiteWeb.forget(it, paths) }
        val p = prefs(context)
        val editor = p.edit()
        p.all.filter { it.value == id }.keys.forEach { editor.remove(it) }
        editor.apply()
        changed(id)
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
