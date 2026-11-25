package atom.device.control

import android.content.Context
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.tools.SimpleTool

/** System toggles and levels. Every change reports before → after, verified or not, and lands in the undo journal. */
internal class ControlTools(private val context: Context) {
    fun all(): List<Tool> = listOf(states(), toggle(), brightness(), timeout(), darkMode(), volume(), soundMode(), dnd())

    private fun env(ctx: atom.core.tool.ToolContext) = DeviceEnv(context) { ctx.confirm(it) }

    private fun states() = SimpleTool(
        name = "get_device_controls",
        description = "Current state of the phone's switches and levels: Wi-Fi, Bluetooth, mobile data, airplane, " +
            "location, NFC, battery saver, hotspot, flashlight, auto-rotate, brightness, screen timeout, dark mode, " +
            "night light, volumes, sound mode, Do Not Disturb.",
        risk = Risk.Read,
        parameters = schema(),
    ) { _, ctx ->
        val env = env(ctx)
        val lines = mutableListOf<String>()
        suspend fun <T : Any> line(c: Control<T>) {
            val v = runCatching { c.read(env) }.getOrNull()
            lines += "${c.label}: " + (v?.let(c::show) ?: "unknown")
        }
        Controls.toggles.values.forEach { line(it) }
        line(Controls.brightness)
        line(Controls.screenTimeout)
        line(Controls.darkMode)
        line(Controls.soundMode)
        line(Controls.dnd)
        Controls.volumes.values.forEach { line(it) }
        ToolResult.ok(lines.joinToString("\n"))
    }

    private fun toggle() = SimpleTool(
        name = "set_toggle",
        description = "Turn a phone switch on or off and report before → after, verified or unconfirmed. Uses the " +
            "system API, then Shizuku, then root; without those it opens the right system panel for the user. " +
            "Names: ${Controls.toggles.keys.joinToString()}.",
        risk = Risk.Act,
        parameters = schema {
            string("name", "Which switch.", required = true, enum = Controls.toggles.keys.toList())
            boolean("on", "true to turn on, false to turn off.", required = true)
        },
    ) { args, ctx ->
        val name = args.string("name").lowercase().replace('-', '_').replace(' ', '_')
        val c = Controls.toggles[name] ?: throw ToolArgumentException("Unknown switch '$name'. Use one of ${Controls.toggles.keys.joinToString()}.")
        val on = args.optBoolean("on") ?: throw ToolArgumentException("Missing 'on' (true or false).")
        ControlRunner.apply(env(ctx), c, on, "set_toggle")
    }

    private fun brightness() = SimpleTool(
        name = "set_brightness",
        description = "Set screen brightness as a slider percentage (0-100), and/or switch auto-brightness. " +
            "A fixed level usually needs auto off.",
        risk = Risk.Act,
        parameters = schema {
            integer("percent", "Slider position 0-100.")
            boolean("auto", "Turn auto-brightness on (true) or off (false).")
        },
    ) { args, ctx ->
        val env = env(ctx)
        val pct = args.optInt("percent")
        val auto = args.optBoolean("auto")
        if (pct == null && auto == null) throw ToolArgumentException("Give percent, auto, or both.")
        val parts = mutableListOf<ToolResult>()
        val autoTarget = auto ?: if (pct != null) false else null
        if (autoTarget != null) parts += ControlRunner.apply(env, Controls.autoBrightness, autoTarget, "set_brightness")
        if (pct != null) parts += ControlRunner.apply(env, Controls.brightness, Brightness.rawFor(pct.coerceIn(0, 100)), "set_brightness")
        ToolResult(parts.joinToString("\n") { it.text }, isError = parts.all { it.isError })
    }

    private fun timeout() = SimpleTool(
        name = "set_screen_timeout",
        description = "Set how long the screen stays on without touch, in seconds (15-1800).",
        risk = Risk.Act,
        parameters = schema { integer("seconds", "Timeout in seconds.", required = true) },
    ) { args, ctx ->
        val s = args.optInt("seconds") ?: throw ToolArgumentException("Missing seconds.")
        ControlRunner.apply(env(ctx), Controls.screenTimeout, s.coerceIn(5, 1_800) * 1000, "set_screen_timeout")
    }

    private fun darkMode() = SimpleTool(
        name = "set_dark_mode",
        description = "Switch system dark mode: on, off or auto (follow the schedule).",
        risk = Risk.Act,
        parameters = schema { string("mode", "Dark mode.", required = true, enum = DarkMode.values) },
    ) { args, ctx ->
        val mode = args.string("mode").lowercase()
        if (mode !in DarkMode.values) throw ToolArgumentException("mode must be on, off or auto.")
        ControlRunner.apply(env(ctx), Controls.darkMode, mode, "set_dark_mode")
    }

    private fun volume() = SimpleTool(
        name = "set_volume",
        description = "Set one volume stream (${Controls.volumes.keys.joinToString()}) by percent or by level, or mute it.",
        risk = Risk.Act,
        parameters = schema {
            string("stream", "Which volume.", required = true, enum = Controls.volumes.keys.toList())
            integer("percent", "0-100 of the stream's range.")
            integer("level", "Exact step, 0 to the stream's maximum.")
            boolean("mute", "true sets it to its minimum.")
        },
    ) { args, ctx ->
        val env = env(ctx)
        val stream = args.string("stream").lowercase()
        val c = Controls.volumes[stream] ?: throw ToolArgumentException("stream must be one of ${Controls.volumes.keys.joinToString()}.")
        val max = c.max(env)
        val target = when {
            args.optBoolean("mute") == true -> c.min(env)
            args.optInt("level") != null -> args.optInt("level")!!.coerceIn(c.min(env), max)
            args.optInt("percent") != null -> (args.optInt("percent")!!.coerceIn(0, 100) * max + 50) / 100
            else -> throw ToolArgumentException("Give percent, level or mute.")
        }
        ControlRunner.apply(env, c, target, "set_volume")
    }

    private fun soundMode() = SimpleTool(
        name = "set_sound_mode",
        description = "Ringer mode: normal (ring), vibrate or silent. Needs Do Not Disturb access, or Shizuku/root to grant it after asking.",
        risk = Risk.Act,
        parameters = schema { string("mode", "Ringer mode.", required = true, enum = SoundMode.values) },
    ) { args, ctx ->
        val mode = args.string("mode").lowercase().let { if (it == "ring") "normal" else it }
        if (mode !in SoundMode.values) throw ToolArgumentException("mode must be normal, vibrate or silent.")
        ControlRunner.apply(env(ctx), Controls.soundMode, mode, "set_sound_mode")
    }

    private fun dnd() = SimpleTool(
        name = "set_do_not_disturb",
        description = "Do Not Disturb: off, priority (priority only), alarms (alarms only) or total_silence.",
        risk = Risk.Act,
        parameters = schema { string("mode", "Do Not Disturb mode.", required = true, enum = DoNotDisturb.values) },
    ) { args, ctx ->
        val mode = args.string("mode").lowercase().replace(' ', '_').let { if (it == "on") "priority" else it }
        if (mode !in DoNotDisturb.values) throw ToolArgumentException("mode must be one of ${DoNotDisturb.values.joinToString()}.")
        ControlRunner.apply(env(ctx), Controls.dnd, mode, "set_do_not_disturb")
    }
}
