package atom.context.sources

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.device.ShellExecutor
import atom.device.ShellTier
import atom.device.Shells
import atom.ui.permission.PermissionAsk
import atom.ui.permission.PermissionAsks
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class CtxTool(
    name: String,
    description: String,
    override val risk: Risk,
    parameters: JsonObject,
    private val body: suspend (JsonObject, ToolContext) -> ToolResult,
) : Tool {
    override val spec = ToolSpec(name, description, parameters)
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = body(args, ctx)
}

/** A tool error the model can act on, thrown from helpers. */
internal class Missing(message: String) : Exception(message)

/**
 * Throws [Missing] unless one of [perms] is granted. The first time, it also asks the app to show the user a single
 * in-context prompt (see [PermissionAsks]); after the user has answered, it only explains and never asks again.
 */
internal fun Context.requirePermission(vararg perms: String, what: String) {
    if (perms.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return
    val ask = JitAsks.forPermission(this, perms.first())
    throw Missing(PermissionAsks.toolMessage(this, ask, what))
}

/** The one-sentence reasons the in-context prompt shows, per permission a context tool needs. */
internal object JitAsks {
    fun forPermission(context: Context, permission: String): PermissionAsk = when (permission) {
        Manifest.permission.READ_SMS -> PermissionAsk(
            "sms", "SMS", "Atom reads your messages only to find the code or message you asked about.", listOf(permission),
        )
        Manifest.permission.READ_CONTACTS -> PermissionAsk(
            "contacts", "Contacts", "Atom looks up the people you name, only when you ask.", listOf(permission),
        )
        Manifest.permission.READ_CALL_LOG -> PermissionAsk(
            "call_log", "Call history", "Atom searches your calls only to answer what you asked.", listOf(permission),
        )
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> PermissionAsk(
            "calendar", "Calendar", "Atom reads and changes events only when you ask it to.",
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        )
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION -> PermissionAsk(
            "location", "Location", "Atom checks where you are only for the answer you asked for.",
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        else -> PermissionAsk(permission, permission.substringAfterLast('.').replace('_', ' ').lowercase(), "Atom needs this for what you asked.", listOf(permission))
    }

    /** Shared storage beyond Atom's own files: one special access, opened in system settings. */
    fun allFiles(context: Context) = PermissionAsk(
        "all_files", "All files access", "Atom searches your photos, videos, music and documents only when you ask.",
        settings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
    )

    fun usageAccess(context: Context) = PermissionAsk(
        "usage_access", "Usage access", "Atom reads screen time and app storage only for the question you asked.",
        settings = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    fun notificationAccess(context: Context) = PermissionAsk(
        "notification_listener", "Notification access", "Atom reads your notifications only when you ask about them.",
        settings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            atom.context.notifications.AtomNotificationListener.component(context).flattenToString(),
        ),
    )
}

internal suspend fun rootShell(context: Context, what: String): ShellExecutor {
    val sh = Shells.detect(context)
    if (sh.tier != ShellTier.Root) throw Missing("$what needs root, which is not available or not granted (Permissions → Root).")
    return sh
}

internal suspend fun privilegedShell(context: Context, what: String): ShellExecutor {
    val sh = Shells.detect(context)
    if (sh.tier == ShellTier.App) throw Missing("$what needs Shizuku or root (see Permissions).")
    return sh
}

/** Runs [block], turning [Missing] into a tool error. */
internal suspend fun guarded(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: Missing) {
    ToolResult.error(e.message ?: "Not available.")
} catch (e: SecurityException) {
    ToolResult.error("Android refused access: ${e.message}. Check Atom's Permissions page.")
}

internal object When {
    private val dateTime = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.US)

    fun at(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = dateTime.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val d = Duration.ofMillis((now - millis).coerceAtLeast(0))
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 48 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} days ago"
        }
    }

    fun duration(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "${ms / 1000} s"
            m < 60 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }
}
