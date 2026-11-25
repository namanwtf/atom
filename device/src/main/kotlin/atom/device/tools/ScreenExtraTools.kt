package atom.device.tools

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.Accessibility
import atom.device.apps.AppResolver
import atom.device.control.ControlError
import atom.device.control.DeviceEnv
import atom.device.screen.UiController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Hardware keys, system panels and waiting, all of which work without the accessibility service where possible. */
internal class ScreenExtraTools(private val context: Context) {
    private val app = context.applicationContext

    fun all(): List<Tool> = listOf(pressKey(), openPanel(), waitFor())

    private val shellKeys = mapOf(
        "enter" to "KEYCODE_ENTER", "delete" to "KEYCODE_DEL", "forward_delete" to "KEYCODE_FORWARD_DEL", "tab" to "KEYCODE_TAB",
        "escape" to "KEYCODE_ESCAPE", "space" to "KEYCODE_SPACE", "dpad_up" to "KEYCODE_DPAD_UP", "dpad_down" to "KEYCODE_DPAD_DOWN",
        "dpad_left" to "KEYCODE_DPAD_LEFT", "dpad_right" to "KEYCODE_DPAD_RIGHT", "page_up" to "KEYCODE_PAGE_UP",
        "page_down" to "KEYCODE_PAGE_DOWN", "move_home" to "KEYCODE_MOVE_HOME", "move_end" to "KEYCODE_MOVE_END",
        "camera" to "KEYCODE_CAMERA", "search" to "KEYCODE_SEARCH",
    )
    private val keys = listOf("power", "power_menu", "volume_up", "volume_down", "mute", "screenshot") + shellKeys.keys

    private fun pressKey() = SimpleTool(
        name = "press_key",
        description = "Press a hardware or keyboard key: power (screen off/on), power_menu, volume_up, volume_down, " +
            "mute (toggle media mute), screenshot, or keyboard keys (${shellKeys.keys.joinToString()}; these need Shizuku or root).",
        risk = Risk.Act,
        parameters = schema { string("key", "Which key.", required = true, enum = keys) },
        usesPhoneUi = true,
    ) { args, _ ->
        val key = args.string("key").lowercase()
        val am = app.getSystemService(AudioManager::class.java)
        when (key) {
            "volume_up", "volume_down", "mute" -> {
                am ?: return@SimpleTool ToolResult.error("Audio service unavailable.")
                val streams = listOf(AudioManager.STREAM_MUSIC to "Media", AudioManager.STREAM_RING to "Ring", AudioManager.STREAM_VOICE_CALL to "Call")
                val before = streams.associate { it.first to (am.getStreamVolume(it.first) to am.isStreamMute(it.first)) }
                try {
                    when (key) {
                        "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                        else -> am.adjustSuggestedStreamVolume(
                            if (key == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
                        )
                    }
                } catch (e: SecurityException) {
                    return@SimpleTool ToolResult.error("Android refused (Do Not Disturb may be blocking volume changes).")
                }
                delay(150)
                val changed = streams.mapNotNull { (s, name) ->
                    val (v0, m0) = before.getValue(s)
                    val v1 = am.getStreamVolume(s)
                    val m1 = am.isStreamMute(s)
                    val max = am.getStreamMaxVolume(s)
                    if (v0 != v1 || m0 != m1) "$name volume: ${if (m0) "muted" else "$v0/$max"} → ${if (m1) "muted" else "$v1/$max"} (verified)" else null
                }
                ToolResult.ok(changed.joinToString("\n").ifEmpty { "Pressed $key; no volume changed (already at the limit), unconfirmed." })
            }
            "power" -> {
                val pm = app.getSystemService(PowerManager::class.java)
                val wasOn = pm?.isInteractive == true
                val sh = DeviceEnv(app).privileged()
                val pressed = when {
                    sh != null -> sh.run("input keyevent KEYCODE_POWER", 10_000).ok
                    wasOn && Accessibility.service != null -> Accessibility.service!!.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    else -> false
                }
                if (!pressed) return@SimpleTool ToolResult.error(
                    if (wasOn) "Cannot press power: needs accessibility, Shizuku or root." else "Turning the screen on needs Shizuku or root.",
                )
                delay(600)
                val isOn = pm?.isInteractive == true
                ToolResult.ok("Screen: ${if (wasOn) "on" else "off"} → ${if (isOn) "on" else "off"} (${if (isOn != wasOn) "verified" else "unconfirmed"}).")
            }
            "power_menu", "screenshot" -> {
                val svc = Accessibility.service ?: return@SimpleTool ToolResult.error(UiController.NOT_CONNECTED)
                val action = if (key == "power_menu") AccessibilityService.GLOBAL_ACTION_POWER_DIALOG else AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                if (!svc.performGlobalAction(action)) return@SimpleTool ToolResult.error("The system refused $key.")
                UiController.outcome(svc, "Pressed $key")
            }
            else -> {
                val code = shellKeys[key] ?: throw ToolArgumentException("Unknown key '$key'.")
                val sh = DeviceEnv(app).privileged() ?: return@SimpleTool ToolResult.error("Keyboard keys need Shizuku or root.")
                if (!sh.run("input keyevent $code", 10_000).ok) return@SimpleTool ToolResult.error("input keyevent failed.")
                val svc = Accessibility.service ?: return@SimpleTool ToolResult.ok("Outcome: submitted, unconfirmed. Pressed $key.")
                UiController.outcome(svc, "Pressed $key")
            }
        }
    }

