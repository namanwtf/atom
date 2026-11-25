package atom.device.system

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.apps.AppResolver
import atom.device.control.ControlError
import atom.device.control.DeviceEnv
import atom.device.control.SysSettings
import atom.device.control.shQuote
import atom.device.tools.SelfConfirmingTool
import atom.device.tools.SimpleTool
import atom.device.tools.approve
import atom.device.tools.capText
import atom.device.undo.Undo
import atom.device.undo.UndoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** App freezing, force-stop, protected settings keys, system logs and app inspection. */
internal class SystemControlTools(private val context: Context) {
    private val app = context.applicationContext

    /** Tools that work with standard permissions. */
    fun rootless(): List<Tool> = listOf(inspectApp())

    /** Tools that need Shizuku or root; hidden when neither is available. */
    fun privileged(): List<Tool> = listOf(freeze(true), freeze(false), forceStop(), readSetting(), writeSetting(), logs())

    private fun env(ctx: ToolContext) = DeviceEnv(app) { ctx.confirm(it) }

    private fun pm() = app.packageManager

    private fun enabledState(pkg: String): Boolean? = runCatching {
        when (pm().getApplicationEnabledSetting(pkg)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> pm().getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            else -> true
        }
    }.getOrNull()

    private fun freeze(freeze: Boolean) = SelfConfirmingTool(
        name = if (freeze) "freeze_app" else "unfreeze_app",
        description = if (freeze) {
            "Freeze (disable) an app so it cannot run, show notifications or use data until unfrozen. Needs Shizuku or root. " +
                "Asks the user first. Undo with unfreeze_app or undo_last_change."
        } else {
            "Unfreeze (re-enable) a frozen app. Needs Shizuku or root."
        },
        parameters = schema { string("app", "App name or package.", required = true) },
    ) { args, ctx ->
        val env = env(ctx)
        val r = AppResolver.resolve(app, args.string("app"))
        if (freeze) AppResolver.requireSafeToStop(app, r.packageName)
        val sh = env.requirePrivileged(if (freeze) "Freezing apps" else "Unfreezing apps")
        val before = enabledState(r.packageName)
        if (before == !freeze) return@SelfConfirmingTool ToolResult.ok("${r.label}: already ${if (freeze) "frozen" else "enabled"}; nothing changed.")
        val preview = if (freeze) {
            "Freeze ${r.label} (${r.packageName}) using ${env.tierName(sh)}: it stops running and sends no notifications until unfrozen. One-tap undo is in the change history."
        } else {
            "Unfreeze ${r.label} (${r.packageName}) using ${env.tierName(sh)} so it can run again."
        }
        ctx.approve(preview)?.let { return@SelfConfirmingTool it }
        val cmd = if (freeze) "pm disable-user --user 0 ${shQuote(r.packageName)}" else "pm enable --user 0 ${shQuote(r.packageName)}"
        val res = sh.run(cmd, 20_000)
        val after = enabledState(r.packageName)
        val label = "${r.label} (${r.packageName})"
        fun show(v: Boolean?) = when (v) { true -> "enabled"; false -> "frozen"; null -> "unknown" }
        if (after == !freeze) {
            Undo.journal(app).record(
                tool = if (freeze) "freeze_app" else "unfreeze_app",
                label = label, before = show(before), after = show(after),
                kind = "app:enabled", undoData = "${r.packageName}|${before != false}",
            )
            ToolResult.ok("$label: ${show(before)} → ${show(after)} (verified via ${env.tierName(sh)}).")
        } else {
            ToolResult(
                "$label: ${show(before)} → ${if (freeze) "frozen" else "enabled"} requested, unconfirmed (it still reads ${show(after)}). " +
                    (res.stderr.ifBlank { res.stdout }).take(200),
                isError = !res.ok,
            )
        }
    }

