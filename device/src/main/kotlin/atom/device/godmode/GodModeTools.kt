package atom.device.godmode

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optDouble
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.ShellExecutor
import atom.device.apps.AppResolver
import atom.device.control.ControlError
import atom.device.control.DeviceEnv
import atom.device.control.SysSettings
import atom.device.control.shQuote
import atom.device.tools.SelfConfirmingTool
import atom.device.tools.SimpleTool
import atom.device.tools.approve
import atom.device.undo.Undo
import atom.device.undo.UndoResult
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The owner's power tools: per-app firewall, refresh-rate lock, charge limit, battery drain report, GPS spoofing. */
internal class GodModeTools(private val context: Context) {
    private val app = context.applicationContext
    private val firewall = Firewall(app)
    private val charge = ChargeLimitStore(app)

    fun privileged(): List<Tool> = listOf(firewallTool(), refreshRate(), chargeLimit(), batteryDrain())

    fun rootless(): List<Tool> = listOf(spoof())

    private fun env(ctx: ToolContext) = DeviceEnv(app) { ctx.confirm(it) }

    // ---- firewall ----------------------------------------------------------------------------------

    @Serializable
    private data class FwUndo(val packageName: String, val label: String, val scope: NetScope, val block: Boolean)

    private val scopes = mapOf("all" to NetScope.All, "mobile" to NetScope.Mobile, "wifi" to NetScope.Wifi, "background" to NetScope.Background)

    private fun firewallTool() = SelfConfirmingTool(
        name = "app_firewall",
        description = "Per-app network firewall. block/unblock an app on all networks, mobile data, Wi-Fi (root; kernel " +
            "rules, zero runtime cost), or background data on metered networks (Android's own restriction; Shizuku or " +
            "root). list shows current rules. Asks the user first; every change can be undone.",
        parameters = schema {
            string("action", "What to do.", required = true, enum = listOf("block", "unblock", "list"))
            string("app", "App name or package (block/unblock).")
            string("network", "Which traffic. Default all.", enum = scopes.keys.toList())
        },
    ) { args, ctx ->
        val env = env(ctx)
        when (args.string("action").lowercase()) {
            "list" -> listRules(env)
            "block", "unblock" -> {
                val block = args.string("action").lowercase() == "block"
                val scope = scopes[args.optString("network")?.lowercase() ?: "all"]
                    ?: throw ToolArgumentException("network must be one of ${scopes.keys.joinToString()}.")
                val r = AppResolver.resolve(app, args.optString("app") ?: throw ToolArgumentException("Missing app."))
                if (r.packageName == app.packageName) throw ControlError("Atom will not firewall itself.")
                val sh = if (scope == NetScope.Background) env.requirePrivileged("Restricting background data") else env.requireRoot("The app firewall")
                changeRule(ctx, sh, FirewallRule(r.packageName, r.label, scope), block, ask = true)
            }
            else -> throw ToolArgumentException("action must be block, unblock or list.")
        }
    }

    private suspend fun changeRule(ctx: ToolContext?, sh: ShellExecutor, rule: FirewallRule, block: Boolean, ask: Boolean): ToolResult {
        val uid = firewall.uidOf(rule.packageName) ?: return ToolResult.error("${rule.label} is not installed.")
        val exists = firewall.rules().any { it.packageName == rule.packageName && it.scope == rule.scope }
        if (block == exists) {
            return ToolResult.ok("${rule.label}: ${rule.scope.label} already ${if (block) "blocked" else "allowed"}; nothing changed.")
        }
        if (ask && ctx != null) {
            val how = if (rule.scope == NetScope.Background) "Android's background-data restriction" else "root firewall rules (iptables, IPv4 and IPv6)"
            ctx.approve(
                if (block) "Block ${rule.label} (uid $uid) from ${rule.scope.label} using $how. It stays blocked after restarts until you unblock it; undo is one tap in the change history."
                else "Allow ${rule.label} (uid $uid) to use ${rule.scope.label} again.",
            )?.let { return it }
        }
        firewall.change(sh, rule, block)?.let { return ToolResult.error(it) }
        val verified = if (rule.scope == NetScope.Background) {
            firewall.backgroundRestricted(sh, uid) == block
        } else {
            (uid in firewall.liveBlocked(sh)) == block
        }
        val before = if (block) "allowed" else "blocked"
        val after = if (block) "blocked" else "allowed"
        Undo.journal(app).record(
            tool = "app_firewall", label = "${rule.label} ${rule.scope.label}", before = before, after = after, kind = "firewall",
            undoData = Json.encodeToString(FwUndo.serializer(), FwUndo(rule.packageName, rule.label, rule.scope, !block)), verified = verified,
        )
        return ToolResult.ok("${rule.label} ${rule.scope.label}: $before → $after (${if (verified) "verified" else "unconfirmed"}).")
    }

