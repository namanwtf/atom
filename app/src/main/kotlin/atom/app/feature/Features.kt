package atom.app.feature

import atom.characters.CharactersFeature
import atom.context.feature.ContextFeature
import atom.device.feature.DeviceFeature
import atom.extend.ExtendFeature
import atom.general.GeneralFeature
import atom.linux.ui.LinuxFeature
import atom.ui.feature.AtomFeature
import atom.vdisplay.system.SystemFeature

/**
 * Every feature module, listed once. Tools, settings rows, screens and startup hooks are all wired from here.
 * Voice, overlay, automation and web join once their app-side install hooks are wired.
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
    )

    fun forRoute(route: String): AtomFeature? = all.firstOrNull { it.ownsRoute(route) }
}
