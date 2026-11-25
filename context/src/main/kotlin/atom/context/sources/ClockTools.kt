package atom.context.sources

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolResult
import atom.core.tool.int
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.device.control.DeviceEnv
import kotlinx.coroutines.delay
import java.util.Calendar

/** Alarms and timers through the system clock app's public intents. */
internal class ClockTools(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("atom_clock", Context.MODE_PRIVATE)

    fun all(): List<Tool> = listOf(setAlarm(), setTimer(), list())

    private suspend fun start(intent: Intent): Boolean = DeviceEnv(app).startActivity(intent)

    private fun nextAlarm(): Long? = app.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime

    private val dayNames = mapOf(
        "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY, "thu" to Calendar.THURSDAY,
        "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY,
    )

    private fun setAlarm() = CtxTool(
        name = "set_alarm",
        description = "Create an alarm in the system clock app (hour 0-23, minute), optionally repeating on days. " +
            "Checks that it became the next alarm; if the clock app cannot be driven silently it opens it to confirm.",
        risk = Risk.Act,
        parameters = schema {
            integer("hour", "Hour, 0-23.", required = true)
            integer("minute", "Minute, 0-59.", required = true)
            string("label", "Alarm label.")
            string("days", "Repeat days, comma-separated: mon,tue,wed,thu,fri,sat,sun.")
            boolean("vibrate", "Vibrate. Default true.")
        },
    ) { args, _ ->
        val hour = args.int("hour")
        val minute = args.int("minute")
        if (hour !in 0..23 || minute !in 0..59) throw ToolArgumentException("hour must be 0-23 and minute 0-59.")
        val days = args.optString("days")?.split(',', ' ')?.mapNotNull { dayNames[it.trim().lowercase().take(3)] }.orEmpty()
        val before = nextAlarm()
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_VIBRATE, args.optBoolean("vibrate") != false)
        args.optString("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        if (!start(i)) {
            val ui = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            return@CtxTool if (start(ui)) ToolResult.ok("Opened the clock app with %02d:%02d filled in; the user needs to tap Save (unconfirmed).".format(hour, minute))
            else ToolResult.error("No clock app on this phone accepts alarms.")
        }
        delay(1_500)
        val after = nextAlarm()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val matches = after != null && Calendar.getInstance().apply { timeInMillis = after }.let {
            it.get(Calendar.HOUR_OF_DAY) == hour && it.get(Calendar.MINUTE) == minute
        }
        val label = "%02d:%02d".format(hour, minute) + (args.optString("label")?.let { " \"$it\"" }.orEmpty()) +
            if (days.isNotEmpty()) " repeating ${args.optString("days")}" else ""
        when {
            matches -> ToolResult.ok("Alarm $label set (verified: it is now the next alarm, ${When.at(after)}).")
            after != before -> ToolResult.ok("Alarm $label set (verified: the next alarm changed to ${after?.let { When.at(it) } ?: "none"}).")
            else -> ToolResult.ok(
                "Alarm $label sent to the clock app, unconfirmed: the next alarm is still ${before?.let { When.at(it) } ?: "none"} " +
                    "(an earlier alarm may be next). target ${When.at(if (target.timeInMillis < System.currentTimeMillis()) target.timeInMillis + 86_400_000L else target.timeInMillis)}.",
            )
        }
    }

    private fun setTimer() = CtxTool(
        name = "set_timer",
        description = "Start a countdown timer in the clock app, up to 24 hours.",
        risk = Risk.Act,
        parameters = schema {
            integer("seconds", "Length in seconds, 1-86400.", required = true)
            string("label", "Timer label.")
        },
    ) { args, _ ->
        val s = args.optInt("seconds") ?: throw ToolArgumentException("Missing seconds.")
        if (s !in 1..86_400) throw ToolArgumentException("Timers can be 1 second to 24 hours (86400 s).")
        val label = args.optString("label")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (!start(i)) return@CtxTool ToolResult.error("No clock app on this phone accepts timers.")
        val end = System.currentTimeMillis() + s * 1000L
        val active = timers().filter { it.first > System.currentTimeMillis() } + (end to (label ?: ""))
        prefs.edit().putString("timers", active.joinToString("\n") { "${it.first}|${it.second.replace("\n", " ")}" }).apply()
        ToolResult.ok("Timer for ${When.duration(s * 1000L)}${label?.let { " \"$it\"" }.orEmpty()} started in the clock app; it ends at ${When.at(end)}.")
    }

    private fun timers(): List<Pair<Long, String>> = prefs.getString("timers", null).orEmpty().lines().mapNotNull { line ->
        val p = line.split('|', limit = 2)
        p.getOrNull(0)?.toLongOrNull()?.let { it to p.getOrElse(1) { "" } }
    }

    private fun list() = CtxTool(
        name = "alarms_and_timers",
        description = "The next system alarm (from any clock app) and the timers Atom started that are still running.",
        risk = Risk.Read,
        parameters = schema(),
    ) { _, _ ->
        val am = app.getSystemService(AlarmManager::class.java)
        val next = am?.nextAlarmClock
        val now = System.currentTimeMillis()
        val running = timers().filter { it.first > now }
        prefs.edit().putString("timers", running.joinToString("\n") { "${it.first}|${it.second}" }).apply()
        val owner = next?.showIntent?.creatorPackage
        ToolResult.ok(
            buildString {
                append("next alarm: ").append(next?.let { When.at(it.triggerTime) + " (in ${When.duration(it.triggerTime - now)})" + (owner?.let { o -> ", set in $o" }.orEmpty()) } ?: "none")
                append("\nrunning timers started by Atom: ")
                if (running.isEmpty()) append("none") else running.forEach { (end, label) ->
                    append("\n  ").append(label.ifBlank { "timer" }).append(": ").append(When.duration(end - now)).append(" left (ends ${When.at(end)})")
                }
                append("\nAndroid does not let apps list every alarm or timer in the clock app; open it with open_app to see all.")
            },
        )
    }
}
