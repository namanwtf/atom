package atom.context.feature

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import atom.context.health.HealthPerms
import atom.context.notifications.AtomNotificationListener
import atom.context.notifications.Notifications
import atom.context.sources.Usage
import atom.device.caps.CapAction
import atom.device.caps.CapGroup
import atom.device.caps.CapState
import atom.device.caps.CapStatus
import atom.device.caps.Capability
import atom.device.caps.Needs
import atom.device.caps.RuntimeCap
import atom.device.caps.SpecialCap
import atom.device.caps.ToolCatalog
import atom.device.caps.ToolGroupInfo
import atom.device.switches.ToolSwitch

/** Location is two steps on Android 11+: "while using" first, then "all the time" in settings. */
private object LocationCap : Capability(
    "location", "Location", "Time-and-place answers and location-aware tasks. Background tasks need \"Allow all the time\".",
    "Permission: fine/approximate location, plus background location", CapGroup.Personal,
) {
    private fun has(c: Context, p: String) = c.checkSelfPermission(p) == 0

    override fun state(context: Context): CapState {
        val fine = has(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val bg = has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return when {
            !fine && !coarse -> CapState(CapStatus.Missing)
            !bg -> CapState(CapStatus.Issue, "Only while using Atom: background tasks cannot read location")
            !fine -> CapState(CapStatus.Issue, "Approximate only")
            else -> CapState(CapStatus.Available)
        }
    }

    override fun action(context: Context): CapAction? {
        val fine = has(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            !fine -> CapAction.Request(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            !has(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) && (fine || coarse) ->
                CapAction.Request(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), "Allow all the time")
            else -> null
        }
    }
}

internal object ContextCapabilities {
    fun register() {
        atom.device.caps.Capabilities.register(
            SpecialCap(
                "notification_listener", "Notification access", "Current notifications, searchable 7-day history (recorded from the moment you grant it), media controls.",
                "Special access: notification access → Atom notification history", CapGroup.Personal,
                check = { c -> CapState(if (Notifications.accessGranted(c)) CapStatus.Available else CapStatus.NotEnabled) },
                open = { c ->
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, AtomNotificationListener.component(c).flattenToString())
                },
            ),
            SpecialCap(
                "usage_access", "Usage access", "Recent apps, screen time per app and per-app storage.", "Special access: usage access", CapGroup.Personal,
                check = { c -> CapState(if (Usage.granted(c)) CapStatus.Available else CapStatus.NotEnabled) },
                open = { c -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}")) },
            ),
            LocationCap,
            RuntimeCap(
                "calendar", "Calendar", "Find, create, change and delete events.", CapGroup.Personal,
                listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            ),
            RuntimeCap("contacts", "Contacts", "Find people by name.", CapGroup.Personal, listOf(Manifest.permission.READ_CONTACTS)),
            RuntimeCap("sms", "SMS", "Verification codes and message search.", CapGroup.Personal, listOf(Manifest.permission.READ_SMS)),
            RuntimeCap("call_log", "Call history", "Search calls by number or contact.", CapGroup.Personal, listOf(Manifest.permission.READ_CALL_LOG)),
            RuntimeCap(
                "media", "Photos, videos and audio", "Search the gallery, screenshots, music and recordings.", CapGroup.Personal,
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE),
                partial = listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
            ),
            object : RuntimeCap(
                "health", "Health Connect", "Optional: steps, sleep, exercise and body measurements, read only when you ask.", CapGroup.Personal,
                HealthPerms.all, minSdk = 34, optional = true,
            ) {
                override fun applicable(context: Context) = HealthPerms.available(context)
            },
        )
        ToolCatalog.register(
            ToolGroupInfo(
                "context", "Personal context",
                "Time and location, contacts, calendar, photos, screenshots, audio, recordings, documents, downloads, app usage, " +
                    "memory and storage ranking, alarms and timers. Read on demand in bounded pages; read_item opens any hit in full.",
                listOf(
                    "time_and_location", "contacts_search", "files_search", "app_usage", "calendar_search", "calendar_read",
                    "calendar_list_calendars", "alarms_and_timers", "memory_ranking", "storage_ranking", "read_item",
                ),
                Needs.Permission, setOf(ToolSwitch.DeviceDirect), 70,
            ),
            ToolGroupInfo(
                "context_sensitive", "Messages and private data",
                "SMS codes and search, call history, current notifications and 7-day history, messenger triage, Health Connect summary.",
                listOf("sms_verification_code", "sms_search", "call_log_search", "notifications_current", "notification_history", "messenger_triage", "health_summary"),
                Needs.Permission, setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead), 72,
            ),
            ToolGroupInfo(
                "context_root", "Root data", "Saved Wi-Fi passwords, keyboard clipboard history, WhatsApp chats and read-only inspection of any app's private files and databases.",
                listOf("wifi_saved_passwords", "clipboard_history", "app_private_data"), Needs.Root,
                setOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead), 74,
            ),
            ToolGroupInfo(
                "apps_ops", "System and built-in apps", "Calendar create/update/delete with read-back, alarms and timers, media controls.",
                listOf("calendar_create", "calendar_update", "calendar_delete", "set_alarm", "set_timer", "change_alarm", "media_control"),
                Needs.Permission, setOf(ToolSwitch.DeviceDirect), 76,
            ),
        )
    }
}
