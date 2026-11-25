package atom.xposed

/** Pure helpers for the colon-separated `enabled_accessibility_services` secure setting. */
object AccessibilityList {
    /** Normalizes "pkg/.Cls" and "pkg/pkg.Cls" to "pkg/pkg.Cls". */
    fun normalize(component: String): String? {
        val slash = component.indexOf('/')
        if (slash <= 0 || slash == component.length - 1) return null
        val pkg = component.substring(0, slash).trim()
        var cls = component.substring(slash + 1).trim()
        if (cls.startsWith(".")) cls = pkg + cls
        if (pkg.isEmpty() || cls.isEmpty()) return null
        return "$pkg/$cls"
    }

    fun contains(setting: String?, component: String): Boolean {
        val want = normalize(component) ?: return false
        return entries(setting).any { normalize(it) == want }
    }

    /** [setting] with [component] appended (unchanged if already present). */
    fun withComponent(setting: String?, component: String): String {
        if (contains(setting, component)) return setting.orEmpty()
        val list = entries(setting)
        return (list + component).joinToString(":")
    }

    private fun entries(setting: String?): List<String> =
        setting.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
}
