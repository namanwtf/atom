package atom.overlay

import android.content.Context
import androidx.compose.runtime.Composable
import atom.overlay.settings.OverlaySettingsScreen
import atom.overlay.ui.OverlayIcons
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection

/** Floating bubble, mini chat, run capsule, edge glow and the system-assistant overlay. */
object OverlayFeature : AtomFeature {
    const val ROUTE_SETTINGS = "overlay/settings"

    override val id: String = "overlay"

    /** Reads a few preferences and installs the assistant UI factory; no window, service or timer starts unless the bubble is on. */
    override fun onAppCreate(context: Context) {
        OverlayRuntime.init(context)
    }

    override val entries: List<FeatureEntry>
        get() = listOf(
            FeatureEntry(
                section = FeatureSection.General,
                title = R.string.overlay_entry_title,
                subtitle = R.string.overlay_entry_subtitle,
                icon = OverlayIcons.Bubble,
                route = ROUTE_SETTINGS,
            ),
        )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        if (route == ROUTE_SETTINGS) OverlaySettingsScreen(nav)
    }
}
