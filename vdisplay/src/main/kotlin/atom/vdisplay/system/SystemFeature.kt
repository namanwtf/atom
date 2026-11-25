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

/**
 * System enhancements: the background virtual display (hidden-screen runs, live viewfinder, take over, present to
 * main screen) and the LSPosed options (power key, default assistant, Circle to Search, accessibility protection).
 *
 * Idle cost: none. No display, thread, listener or root shell exists until a run opens an app in the background.
 */
object SystemFeature : AtomFeature {
    override val id = "vdisplay"
    const val ROUTE = "vdisplay/system"

    override fun onAppCreate(context: Context) {
        DisplayRouter.init(context)
        ModuleStatus.init()
        ModuleStatus.push(SystemPrefs.get(context).hooks.value)
        Overlay.viewfinder = BackgroundViewfinder
    }

    override fun tools(context: Context): List<Tool> {
        if (!SystemPrefs.get(context).backgroundDisplay.value) return emptyList()
        return BackgroundTools(context).all().map { it.gated(ToolSwitch.DeviceDirect) }
    }

    override fun promptHint(context: Context): String? {
        if (!SystemPrefs.get(context).backgroundDisplay.value) return null
        return "# Background display\nFor a task in another app that the user is not looking at, prefer " +
            "background_open_app: it runs on a hidden screen so the user keeps using the phone. Work there with " +
            "background_look / background_act. At a payment confirmation, a QR code or the final result, call " +
            "present_to_main_screen. \"Display unavailable\" means start again with background_open_app; never redo " +
            "those coordinates with the normal screen tools."
    }

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
