package atom.device.control

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import atom.core.tool.ToolResult
import atom.device.Accessibility
import atom.device.ShellExecutor
import atom.device.ShellResult
import atom.device.ShellTier
import atom.device.Shells
import atom.device.shell.ShellFraming
import atom.device.undo.Restorer
import atom.device.undo.Undo
import atom.device.undo.UndoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Thrown by a control or helper with a message meant for the model and the user. */
class ControlError(message: String) : Exception(message)

/** Everything a device tool needs at call time: context, the shared shell, and the user's approval channel. */
class DeviceEnv(context: Context, val confirm: suspend (String) -> Boolean = { false }) {
    val app: Context = context.applicationContext

    suspend fun shell(): ShellExecutor = Shells.detect(app)

    /** The Shizuku or root shell, or null on a rootless phone without Shizuku. */
    suspend fun privileged(): ShellExecutor? = shell().takeIf { it.tier != ShellTier.App }

    suspend fun root(): ShellExecutor? = shell().takeIf { it.tier == ShellTier.Root }

    suspend fun requirePrivileged(what: String): ShellExecutor = privileged()
        ?: throw ControlError("$what needs Shizuku or root, and this phone has neither enabled for Atom (see Permissions).")

    suspend fun requireRoot(what: String): ShellExecutor = root()
        ?: throw ControlError("$what needs root. Root is not available or not granted (see Permissions → Root).")

    /** Runs [commands] in order until one exits 0. */
    suspend fun firstWorking(sh: ShellExecutor, vararg commands: String): ShellResult? {
        for (c in commands) {
            val r = sh.run(c, 15_000)
            if (r.ok && !r.stdout.contains("Unknown command", ignoreCase = true) && !r.stderr.contains("Unknown command", ignoreCase = true)) return r
        }
        return null
    }

    /** Starts [intent] from the accessibility service when bound (allowed from the background), else the app. */
    suspend fun startActivity(intent: Intent): Boolean = withContext(Dispatchers.Main) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            (Accessibility.service ?: app).startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun tierName(sh: ShellExecutor): String = when (sh.tier) {
        ShellTier.Root -> "root"
        ShellTier.Shizuku -> "Shizuku"
        ShellTier.App -> "app shell"
    }
}

fun shQuote(s: String): String = ShellFraming.quote(s)

/** How a write went out. [Panel] means a system panel was opened for the user to flip the switch. */
sealed interface Written {
    data class Done(val via: String) : Written
    data class Panel(val what: String) : Written
}

/** A readable, writable piece of device state with a stable display form and a journal encoding. */
abstract class Control<T : Any>(val id: String, val label: String) {
    abstract suspend fun read(env: DeviceEnv): T?
    abstract suspend fun write(env: DeviceEnv, value: T): Written
    abstract fun show(v: T): String
    abstract fun encode(v: T): String
    abstract fun decode(s: String): T?

    /** How long to wait for the new state to show after a direct write. */
    open val settleMs: Long = 3_000

    open fun matches(after: T, target: T): Boolean = after == target
}

abstract class BoolControl(id: String, label: String) : Control<Boolean>(id, label) {
    override fun show(v: Boolean) = if (v) "on" else "off"
    override fun encode(v: Boolean) = v.toString()
    override fun decode(s: String) = s.toBooleanStrictOrNull()
}

/** Applies a change, verifies it by reading back, journals the before-state and words the outcome honestly. */
object ControlRunner {
    private val registry = HashMap<String, Control<*>>()
    private val backoff = longArrayOf(120, 250, 400, 700, 1_000, 1_500)

    fun register(vararg controls: Control<*>) = synchronized(registry) { controls.forEach { registry[it.id] = it } }

    fun control(id: String): Control<*>? = synchronized(registry) { registry[id] }

    fun installRestorer(context: Context) {
        Undo.register("control:") { entry ->
            val c = control(entry.kind.removePrefix("control:")) ?: return@register UndoResult(false, "Unknown control ${entry.kind}.")
            restore(DeviceEnv(context), c, entry.undoData!!)
        }
    }

    private suspend fun <T : Any> restore(env: DeviceEnv, c: Control<T>, data: String): UndoResult {
        val value = c.decode(data) ?: return UndoResult(false, "The saved state for ${c.label} is unreadable.")
        val change = change(env, c, value, tool = "undo", journal = false)
        return UndoResult(change.status == ChangeStatus.Verified || change.status == ChangeStatus.Unchanged, change.text)
    }

    suspend fun <T : Any> apply(env: DeviceEnv, c: Control<T>, target: T, tool: String): ToolResult {
        val change = change(env, c, target, tool, journal = true)
        return ToolResult(change.text, isError = change.status == ChangeStatus.Failed)
    }