    private val panels: Map<String, () -> Intent> = mapOf(
        "internet" to { Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) },
        "wifi" to { Intent(Settings.Panel.ACTION_WIFI) },
        "nfc" to { Intent(Settings.Panel.ACTION_NFC) },
        "volume" to { Intent(Settings.Panel.ACTION_VOLUME) },
        "bluetooth" to { Intent(Settings.ACTION_BLUETOOTH_SETTINGS) },
        "display" to { Intent(Settings.ACTION_DISPLAY_SETTINGS) },
        "sound" to { Intent(Settings.ACTION_SOUND_SETTINGS) },
        "battery" to { Intent(Intent.ACTION_POWER_USAGE_SUMMARY) },
        "location" to { Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) },
        "apps" to { Intent(Settings.ACTION_APPLICATION_SETTINGS) },
        "notifications_settings" to { Intent("android.settings.NOTIFICATION_SETTINGS") },
        "accessibility" to { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        "developer" to { Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
        "date_time" to { Intent(Settings.ACTION_DATE_SETTINGS) },
        "storage" to { Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) },
        "security" to { Intent(Settings.ACTION_SECURITY_SETTINGS) },
        "mobile_network" to { Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS) },
        "hotspot" to { Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings")) },
        "settings" to { Intent(Settings.ACTION_SETTINGS) },
    )

    private fun openPanel() = SimpleTool(
        name = "open_panel",
        description = "Open a system panel or settings page: notifications, quick_settings, ${panels.keys.joinToString()}, " +
            "or app_info (with package) for one app's settings page.",
        risk = Risk.Act,
        parameters = schema {
            string("panel", "Which panel.", required = true, enum = listOf("notifications", "quick_settings", "app_info") + panels.keys)
            string("app", "App name or package, for app_info.")
        },
        usesPhoneUi = true,
    ) { args, _ ->
        val panel = args.string("panel").lowercase()
        val env = DeviceEnv(app)
        if (panel == "notifications" || panel == "quick_settings") {
            val svc = Accessibility.service
            if (svc != null) return@SimpleTool UiController.outcome(svc, UiController.globalAction(svc, panel))
            val sh = env.privileged() ?: return@SimpleTool ToolResult.error(UiController.NOT_CONNECTED)
            val ok = sh.run(if (panel == "notifications") "cmd statusbar expand-notifications" else "cmd statusbar expand-settings", 10_000).ok
            return@SimpleTool if (ok) ToolResult.ok("Opened $panel via ${env.tierName(sh)}.") else ToolResult.error("Could not open $panel.")
        }
        val intent = if (panel == "app_info") {
            val r = try {
                AppResolver.resolve(app, args.optString("app") ?: throw ToolArgumentException("app_info needs app."))
            } catch (e: ControlError) {
                return@SimpleTool ToolResult.error(e.message ?: "No such app.")
            }
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${r.packageName}"))
        } else {
            panels[panel]?.invoke() ?: throw ToolArgumentException("Unknown panel '$panel'.")
        }
        if (!env.startActivity(intent)) {
            if (panel == "hotspot" && env.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))) {
                return@SimpleTool ToolResult.ok("This phone has no direct hotspot page; opened network settings instead.")
            }
            return@SimpleTool ToolResult.error("This phone has no $panel page Atom can open.")
        }
        val svc = Accessibility.service ?: return@SimpleTool ToolResult.ok("Opened $panel (accessibility is off, so it was not checked).")
        UiController.outcome(svc, "Opened $panel")
    }

    private fun waitFor() = SimpleTool(
        name = "wait",
        description = "Wait for a number of seconds, or until text appears on screen, or until an app is in front " +
            "(timeout_s, default 15, max 60). Event-driven: returns as soon as the condition holds.",
        risk = Risk.Read,
        parameters = schema {
            integer("seconds", "Plain wait, 1-60.")
            string("text", "Wait until this text is visible.")
            string("app", "Wait until this app (name or package) is in the foreground.")
            integer("timeout_s", "Give up after this many seconds. Default 15.")
        },
        usesPhoneUi = true,
    ) { args, _ ->
        val text = args.optString("text")?.takeIf { it.isNotBlank() }
        val appQuery = args.optString("app")?.takeIf { it.isNotBlank() }
        val timeoutMs = (args.optInt("timeout_s") ?: 15).coerceIn(1, 60) * 1000L
        if (text == null && appQuery == null) {
            val s = (args.optInt("seconds") ?: throw ToolArgumentException("Give seconds, text or app.")).coerceIn(1, 60)
            delay(s * 1000L)
            return@SimpleTool ToolResult.ok("Waited $s s.")
        }
        val pkg = appQuery?.let {
            try {
                AppResolver.resolve(app, it).packageName
            } catch (e: ControlError) {
                return@SimpleTool ToolResult.error(e.message ?: "No such app.")
            }
        }
        val svc = Accessibility.service
        val started = System.currentTimeMillis()
        if (svc == null) {
            if (pkg == null) return@SimpleTool ToolResult.error("Waiting for text needs Atom's accessibility service.")
            val sh = DeviceEnv(app).privileged() ?: return@SimpleTool ToolResult.error(UiController.NOT_CONNECTED)
            val found = withTimeoutOrNull(timeoutMs) {
                while (!sh.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", 5_000).stdout.contains(pkg)) delay(1_000)
                true
            } == true
            return@SimpleTool ToolResult.ok(if (found) "$pkg is in front." else "Timed out after ${timeoutMs / 1000} s; $pkg did not come to the front.")
        }
        val found = withTimeoutOrNull(timeoutMs) {
            var seen = Accessibility.settle.events.value
            while (true) {
                val ok = (pkg == null || UiController.foreground(svc) == pkg) && (text == null || UiController.screenContains(svc, text))
                if (ok) break
                // Window events wake this up; with no task-scoped events it still re-checks every 2 s.
                seen = withTimeoutOrNull(2_000) { Accessibility.settle.events.first { it != seen } } ?: seen
            }
            true
        } == true
        val took = (System.currentTimeMillis() - started) / 100 / 10.0
        val what = listOfNotNull(text?.let { "\"$it\" visible" }, pkg?.let { "$it in front" }).joinToString(" and ")
        withContext(Dispatchers.Default) {
            if (found) ToolResult.ok("Condition met after $took s: $what. Foreground: ${UiController.foreground(svc)}.")
            else ToolResult.ok("Timed out after ${timeoutMs / 1000} s waiting for $what. Foreground: ${UiController.foreground(svc)}. Call read_screen.")
        }
    }
}
