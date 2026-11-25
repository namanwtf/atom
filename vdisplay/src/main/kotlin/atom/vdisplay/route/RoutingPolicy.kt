package atom.vdisplay.route

/** Why a run works on the main screen or on the hidden display. */
enum class RouteReason(val text: String) {
    Hidden("working on a hidden background display, so your screen stays yours"),
    Disabled("Background virtual display is off in Settings → System enhancements"),
    NoRoot("the background display needs root, which is not granted"),
    Unsupported("this Android version cannot host the background display"),
    NoTarget("no target app was named"),
    TargetInFront("the app is already on screen"),
    LauncherOrSystemUi("the home screen and system UI live on the main screen"),
    Self("Atom itself runs on the main screen"),
}

data class RouteInput(
    val enabled: Boolean,
    val rootAvailable: Boolean,
    val supported: Boolean,
    val targetPackage: String?,
    /** Package in front on the main display, if known. */
    val foregroundPackage: String?,
    val selfPackage: String,
    val launcherPackages: Set<String>,
    val systemUiPackages: Set<String> = DEFAULT_SYSTEM_UI,
) {
    companion object {
        val DEFAULT_SYSTEM_UI = setOf("com.android.systemui", "android")
    }
}

data class RouteDecision(val hidden: Boolean, val reason: RouteReason)

/**
 * Where a cross-app task should run. Hidden display only when everything allows it and it actually helps: the app
 * already in front is driven where the user sees it, and the launcher / system UI only exist on the main screen.
 */
object RoutingPolicy {
    fun decide(i: RouteInput): RouteDecision {
        val target = i.targetPackage?.trim()?.takeIf { it.isNotEmpty() }
        fun main(r: RouteReason) = RouteDecision(hidden = false, reason = r)
        return when {
            !i.enabled -> main(RouteReason.Disabled)
            !i.supported -> main(RouteReason.Unsupported)
            target == null -> main(RouteReason.NoTarget)
            target == i.selfPackage -> main(RouteReason.Self)
            target in i.launcherPackages || target in i.systemUiPackages -> main(RouteReason.LauncherOrSystemUi)
            target == i.foregroundPackage -> main(RouteReason.TargetInFront)
            !i.rootAvailable -> main(RouteReason.NoRoot)
            else -> RouteDecision(hidden = true, reason = RouteReason.Hidden)
        }
    }
}
