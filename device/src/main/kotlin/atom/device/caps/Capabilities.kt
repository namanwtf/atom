package atom.device.caps

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import atom.device.Accessibility
import atom.device.godmode.LocationSpoof
import atom.device.root.RootAccess
import atom.device.root.RootStatus

/** What a capability row shows. Only [Available] counts as fine. */
enum class CapStatus(val label: String) {
    Available("Available"),
    Missing("Missing"),
    Issue("Issue"),
    NotEnabled("Not enabled"),
    Checking("Checking…"),
    Unsupported("Not on this phone"),
}

data class CapState(val status: CapStatus, val detail: String? = null)

enum class CapGroup(val title: String) {
    Screen("Screen and overlay"),
    Personal("Personal context"),
    System("System access"),
    Background("Background and timing"),
    Advanced("Root and advanced"),
}

/** How the row's button gets the capability. Building or showing a row never triggers any of these. */
sealed interface CapAction {
    val label: String

    /** Runtime permissions, requested together by the screen. */
    data class Request(val permissions: List<String>, override val label: String = "Grant") : CapAction

    /** A system settings page the user switches it on in. */
    data class OpenSettings(val intent: (Context) -> Intent, override val label: String = "Go to settings") : CapAction
}

/**
 * One row on the Permissions & status page. [state] must be cheap and must never prompt. Feature modules add their
 * own rows with [Capabilities.register] from onAppCreate.
 */
abstract class Capability(
    val id: String,
    val title: String,
    val purpose: String,
    val requirement: String,
    val group: CapGroup,
    val optional: Boolean = false,
) {
    abstract fun state(context: Context): CapState

    /** Whether the row belongs in the "Current device" view (e.g. the Android version supports it). */
    open fun applicable(context: Context): Boolean = true

    abstract fun action(context: Context): CapAction?
}

/** A set of runtime permissions; [partial] permissions count as an Issue (granted only in part). */
open class RuntimeCap(
    id: String,
    title: String,
    purpose: String,
    group: CapGroup,
    private val permissions: List<String>,
    private val minSdk: Int = 0,
    optional: Boolean = false,
    private val partial: List<String> = emptyList(),
) : Capability(id, title, purpose, "Permission: " + permissions.joinToString { it.substringAfterLast('.') }, group, optional) {
    /** On this phone: new enough, and at least one of the permissions exists on this Android release. */
    override fun applicable(context: Context) = Build.VERSION.SDK_INT >= minSdk && permissions.any { Perms.exists(context, it) }

    fun wanted(context: Context): List<String> = permissions.filter { Perms.declared(context, it) && Perms.exists(context, it) }

    override fun state(context: Context): CapState {
        if (Build.VERSION.SDK_INT < minSdk) return CapState(CapStatus.Unsupported, "Needs Android API $minSdk+")
        if (!applicable(context)) return CapState(CapStatus.Unsupported, "Not on this Android version")
        val want = wanted(context)
        if (want.isEmpty()) return CapState(CapStatus.Unsupported, "Not declared by this build")
        val missing = want.filterNot { Perms.granted(context, it) }
        return when {
            missing.isEmpty() -> CapState(CapStatus.Available)
            partial.any { Perms.declared(context, it) && Perms.granted(context, it) } -> CapState(CapStatus.Issue, "Limited access: only items you picked")
            missing.size < want.size -> CapState(CapStatus.Issue, "Partly granted")
            else -> CapState(CapStatus.Missing)
        }
    }

    override fun action(context: Context): CapAction? {
        if (!applicable(context)) return null
        val missing = wanted(context).filterNot { Perms.granted(context, it) }
        return if (missing.isEmpty()) null else CapAction.Request(missing)
    }
}

object Perms {
    fun granted(context: Context, p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    @Volatile
    private var declaredSet: Set<String>? = null

    fun declared(context: Context, p: String): Boolean = (declaredSet ?: runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet()).also { declaredSet = it }).contains(p)

    fun exists(context: Context, p: String): Boolean = runCatching { context.packageManager.getPermissionInfo(p, 0); true }.getOrDefault(false)

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}

/** A special access the user switches on in a settings page. */
class SpecialCap(
    id: String,
    title: String,
    purpose: String,
    requirement: String,
    group: CapGroup,
    optional: Boolean = false,
    private val minSdk: Int = 0,
    private val check: (Context) -> CapState,
    private val open: (Context) -> Intent,
) : Capability(id, title, purpose, requirement, group, optional) {
    override fun applicable(context: Context) = Build.VERSION.SDK_INT >= minSdk
    override fun state(context: Context) = if (applicable(context)) check(context) else CapState(CapStatus.Unsupported, "Needs Android API $minSdk+")
    override fun action(context: Context): CapAction? =
        if (state(context).status == CapStatus.Available || !applicable(context)) null else CapAction.OpenSettings(open)
}

object Capabilities {
    private val rows = LinkedHashMap<String, Capability>()

    init {
        deviceRows().forEach { rows[it.id] = it }
    }

    fun register(vararg caps: Capability) = synchronized(rows) { caps.forEach { rows[it.id] = it } }

    val all: List<Capability> get() = synchronized(rows) { rows.values.toList() }