    private suspend fun listRules(env: DeviceEnv): ToolResult {
        val rules = firewall.rules()
        if (rules.isEmpty()) return ToolResult.ok("No firewall rules. Every app may use every network.")
        val live = env.root()?.let { firewall.liveBlocked(it) }
        return ToolResult.ok(
            rules.joinToString("\n") { r ->
                val uid = firewall.uidOf(r.packageName)
                val state = when {
                    r.scope == NetScope.Background -> ""
                    live == null -> " (root unavailable: rule saved but not active)"
                    uid != null && uid in live -> " (active)"
                    else -> " (saved, not active: re-apply needed)"
                }
                "${r.label} (${r.packageName}): ${r.scope.label} blocked$state"
            },
        )
    }

    // ---- refresh rate ------------------------------------------------------------------------------

    @Serializable
    private data class RateUndo(val peak: String?, val min: String?)

    private fun supportedRates(): List<Float> {
        val d = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        val mode = d.mode
        return d.supportedModes.filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .map { it.refreshRate }.distinctBy { it.roundToInt() }.sorted()
    }

    private fun currentRate(): Float? =
        app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate

    private fun refreshRate() = SelfConfirmingTool(
        name = "refresh_rate",
        description = "Lock the screen refresh rate to one supported value (e.g. 60 to save battery, 120 for smoothness), " +
            "or 'auto' to give control back to the system. 'status' lists supported rates. Shizuku or root.",
        parameters = schema {
            string("action", "lock, auto or status.", required = true, enum = listOf("lock", "auto", "status"))
            integer("hz", "Rate to lock to (for lock).")
        },
    ) { args, ctx ->
        val env = env(ctx)
        val rates = supportedRates()
        val ratesText = rates.joinToString(", ") { "${it.roundToInt()} Hz" }
        val peak = SysSettings.get(env, SysSettings.Ns.System, "peak_refresh_rate")
        val min = SysSettings.get(env, SysSettings.Ns.System, "min_refresh_rate")
        fun state(p: String?, m: String?): String {
            val pf = p?.toFloatOrNull()
            val mf = m?.toFloatOrNull()
            return when {
                pf != null && mf != null && abs(pf - mf) < 0.5f && pf < 1000f -> "locked ${pf.roundToInt()} Hz"
                else -> "auto" + (pf?.takeIf { it < 1000f }?.let { " (max ${it.roundToInt()} Hz)" }.orEmpty())
            }
        }
        val now = currentRate()?.let { " Screen is running at ${it.roundToInt()} Hz right now." }.orEmpty()
        when (args.string("action").lowercase()) {
            "status" -> ToolResult.ok("Refresh rate: ${state(peak, min)}. Supported: $ratesText.$now")
            "lock" -> {
                val hz = args.optInt("hz") ?: throw ToolArgumentException("Missing hz.")
                val match = rates.firstOrNull { abs(it - hz) < 1.5f }
                    ?: return@SelfConfirmingTool ToolResult.error("$hz Hz is not supported here. Supported: $ratesText.")
                val sh = env.requirePrivileged("Locking the refresh rate")
                ctx.approve("Lock the screen to ${match.roundToInt()} Hz using ${env.tierName(sh)} (currently ${state(peak, min)}). Undo returns it to how it was.")
                    ?.let { return@SelfConfirmingTool it }
                val v = String.format(Locale.US, "%.1f", match)
                SysSettings.put(env, SysSettings.Ns.System, "min_refresh_rate", v)
                SysSettings.put(env, SysSettings.Ns.System, "peak_refresh_rate", v)
                val after = state(SysSettings.get(env, SysSettings.Ns.System, "peak_refresh_rate"), SysSettings.get(env, SysSettings.Ns.System, "min_refresh_rate"))
                val ok = after == "locked ${match.roundToInt()} Hz"
                Undo.journal(app).record(
                    "refresh_rate", "Refresh rate", state(peak, min), after, "refresh",
                    Json.encodeToString(RateUndo.serializer(), RateUndo(peak, min)), ok,
                )
                delay(400)
                ToolResult.ok(
                    "Refresh rate: ${state(peak, min)} → $after (${if (ok) "verified" else "unconfirmed"}).${currentRate()?.let { " Screen now at ${it.roundToInt()} Hz; some ROMs override this from their own display settings." }.orEmpty()}",
                )
            }
            "auto" -> {
                val sh = env.requirePrivileged("Changing the refresh rate")
                if (state(peak, min).startsWith("auto")) return@SelfConfirmingTool ToolResult.ok("Refresh rate: already auto; nothing changed.$now")
                ctx.approve("Return refresh-rate control to the system using ${env.tierName(sh)}.")?.let { return@SelfConfirmingTool it }
                val top = rates.maxOrNull()?.let { String.format(Locale.US, "%.1f", it) }
                SysSettings.put(env, SysSettings.Ns.System, "min_refresh_rate", "0.0")
                if (top != null) SysSettings.put(env, SysSettings.Ns.System, "peak_refresh_rate", top)
                val after = state(SysSettings.get(env, SysSettings.Ns.System, "peak_refresh_rate"), SysSettings.get(env, SysSettings.Ns.System, "min_refresh_rate"))
                Undo.journal(app).record(
                    "refresh_rate", "Refresh rate", state(peak, min), after, "refresh",
                    Json.encodeToString(RateUndo.serializer(), RateUndo(peak, min)), after.startsWith("auto"),
                )
                ToolResult.ok("Refresh rate: ${state(peak, min)} → $after (${if (after.startsWith("auto")) "verified" else "unconfirmed"}).")
            }
            else -> throw ToolArgumentException("action must be lock, auto or status.")
        }
    }

