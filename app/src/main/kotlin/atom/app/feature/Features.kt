package atom.app.feature

import atom.ui.feature.AtomFeature

/** Every feature module, listed once. Tools, settings rows, screens and startup hooks are all wired from here. */
object Features {
    val all: List<AtomFeature> = listOf()

    fun forRoute(route: String): AtomFeature? = all.firstOrNull { it.ownsRoute(route) }
}