    private fun forceStop() = SelfConfirmingTool(
        name = "force_stop_app",
        description = "Force-stop an app (ends its processes, alarms and jobs until it is opened again). Needs Shizuku or root. Asks first.",
        parameters = schema { string("app", "App name or package.", required = true) },
    ) { args, ctx ->
        val env = env(ctx)
        val r = AppResolver.resolve(app, args.string("app"))
        AppResolver.requireSafeToStop(app, r.packageName)
        val sh = env.requirePrivileged("Force-stopping apps")
        ctx.approve("Force-stop ${r.label} (${r.packageName}) using ${env.tierName(sh)}. Unsaved work in it may be lost; it starts again when opened.")
            ?.let { return@SelfConfirmingTool it }
        val wasStopped = stopped(r.packageName)
        val res = sh.run("am force-stop ${shQuote(r.packageName)}", 15_000)
        delay(300)
        val now = stopped(r.packageName)
        Undo.journal(app).record(
            tool = "force_stop_app", label = "${r.label} (${r.packageName})",
            before = if (wasStopped == true) "stopped" else "running", after = if (now == true) "stopped" else "unknown",
            kind = "app:stop", undoData = null, verified = now == true,
        )
        when {
            !res.ok -> ToolResult.error("Force-stop failed: ${(res.stderr.ifBlank { res.stdout }).take(200)}")
            now == true -> ToolResult.ok("${r.label}: ${if (wasStopped == true) "stopped" else "running"} → stopped (verified via ${env.tierName(sh)}).")
            else -> ToolResult.ok("${r.label}: force-stop sent via ${env.tierName(sh)}, unconfirmed (Android did not report it as stopped).")
        }
    }