    // ---- charge limit ------------------------------------------------------------------------------

    private fun chargeLimit() = SelfConfirmingTool(
        name = "charge_limit",
        description = "Battery charge control (root). status: what this phone supports and the current limit. set: stop " +
            "charging at a percentage (needs a kernel charge-threshold node). off: charge to 100%. pause/resume: switch " +
            "charging off or on now.",
        parameters = schema {
            string("action", "What to do.", required = true, enum = listOf("status", "set", "off", "pause", "resume"))
            integer("percent", "Stop level for set, 50-95.")
        },
    ) { args, ctx ->
        val env = env(ctx)
        val sh = env.requireRoot("Charge control")
        val support = charge.detect(sh)
        if (!support.supported) {
            return@SelfConfirmingTool ToolResult.ok(
                "Charge control is not supported on this phone: none of the known kernel charge-control nodes exist " +
                    "(${ChargeNodes.known.size} checked). The phone's own battery-protection setting, if it has one, is the only option.",
            )
        }
        val t = support.threshold
        val p = support.pause
        when (args.string("action").lowercase()) {
            "status" -> {
                val level = t?.let { charge.readLevel(sh, it) }
                ToolResult.ok(
                    buildString {
                        if (t != null) append("Charge limit: ${if (level == null || level >= 100) "off (charges to 100%)" else "stops at $level%"} (${t.vendor} node).")
                        else append("This phone has no percentage limit node; Atom can only pause and resume charging.")
                        if (p != null) append(" Pause/resume supported (${p.vendor}).")
                    },
                )
            }
            "set", "off" -> {
                val node = t ?: return@SelfConfirmingTool ToolResult.error(
                    "This phone only exposes a charging on/off switch, not a percentage limit, so a limit would need Atom to watch the battery constantly. Use pause/resume instead.",
                )
                val pct = if (args.string("action").lowercase() == "off") 100 else (args.optInt("percent") ?: throw ToolArgumentException("Missing percent.")).coerceIn(50, 95)
                val before = charge.readLevel(sh, node)
                fun show(v: Int?) = when { v == null -> "unknown"; v >= 100 -> "off (100%)"; else -> "$v%" }
                if (before == pct) return@SelfConfirmingTool ToolResult.ok("Charge limit: already ${show(pct)}; nothing changed.")
                ctx.approve(
                    if (pct >= 100) "Remove the charge limit so the battery charges to 100% (root)."
                    else "Stop charging at $pct% (root, ${node.vendor} kernel node; the kernel enforces it, no app running). Atom re-applies it after restarts until you turn it off.",
                )?.let { return@SelfConfirmingTool it }
                ChargeNodes.limitCommands(node, pct).forEach { sh.run(it, 5_000) }
                val after = charge.readLevel(sh, node)
                charge.limit = pct.takeIf { it < 100 }
                Undo.journal(app).record("charge_limit", "Charge limit", show(before), show(after), "charge", (before ?: 100).toString(), after == pct)
                ToolResult.ok("Charge limit: ${show(before)} → ${show(after)} (${if (after == pct) "verified" else "unconfirmed"}).")
            }
            "pause", "resume" -> {
                val node = p ?: return@SelfConfirmingTool ToolResult.error("This phone has no charging on/off switch Atom knows.")
                val pause = args.string("action").lowercase() == "pause"
                ctx.approve(if (pause) "Pause charging now (root). It stays paused until you resume or restart." else "Resume charging (root).")
                    ?.let { return@SelfConfirmingTool it }
                val r = sh.run("echo ${shQuote(if (pause) node.pauseValue else node.resumeValue)} > ${shQuote(node.path)}", 5_000)
                if (!r.ok) return@SelfConfirmingTool ToolResult.error("Writing ${node.path} failed: ${r.stderr.take(200)}")
                Undo.journal(app).record(
                    "charge_limit", "Charging", if (pause) "on" else "paused", if (pause) "paused" else "on", "charge_pause",
                    if (pause) "resume" else "pause",
                )
                ToolResult.ok("Charging: ${if (pause) "on → paused" else "paused → on"} (written to ${node.vendor} node; check the charging icon to confirm).")
            }
            else -> throw ToolArgumentException("action must be status, set, off, pause or resume.")
        }
    }