    suspend fun <T : Any> change(env: DeviceEnv, c: Control<T>, target: T, tool: String, journal: Boolean): Change {
        val before = runCatching { c.read(env) }.getOrNull()
        if (before != null && c.matches(before, target)) {
            return Change(c.label, c.show(before), c.show(target), c.show(before), ChangeStatus.Unchanged)
        }
        val written = try {
            c.write(env, target)
        } catch (e: ControlError) {
            return Change(c.label, before?.let(c::show), c.show(target), before?.let(c::show), ChangeStatus.Failed, note = e.message)
        } catch (e: SecurityException) {
            return Change(c.label, before?.let(c::show), c.show(target), before?.let(c::show), ChangeStatus.Failed, note = "Android refused: ${e.message}")
        }
        val wait = if (written is Written.Panel) 12_000L else c.settleMs
        val after = awaitValue(env, c, target, wait)
        val status = if (after != null && c.matches(after, target)) ChangeStatus.Verified else ChangeStatus.Unconfirmed
        val note = (written as? Written.Panel)?.let {
            if (status == ChangeStatus.Verified) "Switched by the user in the ${it.what} Atom opened"
            else "Android does not let apps flip this directly without Shizuku or root, so Atom opened the ${it.what}; switch it there"
        }
        val via = (written as? Written.Done)?.via
        if (journal && before != null && (status == ChangeStatus.Verified || written is Written.Done)) {
            Undo.journal(env.app).record(
                tool = tool,
                label = c.label,
                before = c.show(before),
                after = after?.let(c::show) ?: "${c.show(target)}?",
                kind = "control:${c.id}",
                undoData = c.encode(before),
                verified = status == ChangeStatus.Verified,
            )
        }
        return Change(c.label, before?.let(c::show), c.show(target), after?.let(c::show), status, via, note)
    }

    private suspend fun <T : Any> awaitValue(env: DeviceEnv, c: Control<T>, target: T, totalMs: Long): T? {
        var waited = 0L
        var last: T? = null
        var i = 0
        while (true) {
            last = runCatching { c.read(env) }.getOrNull() ?: last
            if (last != null && c.matches(last, target)) return last
            if (waited >= totalMs) return last
            val step = backoff[minOf(i++, backoff.size - 1)]
            delay(step)
            waited += step
        }
    }
}

/** Reads and writes Settings.System/Secure/Global: public API first, then the Shizuku/root `settings` command. */
object SysSettings {
    enum class Ns(val cmd: String) { System("system"), Secure("secure"), Global("global") }

    suspend fun get(env: DeviceEnv, ns: Ns, key: String): String? {
        val api = try {
            val cr = env.app.contentResolver
            when (ns) {
                Ns.System -> Settings.System.getString(cr, key)
                Ns.Secure -> Settings.Secure.getString(cr, key)
                Ns.Global -> Settings.Global.getString(cr, key)
            }
        } catch (e: Exception) {
            null
        }
        if (api != null) return api
        val sh = env.privileged() ?: return null
        val r = sh.run("settings get ${ns.cmd} ${shQuote(key)}", 10_000)
        return r.stdout.trim().takeIf { r.ok && it.isNotEmpty() && it != "null" }
    }

    suspend fun put(env: DeviceEnv, ns: Ns, key: String, value: String): String {
        if (ns == Ns.System && Settings.System.canWrite(env.app)) {
            try {
                if (Settings.System.putString(env.app.contentResolver, key, value)) return "system settings"
            } catch (e: Exception) {
                // Not a public key, or the ROM refuses; try the shell below.
            }
        }
        val sh = env.privileged() ?: throw ControlError(
            if (ns == Ns.System) {
                "Atom needs \"Modify system settings\" (Permissions page), or Shizuku/root, to change this."
            } else {
                "Changing this needs Shizuku or root (see Permissions)."
            },
        )
        val r = sh.run("settings put ${ns.cmd} ${shQuote(key)} ${shQuote(value)}", 10_000)
        if (!r.ok) throw ControlError("settings put failed: ${(r.stderr.ifBlank { r.stdout }).take(200)}")
        return env.tierName(sh)
    }

    suspend fun delete(env: DeviceEnv, ns: Ns, key: String): String {
        val sh = env.requirePrivileged("Deleting a settings key")
        val r = sh.run("settings delete ${ns.cmd} ${shQuote(key)}", 10_000)
        if (!r.ok) throw ControlError("settings delete failed: ${(r.stderr.ifBlank { r.stdout }).take(200)}")
        return env.tierName(sh)
    }
}

/** A boolean stored as 0/1 in a settings table. */
open class SettingBool(
    id: String,
    label: String,
    private val ns: SysSettings.Ns,
    private val key: String,
    private val onValue: String = "1",
    private val offValue: String = "0",
) : BoolControl(id, label) {
    override suspend fun read(env: DeviceEnv): Boolean? = SysSettings.get(env, ns, key)?.trim()?.let { it == onValue }

    override suspend fun write(env: DeviceEnv, value: Boolean): Written =
        Written.Done(SysSettings.put(env, ns, key, if (value) onValue else offValue))
}

/** Opens [intent] for the user when Atom cannot flip a switch itself. */
suspend fun openPanel(env: DeviceEnv, what: String, vararg intents: Intent): Written {
    for (i in intents) if (env.startActivity(i)) return Written.Panel(what)
    throw ControlError("Atom cannot change this on this phone without Shizuku or root, and the settings screen would not open.")
}
