package atom.vdisplay.system

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import atom.core.tool.Tool
import atom.device.switches.ToolSwitch
import atom.device.switches.gated
import atom.overlay.Overlay
import atom.ui.components.ScreenIcons
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection
import atom.vdisplay.R
import atom.vdisplay.route.DisplayRouter
import atom.vdisplay.tools.BackgroundTools
import atom.vdisplay.viewfinder.BackgroundViewfinder
import atom.xposed.ModuleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * System enhancements: the background virtual display (hidden-screen runs, live viewfinder, take over, present to
 * main screen) and the LSPosed options (power key, default assistant, Circle to Search, accessibility protection).
 *
 * Idle cost: none. No display, thread, listener or root shell exists until a run opens an app in the background.
 */
object SystemFeature : AtomFeature {
    override val id = "vdisplay"
    const val ROUTE = "vdisplay/system"
    private val seenWatcher = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onAppCreate(context: Context) {
        DisplayRouter.init(context)
        ModuleStatus.init()
        val prefs = SystemPrefs.get(context)
        ModuleStatus.push(prefs.hooks.value)
        // Event-driven: remember the first connection so the framework rows stay visible (greyed) when it is away.
        if (!prefs.frameworkSeen.value) {
            seenWatcher.launch {
                ModuleStatus.state.first { it is ModuleStatus.Connection.Connected }
                prefs.markFrameworkSeen()
            }
        }
        Overlay.viewfinder = BackgroundViewfinder
    }

    override fun tools(context: Context): List<Tool> {
        if (!backgroundUsable(context)) return emptyList()
        return BackgroundTools(context).all().map { it.gated(ToolSwitch.DeviceDirect) }
    }

    override fun promptHint(context: Context): String? {
        if (!backgroundUsable(context)) return null
        return BackgroundUse.PROMPT_HINT
    }

    /**
     * The background display only acts with root: without it the tools and the prompt hint are left out. Read at
     * each run's start (tools and hint are gathered per run), from the shared root state, so a grant made after
     * start-up counts from the next run.
     */
    private fun backgroundUsable(context: Context): Boolean =
        BackgroundUse.usable(SystemPrefs.get(context).backgroundDisplay.value, atom.device.root.RootAccess.granted)

    override val entries: List<FeatureEntry> = listOf(
        FeatureEntry(FeatureSection.System, R.string.vd_system_title, R.string.vd_system_sub, ScreenIcons.Tune, ROUTE),
    )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        when (route) {
            ROUTE -> SystemScreen(nav)
            else -> LaunchedEffect(route) { nav.back() }
        }
    }
}