    // ---- battery drain -----------------------------------------------------------------------------

    private fun batteryDrain() = SimpleTool(
        name = "battery_drain",
        description = "What drained the battery since the last full charge, from Android's own battery stats: top apps " +
            "and hardware by mAh. Offer force_stop_app for a runaway app (ask the user first). Shizuku or root.",
        risk = Risk.Read,
        parameters = schema { integer("top", "How many entries, default 10.") },
    ) { args, ctx ->
        val sh = env(ctx).requirePrivileged("Reading battery stats")
        val top = (args.optInt("top") ?: 10).coerceIn(3, 30)
        var items = BatteryDrain.parseCheckin(sh.run(BatteryDrain.CHECKIN_COMMAND, 45_000).stdout)
        if (items.isEmpty()) items = BatteryDrain.parseText(sh.run(BatteryDrain.TEXT_COMMAND, 45_000).stdout)
        if (items.isEmpty()) return@SimpleTool ToolResult.error("Android returned no battery estimates (stats may have just been reset by a full charge).")
        val pm = app.packageManager
        val total = items.sumOf { it.mah }
        val lines = items.take(top).map { item ->
            val name = item.uid?.let { uid ->
                val pkgs = pm.getPackagesForUid(uid).orEmpty()
                val label = pkgs.firstOrNull()?.let { p -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrNull() }
                when {
                    label != null -> "$label (${pkgs.first()})"
                    uid < 10_000 -> "Android system (uid $uid)"
                    else -> "uid $uid"
                }
            } ?: item.label
            String.format(Locale.US, "%-4s %s: %.0f mAh", "${(item.mah * 100 / total).roundToInt()}%", name, item.mah)
        }
        ToolResult.ok("Battery use since last full charge (estimated by Android):\n" + lines.joinToString("\n"))
    }

