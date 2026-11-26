# Permissions

Atom declares 43 permissions (down from 54). Nothing is asked at first launch or on the setup screen. Each runtime
permission and special access is asked once, at the moment a feature or agent tool first needs it, with one sentence
saying why. If you say no, that feature is turned off and says so; Atom does not ask again (you can still allow it on
the Permissions page any time).

How the asking works: a tool that lacks a permission returns "needs X, ask the user" to the agent and queues one
in-context prompt (`atom.ui.permission.PermissionAsks`, shown by `PermissionAskHost`). The answer is remembered per
permission, so the same prompt never comes back in a loop.

The Permissions page has two groups: **Needed for core features** (accessibility, floating window) and
**Optional — asked only when used** (everything else).

## Needed for core features

| Permission | Kind | Used by | When it is asked |
| --- | --- | --- | --- |
| Accessibility service (Atom Agent) | Special access | Reading the screen, taps, swipes, typing, screenshots for tasks you start | Setup screen and Permissions page show a button; you switch it on in system settings |
| `SYSTEM_ALERT_WINDOW` | Special access | Floating bubble, run capsule, assistant overlay | Permissions page or when you turn on the bubble/overlay |

## Runtime permissions (asked only when used)

| Permission | Used by | When it is asked |
| --- | --- | --- |
| `POST_NOTIFICATIONS` | Running-task notification with Stop, background results, scheduled task results, download and install progress | The first time a task is still running after a few seconds, or you turn on a scheduled task or automation that reports, start a model download or a Linux install |
| `RECORD_AUDIO` | Voice input, audio chat, "Hey Atom" | When you tap the mic, open audio chat or switch on "Hey Atom" |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | `time_and_location` tool; place and Wi-Fi-name automations | When the agent first needs your location (not for time-only questions), or when you add a place trigger |
| `ACCESS_BACKGROUND_LOCATION` | Place automations that fire while Atom is closed | Only from the automation editor, after foreground location, when a rule uses a place trigger |
| `READ_CONTACTS` | `contacts_search`, contact names in SMS and call searches | When the agent first looks up a person |
| `READ_SMS` | `sms_verification_code`, `sms_search` | When the agent first reads a code or searches messages |
| `READ_CALL_LOG` | `call_log_search` | When the agent first searches your calls |
| `READ_CALENDAR`, `WRITE_CALENDAR` | Calendar search, read, create, update, delete | When the agent first reads or changes the calendar (one system prompt for both) |
| `BLUETOOTH_CONNECT` | Bluetooth device automations | From the automation editor, when a rule uses a Bluetooth trigger |
| `ACCESS_LOCAL_NETWORK` | Models, MCP servers, browser and terminals on your LAN (Android releases that gate local network access) | Permissions page; internet access never needs it |
| Health Connect reads: `health.READ_STEPS`, `READ_DISTANCE`, `READ_TOTAL_CALORIES_BURNED`, `READ_EXERCISE`, `READ_SLEEP`, `READ_HEART_RATE`, `READ_RESTING_HEART_RATE`, `READ_WEIGHT` | `health_summary` (exactly these types, nothing else) | Through Health Connect when you ask a health question, only for that topic (activity, sleep, heart or weight) |

## Special access (switched on in system settings, asked only when used)

| Permission | Used by | When it is asked |
| --- | --- | --- |
| `MANAGE_EXTERNAL_STORAGE` (All files access) | `files_search` for photos, videos, music, recordings, documents and downloads; Linux and the terminal seeing `/sdcard` | When the agent first searches shared files (it shows Atom's own files until then), or from the Workspace page. The app's own image and file pickers use the system Photo Picker and document picker, which need no permission |
| `PACKAGE_USAGE_STATS` (Usage access) | `app_usage`, per-app storage ranking | When the agent first needs screen time or app storage |
| Notification access (listener service) | Current notifications, 7-day history, messenger triage, notification automations | When the agent first reads notifications, or from the automation editor |
| `WRITE_SETTINGS` | Brightness, screen timeout, auto-rotate without root | Permissions page |
| `ACCESS_NOTIFICATION_POLICY` | Lists Atom on the Do Not Disturb access page (silent/vibrate/DND without root) | Permissions page |
| `SCHEDULE_EXACT_ALARM` | Scheduled tasks and timed automations start on the minute | Scheduled tasks health banner and the automation editor, when you tap it |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Background runs not cut off by Doze | Only from the Scheduled tasks health banner, when you tap it. The Permissions page opens the battery list instead |
| `REQUEST_INSTALL_PACKAGES` | In-app update | Only when you tap Install on an update |
| `ACCESS_MOCK_LOCATION` | Optional GPS spoofing | Only if you pick Atom as the mock location app in Developer options |

## Install-time (never prompted)

| Permission | Used by |
| --- | --- |
| `INTERNET` | Cloud models, web search, browser, downloads |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Network status, Wi-Fi state, Wi-Fi automations |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Running agent tasks, automation trigger listener, terminal sessions, location spoofing (each only while active) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Model downloads and the Linux install (WorkManager foreground workers) |
| `FOREGROUND_SERVICE_MICROPHONE` | "Hey Atom" listening, only while switched on |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Read aloud |
| `POST_PROMOTED_NOTIFICATIONS` | Android 16+ Live Update for the running task |
| `WAKE_LOCK` | Keeping the CPU awake during a run and read aloud |
| `VIBRATE` | The "Hey Atom" wake tick |
| `RECEIVE_BOOT_COMPLETED` | Re-arming scheduled tasks; re-applying saved firewall rules or charge limits (receiver off until you save one) |
| `QUERY_ALL_PACKAGES` | Finding any installed app by name (sideloaded build) |
| `com.android.alarm.permission.SET_ALARM` | Setting alarms and timers in the clock app |

## Removed in this change

| Permission | Why |
| --- | --- |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `READ_EXTERNAL_STORAGE` | Duplicates: shared-file search already used All files access for documents and downloads, and that one grant covers media too. The app's own UI uses the Photo Picker and document picker |
| `health.READ_HEIGHT`, `health.READ_BODY_FAT` | Dropped from `health_summary`; the manifest now lists exactly the types it reads |
| `NEARBY_WIFI_DEVICES` | No code used it (no Wi-Fi scanning, Aware or P2P); LAN access is `ACCESS_LOCAL_NETWORK` |
| `CHANGE_WIFI_STATE` | No code used it: Wi-Fi is toggled through Shizuku/root or the system panel |
| `NFC` | No code used it: reading whether NFC is on needs no permission, toggling goes through Shizuku/root or the panel |
| `MODIFY_AUDIO_SETTINGS` | No code used it: volume and ringer changes do not need it |
