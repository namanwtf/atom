package atom.app.feature

import atom.automation.AutomationFeature
import atom.characters.CharactersFeature
import atom.context.feature.ContextFeature
import atom.device.feature.DeviceFeature
import atom.extend.ExtendFeature
import atom.general.GeneralFeature
import atom.linux.ui.LinuxFeature
import atom.overlay.OverlayFeature
import atom.ui.feature.AtomFeature
import atom.vdisplay.system.SystemFeature
import atom.voice.ui.VoiceFeature
import atom.web.WebFeature

/**
 * Every feature module, listed once. Tools, settings rows, screens and startup hooks are all wired from here.
 * Their app-side install / bind calls live in [atom.app.integration.Integration] and run first.
 */
object Features {
    val all: List<AtomFeature> = listOf(
        DeviceFeature,
        ContextFeature,
        ExtendFeature,
        CharactersFeature,
        LinuxFeature,
        SystemFeature,
        GeneralFeature,
        VoiceFeature,
        OverlayFeature,
        AutomationFeature(),
        WebFeature,
    )

    fun forRoute(route: String): AtomFeature? = all.firstOrNull { it.ownsRoute(route) }
}