    // ---- GPS spoofing ------------------------------------------------------------------------------

    private fun spoof() = SelfConfirmingTool(
        name = "spoof_location",
        description = "GPS spoofing (explicit opt-in): every app sees the given coordinates while it is on; stop ends it " +
            "and restores real GPS. Not per-app (that needs LSPosed). Games ban mock locations. status shows state.",
        parameters = schema {
            string("action", "start, stop or status.", required = true, enum = listOf("start", "stop", "status"))
            number("latitude", "For start.")
            number("longitude", "For start.")
            number("accuracy_m", "Reported accuracy in metres, default 5.")
        },
    ) { args, ctx ->
        val env = env(ctx)
        when (args.string("action").lowercase()) {
            "status" -> {
                val s = LocationSpoof.state.value
                ToolResult.ok(if (s.active) String.format(Locale.US, "Spoofing is on: %.5f, %.5f.", s.lat, s.lon) else "Spoofing is off; apps see the real location.")
            }
            "stop" -> {
                if (!LocationSpoof.state.value.active) return@SelfConfirmingTool ToolResult.ok("Spoofing was already off.")
                LocationSpoof.stop(app)
                ToolResult.ok("Location spoofing: on → off. Apps get real GPS again (some cache the last fix for a minute).")
            }
            "start" -> {
                val lat = args.optDouble("latitude") ?: throw ToolArgumentException("Missing latitude.")
                val lon = args.optDouble("longitude") ?: throw ToolArgumentException("Missing longitude.")
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw ToolArgumentException("Coordinates out of range.")
                if (!LocationSpoof.isMockApp(app)) {
                    val sh = env.privileged()
                        ?: return@SelfConfirmingTool ToolResult.error(
                            "Atom is not the mock location app. The user must enable Developer options, then Select mock location app → Atom, and try again.",
                        )
                    ctx.approve("Make Atom the system's mock location app using ${env.tierName(sh)} (needed for spoofing; reversible in Developer options).")
                        ?.let { return@SelfConfirmingTool it }
                    sh.run("appops set ${app.packageName} android:mock_location allow", 10_000)
                    if (!LocationSpoof.isMockApp(app)) return@SelfConfirmingTool ToolResult.error("Could not make Atom the mock location app; set it in Developer options.")
                }
                ctx.approve(String.format(Locale.US, "Spoof the location of every app to %.5f, %.5f until you stop it. %s", lat, lon, LocationSpoof.WARNING))
                    ?.let { return@SelfConfirmingTool it }
                try {
                    LocationSpoof.start(app, lat, lon, (args.optDouble("accuracy_m") ?: 5.0).toFloat().coerceIn(1f, 500f))
                } catch (e: Exception) {
                    return@SelfConfirmingTool ToolResult.error("Android would not start the spoofing service: ${e.message}")
                }
                var waited = 0
                while (!LocationSpoof.state.value.active && LocationSpoof.state.value.error == null && waited < 3_000) {
                    delay(100)
                    waited += 100
                }
                val s = LocationSpoof.state.value
                when {
                    s.active -> ToolResult.ok(String.format(Locale.US, "Location spoofing: off → on at %.5f, %.5f (verified: test providers active). Stop it with spoof_location stop or from the notification.", lat, lon))
                    s.error != null -> ToolResult.error(s.error)
                    else -> ToolResult.ok("Spoofing requested, unconfirmed (the service has not reported in yet).")
                }
            }
            else -> throw ToolArgumentException("action must be start, stop or status.")
        }
    }