    /**
     * "N items need attention": only the core rows (accessibility, overlay). Everything else is asked when a
     * feature first needs it, so a missing optional permission is not something to fix.
     */
    fun attentionCount(context: Context): Int = all.count { c ->
        PermissionRows.isCore(c.id) && c.applicable(context) && c.state(context).status.let { it != CapStatus.Available && it != CapStatus.Unsupported }
    }

    fun isAvailable(context: Context, id: String): Boolean = rows[id]?.state(context)?.status == CapStatus.Available

    private fun yes(ok: Boolean, off: CapStatus = CapStatus.NotEnabled) = CapState(if (ok) CapStatus.Available else off)

    private fun deviceRows(): List<Capability> = listOf(
        SpecialCap(
            "accessibility", "Accessibility service (Atom Agent)", "Reads the screen, taps, swipes, types and takes screenshots for tasks you start.",
            "Settings → Accessibility → Atom", CapGroup.Screen,
            check = { c ->
                when {
                    Accessibility.connected.value -> CapState(CapStatus.Available)
                    Accessibility.isEnabled(c) -> CapState(CapStatus.Issue, "Switched on but not running. Turn it off and on again.")
                    else -> CapState(CapStatus.NotEnabled)
                }
            },
            open = { Accessibility.settingsIntent() },
        ),
        SpecialCap(
            "overlay", "Display over other apps", "Floating bubble, run capsule and the assistant overlay.",
            "Special access: display over other apps", CapGroup.Screen,
            check = { c -> yes(Settings.canDrawOverlays(c)) },
            open = { c -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")) },
        ),
        RuntimeCap(
            "notifications_post", "Notifications", "Running-task controls and background results. Asked the first time a task keeps running in the background.",
            CapGroup.Background, listOf("android.permission.POST_NOTIFICATIONS"), minSdk = 33, optional = true,
        ),
        RuntimeCap(
            "microphone", "Microphone", "Voice input. Asked the first time you use the mic; the keyboard always works.",
            CapGroup.Screen, listOf("android.permission.RECORD_AUDIO"), optional = true,
        ),
        RuntimeCap(
            "nearby", "Local network", "LAN models, MCP servers, the browser and terminals on your network. Internet works without it.",
            CapGroup.System, listOf("android.permission.ACCESS_LOCAL_NETWORK"), optional = true,
        ),
        SpecialCap(
            "dnd_access", "Do Not Disturb access", "Switch silent, vibrate, ring and Do Not Disturb modes without root.",
            "Special access: Do Not Disturb", CapGroup.System, optional = true,
            check = { c -> yes(c.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true) },
            open = { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
        ),
        SpecialCap(
            "write_settings", "Modify system settings", "Brightness, screen timeout and auto-rotate without root.",
            "Special access: modify system settings", CapGroup.System, optional = true,
            check = { c -> yes(Settings.System.canWrite(c)) },
            open = { c -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${c.packageName}")) },
        ),
        SpecialCap(
            "all_files", "All files access", "Search your photos, videos, music and documents, and reach shared folders. Atom's own workspace, pickers and import/export work without it.",
            "Special access: all files", CapGroup.System, optional = true,
            check = { yes(Environment.isExternalStorageManager()) },
            open = { c -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}")) },
        ),
        SpecialCap(
            "app_list", "Full installed-app list", "Lets Atom find any app by name.", "Install-time permission", CapGroup.System, optional = true,
            check = { c -> yes(Perms.declared(c, "android.permission.QUERY_ALL_PACKAGES"), CapStatus.Missing) },
            open = { c -> Perms.appDetails(c) },
        ),
        SpecialCap(
            "exact_alarms", "Exact alarms", "Scheduled tasks fire on time.", "Special access: alarms and reminders", CapGroup.Background,
            optional = true, minSdk = 31,
            check = { c -> yes(c.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true) },
            open = { c -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}")) },
        ),
        // The one-tap "Allow" dialog is only offered from Scheduled tasks' health banner; this row opens the list.
        SpecialCap(
            "battery", "Unrestricted battery", "Scheduled and background tasks are not cut off by Doze.", "Battery: unrestricted",
            CapGroup.Background, optional = true,
            check = { c -> yes(c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true) },
            open = { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
        ),
        SpecialCap(
            "mock_location", "Mock location app", "Optional: GPS spoofing, only while you switch it on.",
            "Developer options → Select mock location app → Atom", CapGroup.Advanced, optional = true,
            check = { c -> yes(LocationSpoof.isMockApp(c)) },
            open = { Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        ),
    )

    fun rootState(): CapState = when (RootAccess.status.value) {
        RootStatus.Unknown -> CapState(CapStatus.NotEnabled, "Not checked yet")
        RootStatus.Checking -> CapState(CapStatus.Checking, "Approve the superuser prompt (up to 30 s)")
        RootStatus.Granted -> CapState(
            CapStatus.Available,
            if (RootAccess.missingTools.isEmpty()) "Granted" else "Granted; missing tools: ${RootAccess.missingTools.joinToString()}",
        )
        RootStatus.NotDetected -> CapState(CapStatus.Unsupported, "Not detected")
        RootStatus.TimedOut -> CapState(CapStatus.Issue, "Timed out: the prompt was not answered")
        RootStatus.NotGranted -> CapState(CapStatus.Missing, "Not granted")
    }
}
