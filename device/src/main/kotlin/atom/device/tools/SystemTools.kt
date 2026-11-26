package atom.device.tools

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import atom.core.tool.ApprovalRequest
import atom.core.tool.PerCallRisk
import atom.core.tool.Risk
import atom.core.tool.ShellRisk
import atom.core.tool.ShellScope
import atom.core.tool.Tool
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.Accessibility
import atom.device.ShellExecutor
import atom.device.ShellTier
import atom.device.apps.AppMatcher
import atom.device.apps.Apps
import atom.device.power.Battery
import atom.device.power.PowerPolicy
import atom.device.screen.UiController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class SystemTools(context: Context, private val shell: ShellExecutor) {
    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024L * 1024 * 1024
    }

    private val app = context.applicationContext

    fun all(): List<Tool> = listOf(
        openApp(), listApps(), openUrl(), shellTool(), deviceStatus(), setClipboard(), getClipboard(),
    )

    /** Starting activities from the background is allowed for apps with a bound accessibility service. */
    private fun launcher(): Context = Accessibility.service ?: app

    private suspend fun launched(what: String, expectPackage: String?): ToolResult {
        val svc = Accessibility.service
        if (svc != null) return UiController.outcome(svc, what, expectPackage)
        if (expectPackage != null && shell.tier != ShellTier.App) {
            val focus = shell.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", 5_000).stdout
            return if (focus.contains(expectPackage)) {
                ToolResult.ok("Outcome: done. $what; the $expectPackage window was seen (checked via ${shell.tier.name.lowercase()}).")
            } else {
                ToolResult.ok("Outcome: submitted, unconfirmed. $what, but the $expectPackage window was not seen yet.")
            }
        }
        return ToolResult.ok("Outcome: submitted, unconfirmed. $what. (Accessibility is off, so whether the window appeared cannot be checked.)")
    }

    /** Starts [intent]; falls back to the shell's `am start` when Android blocks a background start. */
    private suspend fun start(intent: Intent, component: ComponentName?): Boolean {
        val ok = try {
            withContext(Dispatchers.Main) { launcher().startActivity(intent) }
            true
        } catch (e: Exception) {
            false
        }
        if (ok && (Accessibility.service != null || shell.tier == ShellTier.App || component == null)) return true
        if (component != null && shell.tier != ShellTier.App) {
            if (ok) {
                kotlinx.coroutines.delay(800)
                if (shell.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'", 5_000).stdout.contains(component.packageName)) return true
            }
            return shell.run("am start -n ${component.flattenToShortString()} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER", 10_000).ok
        }
        return ok
    }

    private fun openApp() = SimpleTool(
        name = "open_app",
        description = "Open an installed app by name (fuzzy, e.g. \"maps\", \"whats app\") or exact package name. " +
            "Works without accessibility; reports whether the app's window was actually seen. Opens on the user's own " +
            "screen: when background_open_app is offered, prefer it for tasks in other apps.",
        risk = Risk.Act,
        usesPhoneUi = true,
        parameters = schema {
            string("app", "App name or package name.", required = true)
        },
    ) { args, _ ->
        val query = args.string("app")
        if (WebLinkRouting.meansBuiltInBrowser(query)) return@SimpleTool ToolResult.error(WebLinkRouting.BUILT_IN_BROWSER_HINT)
        val apps = withContext(Dispatchers.IO) { Apps.launchable(app) }
        val ranked = AppMatcher.rank(query, apps.map { it.entry })
        val best = ranked.firstOrNull()
            ?: return@SimpleTool ToolResult.error("No installed app matches \"$query\". Call list_apps to see what is installed.")
        val tied = ranked.drop(1).takeWhile { it.score == best.score }.take(4)
        if (tied.isNotEmpty() && best.score < 100) {
            val options = (listOf(best) + tied).joinToString("; ") { "${it.app.label}=${it.app.packageName}" }
            return@SimpleTool ToolResult.error("\"$query\" is ambiguous: $options. Call open_app with the exact package name.")
        }
        val target = apps.first { it.entry == best.app }
        if (!start(Apps.launchIntent(target), ComponentName(target.entry.packageName, target.activity))) {
            return@SimpleTool ToolResult.error("Android would not open ${best.app.label}.")
        }
        launched("Opened ${best.app.label} (${best.app.packageName})", best.app.packageName)
    }

    private fun listApps() = SimpleTool(
        name = "list_apps",
        description = "Search installed launchable apps by name or package (fuzzy); lists label=package, one per line.",
        risk = Risk.Read,
        parameters = schema {
            string("filter", "Only apps whose name or package matches this.")
        },
    ) { args, _ ->
        val apps = withContext(Dispatchers.IO) { Apps.launchable(app) }.map { it.entry }.distinctBy { it.packageName to it.label }
        val filter = args.optString("filter")?.takeIf { it.isNotBlank() }
        val shown = if (filter == null) apps else AppMatcher.rank(filter, apps).map { it.app }
        if (shown.isEmpty()) return@SimpleTool ToolResult.ok("No apps match \"$filter\".")
        ToolResult.ok(capText("${shown.size} apps:\n" + shown.joinToString("\n") { "${it.label}=${it.packageName}" }, 6_000))
    }

    private fun openUrl() = SimpleTool(
        name = "open_url",
        description = "Open a link OUTSIDE Atom, in another app: deep links (geo:, tel:, mailto:, sms:, market:, app " +
            "schemes) or, only when the user asks for their own browser or a specific app, a web page (set external=true). " +
            "Never use it to read, search, summarize or browse websites: that is Atom's built-in agent browser " +
            "(browser_open) or web_search / read_page.",
        risk = Risk.Act,
        usesPhoneUi = true,
        parameters = schema {
            string("url", "The deep link or URL.", required = true)
            boolean(
                "external",
                "true only when the user asked to open this web page in their own browser or another app. Plain " +
                    "http(s) pages are refused without it.",
            )
        },
    ) { args, _ ->
        val raw = args.string("url").trim()
        WebLinkRouting.openUrlRefusal(raw, external = args.optBoolean("external") == true)?.let {
            return@SimpleTool ToolResult.error(it)
        }
        val withScheme = WebLinkRouting.withScheme(raw)
        val uri = Uri.parse(withScheme)
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            withContext(Dispatchers.Main) { launcher().startActivity(intent) }
        } catch (e: ActivityNotFoundException) {
            return@SimpleTool ToolResult.error("No installed app can open $withScheme.")
        } catch (e: Exception) {
            return@SimpleTool ToolResult.error("Could not open $withScheme: ${e.message}")
        }
        launched("Opened $withScheme", null)
    }

    private fun shellTool(): Tool {
        val where = when (shell.tier) {
            ShellTier.Root -> "Runs as root (su)."
            ShellTier.Shizuku -> "Runs as the adb shell user via Shizuku (no root: system dirs are read-only)."
            ShellTier.App -> "Runs as Atom's own app user (no root): fine for files in Atom's storage, network tools and " +
                "general commands; system settings and other apps' data are off limits."
        }
        return object : SimpleTool(
            name = "shell",
            description = "Run a shell command on the phone. $where Each call is a fresh subshell (cd does not " +
                "persist). Returns exit code, stdout, stderr. Prefer UI tools for anything the user should see. Read-only " +
                "commands run at once; anything that changes files or the system asks the user first.",
            risk = Risk.Sensitive,
            parameters = schema {
                string("command", "The sh command line.", required = true)
                integer("timeout_s", "Seconds before the command is killed, 1-55. Default 30.")
            },
            body = { args, _ ->
                val command = args.string("command")
                val timeout = (args.optInt("timeout_s") ?: 30).coerceIn(1, 55) * 1_000L
                val r = shell.run(command, timeout)
                val body = buildString {
                    append("exit ").append(r.exitCode)
                    if (r.stdout.isNotBlank()) append("\nstdout:\n").append(capText(r.stdout.trim(), 3_000))
                    if (r.stderr.isNotBlank()) append("\nstderr:\n").append(capText(r.stderr.trim(), 1_000))
                    if (r.stdout.isBlank() && r.stderr.isBlank()) append(" (no output)")
                }
                ToolResult(body, isError = r.exitCode != 0)
            },
        ), PerCallRisk {
            private fun scope() = ShellScope.android(
                packageName = app.packageName,
                dataDir = app.applicationInfo.dataDir,
                cacheDir = app.cacheDir.path,
                workDirs = listOf(java.io.File(app.filesDir, "atom4_workspace").path),
                privileged = shell.tier != ShellTier.App,
            )

            // Commands proven read-only run without asking; everything else asks (or runs under the task's grant).
            override fun riskFor(args: JsonObject): Risk = ShellRisk.classify(args.optString("command").orEmpty(), scope())

            override fun approvalRequest(args: JsonObject): ApprovalRequest =
                ShellRisk.approval(args.optString("command").orEmpty(), scope(), if (shell.tier == ShellTier.Root) "a root shell" else "the phone's shell")
        }
    }

    private fun deviceStatus() = SimpleTool(
        name = "device_status",
        description = "Device, network and environment status: battery and charging, thermal, memory, storage, OS; " +
            "connection type, Wi-Fi, internet validated, VPN, airplane; screen on/locked, ringer and Do Not Disturb, " +
            "audio output, external displays; local time and timezone.",
        risk = Risk.Read,
        parameters = schema {
            string("section", "Only one part. Default all.", enum = listOf("all", "device", "network", "environment"))
        },
    ) { args, _ ->
        val section = args.optString("section")?.lowercase() ?: "all"
        val lines = mutableListOf<String>()
        if (section == "all" || section == "device") lines += deviceLines()
        if (section == "all" || section == "network") lines += "network: " + network() + airplane()
        if (section == "all" || section == "environment") lines += environmentLines()
        val now = ZonedDateTime.now()
        lines += "time: " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.US)) +
            " ${now.zone.id} (UTC${now.offset.id.replace("Z", "+00:00")})"
        ToolResult.ok(lines.joinToString("\n"))
    }

    private fun deviceLines(): List<String> {
        val b = Battery.read(app)
        val power = app.getSystemService(PowerManager::class.java)
        val lines = mutableListOf<String>()
        lines += buildString {
            append("battery: ").append(if (b.percent >= 0) "${b.percent}%" else "unknown")
            append(if (b.charging) " charging" + (b.plugged?.let { " ($it)" } ?: "") else " not charging")
            b.temperatureC?.let { append(", ${it}°C") }
            if (power?.isPowerSaveMode == true) append(", battery saver on")
            app.getSystemService(android.os.BatteryManager::class.java)?.let { bm ->
                if (b.charging) bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { append(", full in ${it / 60_000} min") }
            }
        }
        power?.let { lines += "thermal: " + PowerPolicy.thermalOf(it.currentThermalStatus).name.lowercase() }
        app.getSystemService(android.app.ActivityManager::class.java)?.let { am ->
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            lines += "memory: ${mi.availMem / MB} MB free of ${mi.totalMem / MB} MB" + if (mi.lowMemory) " (low memory)" else ""
        }
        runCatching {
            val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            lines += "storage: ${st.availableBytes / GB} GB free of ${st.totalBytes / GB} GB"
        }
        lines += "os: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), patch " +
            "${android.os.Build.VERSION.SECURITY_PATCH}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, up " +
            "${android.os.SystemClock.elapsedRealtime() / 3_600_000} h"
        return lines
    }

    private fun airplane(): String =
        if (android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1) ", airplane mode on" else ""

    private fun environmentLines(): List<String> {
        val power = app.getSystemService(PowerManager::class.java)
        val lines = mutableListOf<String>()
        val km = app.getSystemService(KeyguardManager::class.java)
        val locked = km?.isKeyguardLocked == true
        lines += "screen: " + (if (power?.isInteractive == true) "on" else "off") + (if (locked) ", locked" else ", unlocked") +
            if (km?.isDeviceSecure == true) " (secure lock set)" else " (no secure lock)"
        lines += "sound: " + sound()
        app.getSystemService(AudioManager::class.java)?.let { am ->
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
            val route = when {
                outs.any { it == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET || it == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER } -> "Bluetooth audio"
                outs.any { it == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it == android.media.AudioDeviceInfo.TYPE_USB_HEADSET } -> "headphones"
                outs.any { it == android.media.AudioDeviceInfo.TYPE_HDMI || it == android.media.AudioDeviceInfo.TYPE_USB_DEVICE } -> "external audio device"
                else -> "phone speaker"
            }
            lines += "audio output: $route" + if (am.isMusicActive) ", media playing" else ", nothing playing"
        }
        app.getSystemService(android.hardware.display.DisplayManager::class.java)?.let { dm ->
            val ext = dm.displays.count { it.displayId != android.view.Display.DEFAULT_DISPLAY && (it.flags and android.view.Display.FLAG_PRIVATE) == 0 }
            lines += "external displays: " + if (ext == 0) "none" else "$ext connected"
        }
        return lines
    }

    private fun network(): String {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = try {
            cm.getNetworkCapabilities(cm.activeNetwork)
        } catch (e: SecurityException) {
            return "unknown (no network-state permission)"
        } ?: return "offline"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        val extras = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("metered")
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("no internet")
        }
        return kind + if (extras.isEmpty()) "" else " (" + extras.joinToString(", ") + ")"
    }

    private fun sound(): String {
        val ringer = when (app.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "ring"
            else -> "unknown"
        }
        val dnd = app.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        val dndOn = dnd != null && dnd != NotificationManager.INTERRUPTION_FILTER_ALL &&
            dnd != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return ringer + if (dndOn) ", do not disturb on" else ""
    }

    private fun setClipboard() = SimpleTool(
        name = "set_clipboard",
        description = "Put text on the clipboard, e.g. to paste it with long_press on a field then tapping Paste.",
        risk = Risk.Act,
        parameters = schema {
            string("text", "Text to copy.", required = true)
        },
    ) { args, _ ->
        val text = args.string("text")
        withContext(Dispatchers.Main) {
            val cm = app.getSystemService(ClipboardManager::class.java)
                ?: return@withContext ToolResult.error("Clipboard service unavailable.")
            cm.setPrimaryClip(ClipData.newPlainText("Atom", text))
            ToolResult.ok("Copied ${text.length} chars to the clipboard.")
        }
    }

    private fun getClipboard() = SimpleTool(
        name = "get_clipboard",
        description = "Read the clipboard text. Android only allows this while Atom (or its keyboard) is in front; " +
            "otherwise the result says so.",
        risk = Risk.Read,
        parameters = schema(),
    ) { _, _ ->
        withContext(Dispatchers.Main) {
            val cm = app.getSystemService(ClipboardManager::class.java)
                ?: return@withContext ToolResult.error("Clipboard service unavailable.")
            val clip = try {
                cm.primaryClip
            } catch (e: SecurityException) {
                null
            }
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
            when {
                !text.isNullOrEmpty() -> ToolResult.ok(capText(text, 4_000))
                clip != null -> ToolResult.ok("The clipboard holds no text.")
                else -> ToolResult.error(
                    "The clipboard is empty or Android blocked reading it (apps in the background cannot read the " +
                        "clipboard). Workaround: long_press a text field, tap Paste, then read_screen.",
                )
            }
        }
    }
}
