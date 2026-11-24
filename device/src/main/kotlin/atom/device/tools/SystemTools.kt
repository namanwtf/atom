package atom.device.tools

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolResult
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class SystemTools(context: Context, private val shell: ShellExecutor) {
    private val app = context.applicationContext

    fun all(): List<Tool> = listOf(
        openApp(), listApps(), openUrl(), shellTool(), deviceStatus(), setClipboard(), getClipboard(),
    )

    /** Starting activities from the background is allowed for apps with a bound accessibility service. */
    private fun launcher(): Context = Accessibility.service ?: app

    private suspend fun launched(what: String, expectPackage: String?): ToolResult {
        val svc = Accessibility.service ?: return ToolResult.ok("$what. (Accessibility is off, so the result cannot be checked.)")
        return UiController.outcome(svc, what, expectPackage)
    }

    private fun openApp() = SimpleTool(
        name = "open_app",
        description = "Open an installed app by name (fuzzy, e.g. \"maps\", \"whats app\") or exact package name.",
        risk = Risk.Act,
        parameters = schema {
            string("app", "App name or package name.", required = true)
        },
    ) { args, _ ->
        val query = args.string("app")
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
        try {
            withContext(Dispatchers.Main) { launcher().startActivity(Apps.launchIntent(target)) }
        } catch (e: Exception) {
            return@SimpleTool ToolResult.error("Could not open ${best.app.label}: ${e.message}")
        }
        launched("Opened ${best.app.label} (${best.app.packageName})", best.app.packageName)
    }

    private fun listApps() = SimpleTool(
        name = "list_apps",
        description = "List installed launchable apps as label=package, one per line. filter narrows by name.",
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
        description = "Open a web link or app deep link (https://…, geo:, tel:, mailto:, app schemes) with its default handler.",
        risk = Risk.Act,
        parameters = schema {
            string("url", "The URL or deep link.", required = true)
        },
    ) { args, _ ->
        val raw = args.string("url").trim()
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)) raw else "https://$raw"
        val uri = Uri.parse(withScheme)
        val scheme = uri.scheme?.lowercase()
        if (scheme in setOf("file", "content", "javascript", "intent")) {
            return@SimpleTool ToolResult.error("Opening $scheme: links is not allowed.")
        }
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
            ShellTier.None -> "Currently unavailable (no root or Shizuku); calls explain how to enable it."
        }
        return SimpleTool(
            name = "shell",
            description = "Run a shell command on the phone. $where Each call is a fresh subshell (cd does not " +
                "persist). Returns exit code, stdout, stderr. Prefer UI tools for anything the user should see.",
            risk = Risk.Sensitive,
            parameters = schema {
                string("command", "The sh command line.", required = true)
                integer("timeout_s", "Seconds before the command is killed, 1-55. Default 30.")
            },
        ) { args, _ ->
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
        }
    }

    private fun deviceStatus() = SimpleTool(
        name = "device_status",
        description = "Battery, charging, thermal state, network, screen on/locked, ringer/Do Not Disturb, local time and timezone.",
        risk = Risk.Read,
        parameters = schema(),
    ) { _, _ ->
        val b = Battery.read(app)
        val power = app.getSystemService(PowerManager::class.java)
        val lines = mutableListOf<String>()
        lines += buildString {
            append("battery: ").append(if (b.percent >= 0) "${b.percent}%" else "unknown")
            append(if (b.charging) " charging" + (b.plugged?.let { " ($it)" } ?: "") else " not charging")
            b.temperatureC?.let { append(", ${it}°C") }
            if (power?.isPowerSaveMode == true) append(", battery saver on")
        }
        power?.let { lines += "thermal: " + PowerPolicy.thermalOf(it.currentThermalStatus).name.lowercase() }
        lines += "network: " + network()
        val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        lines += "screen: " + (if (power?.isInteractive == true) "on" else "off") + (if (locked) ", locked" else ", unlocked")
        lines += "sound: " + sound()
        val now = ZonedDateTime.now()
        lines += "time: " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEEE", Locale.US)) +
            " ${now.zone.id} (UTC${now.offset.id.replace("Z", "+00:00")})"
        ToolResult.ok(lines.joinToString("\n"))
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