    private fun stopped(pkg: String): Boolean? = runCatching {
        (pm().getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS).flags and ApplicationInfo.FLAG_STOPPED) != 0
    }.getOrNull()

    private fun inspectApp() = SimpleTool(
        name = "inspect_app",
        description = "Details of an installed app: version, UID, installer, install path, enabled/frozen and stopped " +
            "state, install and update time, and its permissions with granted state.",
        risk = Risk.Read,
        parameters = schema {
            string("app", "App name or package.", required = true)
            boolean("all_permissions", "List every requested permission, not only granted dangerous ones. Default false.")
        },
    ) { args, _ ->
        val r = AppResolver.resolve(app, args.string("app"))
        val info = withContext(Dispatchers.IO) {
            pm().getPackageInfo(r.packageName, PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS)
        }
        ToolResult.ok(describe(r.label, info, args.optBoolean("all_permissions") == true))
    }

    @Suppress("DEPRECATION")
    private fun describe(label: String, p: PackageInfo, allPerms: Boolean): String {
        val a = p.applicationInfo
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val installer = runCatching {
            val s = pm().getInstallSourceInfo(p.packageName)
            s.installingPackageName ?: s.initiatingPackageName
        }.getOrNull() ?: "unknown (sideloaded or preinstalled)"
        val lines = mutableListOf(
            "$label (${p.packageName})",
            "version: ${p.versionName ?: "?"} (code ${p.longVersionCode})",
            "uid: ${a?.uid}",
            "installer: $installer",
            "path: ${a?.sourceDir}" + (a?.splitSourceDirs?.size?.let { " (+$it splits)" } ?: ""),
            "data dir: ${a?.dataDir}",
            "state: " + listOfNotNull(
                if (enabledState(p.packageName) == false) "frozen/disabled" else "enabled",
                if (a != null && a.flags and ApplicationInfo.FLAG_STOPPED != 0) "stopped" else "not stopped",
                if (a != null && a.flags and ApplicationInfo.FLAG_SYSTEM != 0) "system app" else "user app",
                if (a != null && a.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debuggable" else null,
            ).joinToString(", "),
            "target SDK: ${a?.targetSdkVersion}, min SDK: ${a?.minSdkVersion}",
            "installed: ${fmt.format(Instant.ofEpochMilli(p.firstInstallTime))}, updated: ${fmt.format(Instant.ofEpochMilli(p.lastUpdateTime))}",
        )
        val perms = p.requestedPermissions.orEmpty().mapIndexed { i, name ->
            val granted = (p.requestedPermissionsFlags?.getOrNull(i) ?: 0) and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            Triple(name, granted, isDangerous(name))
        }
        val shown = if (allPerms) perms else perms.filter { it.second && it.third }
        lines += "permissions (${perms.size} requested, ${perms.count { it.second }} granted" + (if (allPerms) "" else "; showing granted runtime ones") + "):"
        shown.forEach { (n, g, _) -> lines += "  ${n.removePrefix("android.permission.")}${if (allPerms) if (g) " ✓" else " ✗" else ""}" }
        val ops = app.getSystemService(AppOpsManager::class.java)
        if (ops != null && a != null) {
            val special = listOf(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW to "draw over apps",
                AppOpsManager.OPSTR_GET_USAGE_STATS to "usage access",
            ).mapNotNull { (op, name) ->
                runCatching { if (ops.unsafeCheckOpNoThrow(op, a.uid, p.packageName) == AppOpsManager.MODE_ALLOWED) name else null }.getOrNull()
            }
            if (special.isNotEmpty()) lines += "special access: " + special.joinToString(", ")
        }
        return capText(lines.joinToString("\n"), 6_000)
    }

    private fun isDangerous(name: String): Boolean = runCatching {
        val pi = pm().getPermissionInfo(name, 0)
        pi.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    private val namespaces = listOf("system", "secure", "global")

    private fun ns(name: String) = when (name.lowercase()) {
        "system" -> SysSettings.Ns.System
        "secure" -> SysSettings.Ns.Secure
        "global" -> SysSettings.Ns.Global
        else -> throw ToolArgumentException("namespace must be system, secure or global.")
    }

    private fun readSetting() = SimpleTool(
        name = "read_setting",
        description = "Read Android settings keys (system, secure, global). Give a key, or a filter to list matching keys with values.",
        risk = Risk.Read,
        parameters = schema {
            string("namespace", "Settings table.", required = true, enum = namespaces)
            string("key", "Exact key to read.")
            string("filter", "List keys containing this text instead.")
        },
    ) { args, ctx ->
        val env = env(ctx)
        val n = ns(args.string("namespace"))
        val key = args.optString("key")
        if (key != null) {
            val v = SysSettings.get(env, n, key)
            return@SimpleTool ToolResult.sensitive("${n.cmd}/$key = ${v ?: "(not set)"}")
        }
        val sh = env.requirePrivileged("Listing settings")
        val filter = args.optString("filter").orEmpty()
        val r = sh.run("settings list ${n.cmd}" + if (filter.isNotBlank()) " | grep -i -F -e ${shQuote(filter)}" else "", 15_000)
        ToolResult(capText(r.stdout.ifBlank { "(no keys match)" }, 5_000), sensitive = true)
    }

    @Serializable
    private data class SettingUndo(val ns: String, val key: String, val before: String?)

    private fun writeSetting() = SelfConfirmingTool(
        name = "write_setting",
        description = "Write (or delete) a protected Android settings key with Shizuku or root, after a plain-English " +
            "preview the user approves. The old value goes into the undo journal.",
        parameters = schema {
            string("namespace", "Settings table.", required = true, enum = namespaces)
            string("key", "Key to change.", required = true)
            string("value", "New value. Omit with delete=true to remove the key.")
            boolean("delete", "Delete the key instead of writing it.")
            string("why", "One short sentence on what this does, shown to the user.")
        },
    ) { args, ctx ->
        val env = env(ctx)
        val n = ns(args.string("namespace"))
        val key = args.string("key")
        val delete = args.optBoolean("delete") == true
        val value = args.optString("value")
        if (!delete && value == null) throw ToolArgumentException("Give value, or delete=true.")
        val sh = env.requirePrivileged("Changing protected settings")
        val before = SysSettings.get(env, n, key)
        if (!delete && before == value) return@SelfConfirmingTool ToolResult.ok("${n.cmd}/$key: already $value; nothing changed.")
        val why = args.optString("why")?.let { " ($it)" }.orEmpty()
        val preview = if (delete) {
            "Delete the ${n.cmd} setting \"$key\" (now ${before ?: "not set"})$why using ${env.tierName(sh)}. Undo restores it."
        } else {
            "Change the ${n.cmd} setting \"$key\" from ${before ?: "not set"} to $value$why using ${env.tierName(sh)}. Undo restores it."
        }
        ctx.approve(preview)?.let { return@SelfConfirmingTool it }
        if (delete) SysSettings.delete(env, n, key) else SysSettings.put(env, n, key, value!!)
        val after = SysSettings.get(env, n, key)
        val ok = if (delete) after == null else after == value
        Undo.journal(app).record(
            tool = "write_setting", label = "${n.cmd}/$key", before = before ?: "not set", after = after ?: "not set",
            kind = "setting", undoData = Json.encodeToString(SettingUndo.serializer(), SettingUndo(n.cmd, key, before)), verified = ok,
        )
        ToolResult(
            "${n.cmd}/$key: ${before ?: "not set"} → ${after ?: "not set"} " + if (ok) "(verified via ${env.tierName(sh)})" else "(unconfirmed)",
            sensitive = true,
        )
    }

    private fun logs() = SimpleTool(
        name = "read_system_logs",
        description = "Read the system log (logcat) with filters: process name or pid, tag, minimum level, how many " +
            "minutes back, and text. Needs Shizuku or root to see other apps. Returns at most 300 lines.",
        risk = Risk.Read,
        parameters = schema {
            string("process", "Process or package name, or a pid.")
            string("tag", "Log tag.")
            string("level", "Minimum level.", enum = listOf("verbose", "debug", "info", "warn", "error", "fatal"))
            integer("minutes", "Only the last N minutes. Default 10.")
            string("text", "Only lines containing this text (case-insensitive).")
            integer("lines", "Max lines, default 150, max 300.")
        },
    ) { args, ctx ->
        val env = env(ctx)
        val sh = env.privileged() ?: env.shell()
        val proc = args.optString("process")?.trim()?.takeIf { it.isNotEmpty() }
        val pid = proc?.toIntOrNull() ?: proc?.let {
            sh.run("pidof ${shQuote(it)}", 5_000).stdout.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                ?: return@SimpleTool ToolResult.error("No running process named $it.")
        }
        val minutes = (args.optInt("minutes") ?: 10).coerceIn(1, 24 * 60)
        val q = LogQuery(
            pid = pid,
            tag = args.optString("tag")?.takeIf { it.isNotBlank() },
            level = LogQuery.level(args.optString("level")),
            sinceEpochMs = System.currentTimeMillis() - minutes * 60_000L,
            text = args.optString("text"),
            maxLines = (args.optInt("lines") ?: 150).coerceIn(1, 300),
        )
        val r = sh.run(q.command(), 30_000)
        val note = if (sh.tier == atom.device.ShellTier.App) "\n(Without Shizuku or root only Atom's own log lines are visible.)" else ""
        ToolResult(capText(r.stdout.ifBlank { "(no matching log lines)" }, 12_000) + note, sensitive = true)
    }

    companion object {
        fun installRestorers(context: Context) {
            val app = context.applicationContext
            Undo.register("app:enabled") { e ->
                val (pkg, enabled) = e.undoData!!.split('|').let { it[0] to it[1].toBoolean() }
                val sh = DeviceEnv(app).privileged() ?: return@register UndoResult(false, "Needs Shizuku or root to undo.")
                val r = sh.run(if (enabled) "pm enable --user 0 ${shQuote(pkg)}" else "pm disable-user --user 0 ${shQuote(pkg)}", 20_000)
                UndoResult(r.ok, if (r.ok) "${e.label}: restored to ${if (enabled) "enabled" else "frozen"}." else r.stderr.take(200))
            }
            Undo.register("setting") { e ->
                val u = Json.decodeFromString(SettingUndo.serializer(), e.undoData!!)
                val env = DeviceEnv(app)
                val n = when (u.ns) { "system" -> SysSettings.Ns.System; "secure" -> SysSettings.Ns.Secure; else -> SysSettings.Ns.Global }
                try {
                    if (u.before == null) SysSettings.delete(env, n, u.key) else SysSettings.put(env, n, u.key, u.before)
                    UndoResult(true, "${u.ns}/${u.key} restored to ${u.before ?: "not set"}.")
                } catch (ex: ControlError) {
                    UndoResult(false, ex.message ?: "Undo failed.")
                }
            }
        }
    }
}