    companion object {
        fun installRestorers(context: Context) {
            val app = context.applicationContext
            val firewall = Firewall(app)
            val charge = ChargeLimitStore(app)
            Undo.register("firewall") { e ->
                val u = Json.decodeFromString(FwUndo.serializer(), e.undoData!!)
                val env = DeviceEnv(app)
                val sh = (if (u.scope == NetScope.Background) env.privileged() else env.root())
                    ?: return@register UndoResult(false, "Needs root to undo.")
                val err = firewall.change(sh, FirewallRule(u.packageName, u.label, u.scope), u.block)
                UndoResult(err == null, err ?: "${u.label} ${u.scope.label}: ${if (u.block) "blocked again" else "allowed again"}.")
            }
            Undo.register("refresh") { e ->
                val u = Json.decodeFromString(RateUndo.serializer(), e.undoData!!)
                val env = DeviceEnv(app)
                try {
                    if (u.min == null) SysSettings.delete(env, SysSettings.Ns.System, "min_refresh_rate") else SysSettings.put(env, SysSettings.Ns.System, "min_refresh_rate", u.min)
                    if (u.peak == null) SysSettings.delete(env, SysSettings.Ns.System, "peak_refresh_rate") else SysSettings.put(env, SysSettings.Ns.System, "peak_refresh_rate", u.peak)
                    UndoResult(true, "Refresh rate restored.")
                } catch (ex: ControlError) {
                    UndoResult(false, ex.message ?: "Undo failed.")
                }
            }
            Undo.register("charge") { e ->
                val sh = DeviceEnv(app).root() ?: return@register UndoResult(false, "Needs root to undo.")
                val node = charge.detect(sh).threshold ?: return@register UndoResult(false, "No charge-limit node.")
                val pct = e.undoData!!.toIntOrNull() ?: 100
                ChargeNodes.limitCommands(node, pct).forEach { sh.run(it, 5_000) }
                charge.limit = pct.takeIf { it < 100 }
                UndoResult(true, "Charge limit restored to ${if (pct >= 100) "off" else "$pct%"}.")
            }
            Undo.register("charge_pause") { e ->
                val sh = DeviceEnv(app).root() ?: return@register UndoResult(false, "Needs root to undo.")
                val node = charge.detect(sh).pause ?: return@register UndoResult(false, "No charging switch.")
                val pause = e.undoData == "pause"
                val r = sh.run("echo ${shQuote(if (pause) node.pauseValue else node.resumeValue)} > ${shQuote(node.path)}", 5_000)
                UndoResult(r.ok, if (pause) "Charging paused again." else "Charging resumed.")
            }
        }

        /** Re-applies kernel state that resets on reboot (firewall chain, charge limit). Called once from the boot receiver. */
        suspend fun reapplyAfterBoot(context: Context) {
            val app = context.applicationContext
            val firewall = Firewall(app)
            val charge = ChargeLimitStore(app)
            val hasRules = firewall.rules().any { it.scope != NetScope.Background }
            val limit = charge.limit
            if (!hasRules && limit == null) return
            val sh = DeviceEnv(app).root() ?: return
            if (hasRules) firewall.apply(sh)
            if (limit != null) charge.detect(sh).threshold?.let { node -> ChargeNodes.limitCommands(node, limit).forEach { sh.run(it, 5_000) } }
        }
    }
}
