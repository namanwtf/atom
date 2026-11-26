package atom.ui.feature

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import atom.core.tool.Tool

/** Where a feature's entry appears in Settings. */
enum class FeatureSection { Models, Context, Tools, System, General }

/**
 * A row in Settings that opens one of the feature's screens. [route] must be unique across features; use
 * "<featureId>/<screen>".
 */
data class FeatureEntry(
    val section: FeatureSection,
    @StringRes val title: Int,
    @StringRes val subtitle: Int? = null,
    val icon: ImageVector? = null,
    val route: String,
)

/** Navigation a feature screen can ask the app for. */
interface FeatureNav {
    fun back()
    fun open(route: String)
    fun openChat(conversationId: String? = null)
    fun openModels()
}

/**
 * One self-contained capability (bubble, voice, automations, ...), living in its own module. The app lists
 * all features once and wires tools, settings rows and screens from them; features never edit app code.
 *
 * Battery rule for every feature: nothing runs while idle unless the user switched the feature on, and then
 * only event-driven work (no polling loops).
 */
interface AtomFeature {
    val id: String

    /** Called from Application.onCreate. Keep it cheap: register lazily, start nothing heavy. */
    fun onAppCreate(context: Context) {}

    /** Tools the agent may use. Re-evaluated at the start of every run, so switches take effect immediately. */
    fun tools(context: Context): List<Tool> = emptyList()

    /** Extra text appended to the agent's system prompt (short; it is sent on every request). */
    fun promptHint(context: Context): String? = null

    /**
     * Guidance for tools the run offers on demand, keyed by tool name: sent once, with the first load of a group
     * holding that tool, instead of in every system prompt.
     */
    fun toolNotes(context: Context): Map<String, String> = emptyMap()

    val entries: List<FeatureEntry> get() = emptyList()

    /** Renders [route] (one of this feature's [entries] or a nested route it opened). */
    @Composable
    fun Screen(route: String, nav: FeatureNav) {}

    fun ownsRoute(route: String): Boolean = route.startsWith("$id/")
}
