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

    fun all(): List<Tool> = listOf(setAlarm(), setTimer(), list(), changeAlarm())

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
        val i = AlarmIntents.set(hour, minute, args.optString("label"), days, vibrate = args.optBoolean("vibrate") != false).toIntent()
        if (!start(i)) {
            val ui = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            return@CtxTool if (start(ui)) ToolResult.ok("Opened the clock app with %02d:%02d filled in; the user needs to tap Save (unconfirmed).".format(hour, minute))
            else ToolResult.error("No clock app on this phone accepts alarms.")
        }
        remember(AlarmRecord(hour, minute, days, args.optString("label").orEmpty()))
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

    private fun saved(): List<AlarmRecord> = AlarmRecord.decodeAll(prefs.getString("alarms", null))

    private fun save(list: List<AlarmRecord>) {
        prefs.edit().putString("alarms", AlarmRecord.encodeAll(list.takeLast(MAX_SAVED))).apply()
    }

    private fun remember(r: AlarmRecord) =
        save(saved().filterNot { it.hour == r.hour && it.minute == r.minute && it.days == r.days } + r)

    private fun list() = CtxTool(
        name = "alarms_and_timers",
        description = "Alarms: the next system alarm (from any clock app) and every alarm Atom created (with its " +
            "on/off state as far as Atom changed it); the timers Atom started that are still running.",
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
                val mine = saved()
                append("\nalarms created by Atom: ")
                if (mine.isEmpty()) append("none") else mine.forEach { a ->
                    append("\n  ").append(a.time)
                    if (a.label.isNotBlank()) append(" \"").append(a.label).append('"')
                    if (a.days.isNotEmpty()) append(" repeating ").append(a.days.joinToString(",") { dayLabel(it) })
                    append(if (a.enabled) " (on)" else " (turned off)")
                }
                append("\nrunning timers started by Atom: ")
                if (running.isEmpty()) append("none") else running.forEach { (end, label) ->
                    append("\n  ").append(label.ifBlank { "timer" }).append(": ").append(When.duration(end - now)).append(" left (ends ${When.at(end)})")
                }
                append("\nAndroid does not let apps list every alarm or timer in the clock app; open it with open_app to see all. ")
                append("change_alarm turns alarms on or off, changes their time, deletes them or dismisses timers.")
            },
        )
    }

    private fun dayLabel(day: Int): String = dayNames.entries.firstOrNull { it.value == day }?.key ?: day.toString()

    private fun changeAlarm() = CtxTool(
        name = "change_alarm",
        description = "Change an alarm in the clock app through Android's alarm intents: enable, disable (turns it " +
            "off), change (to a new time), delete, dismiss_next, dismiss_all, or dismiss_timer (a ringing timer). " +
            "Find the alarm by hour+minute (24 h) or label. Android has no intent to delete or edit an alarm in place: " +
            "delete turns it off and opens the alarm list for the user to remove it; change turns the old one off and " +
            "sets the new time. Reports whether the next alarm changed, or that the clock app was opened to confirm.",
        risk = Risk.Act,
        parameters = schema {
            string("action", "One of: " + AlarmIntents.ACTIONS.joinToString(", ") + ".", required = true)
            integer("hour", "Hour of the existing alarm, 0-23.")
            integer("minute", "Minute of the existing alarm, 0-59.")
            string("label", "Label of the existing alarm (when no time is given).")
            integer("new_hour", "For change: the new hour, 0-23.")
            integer("new_minute", "For change: the new minute, 0-59.")
            string("days", "For enable/change: repeat days, comma-separated: mon,tue,wed,thu,fri,sat,sun.")
        },
    ) { args, _ ->
        val action = args.optString("action")?.trim()?.lowercase()
        if (action == null || action !in AlarmIntents.ACTIONS) throw ToolArgumentException("action must be one of " + AlarmIntents.ACTIONS.joinToString(", ") + ".")
        val hour = args.optInt("hour")
        val minute = args.optInt("minute")
        val label = args.optString("label")?.takeIf { it.isNotBlank() }
        if (hour != null && hour !in 0..23 || minute != null && minute !in 0..59) throw ToolArgumentException("hour must be 0-23 and minute 0-59.")
        val days = args.optString("days")?.split(',', ' ')?.mapNotNull { dayNames[it.trim().lowercase().take(3)] }
        val known = saved()
        val from: AlarmRecord? = when {
            hour != null -> known.firstOrNull { it.hour == hour && it.minute == (minute ?: 0) }
                ?: AlarmRecord(hour, minute ?: 0, days.orEmpty(), label.orEmpty())
            label != null -> known.firstOrNull { it.label.equals(label, ignoreCase = true) }
            else -> null
        }?.let { r -> if (days != null) r.copy(days = days) else r }
        val needsTarget = action in setOf("enable", "disable", "change", "delete")
        if (needsTarget && from == null && (action != "disable" && action != "delete" || label == null)) {
            throw ToolArgumentException("Say which alarm: hour and minute (24 h), or its label.")
        }
        val to = if (action == "change") {
            val nh = args.optInt("new_hour") ?: throw ToolArgumentException("change needs new_hour and new_minute.")
            val nm = args.optInt("new_minute") ?: 0
            if (nh !in 0..23 || nm !in 0..59) throw ToolArgumentException("new_hour must be 0-23 and new_minute 0-59.")
            AlarmRecord(nh, nm, from!!.days, from.label)
        } else {
            null
        }
        val before = nextAlarm()
        val steps = AlarmIntents.plan(action, from, to, label)
        for (step in steps) {
            if (!start(step.toIntent())) {
                return@CtxTool ToolResult.error("The clock app on this phone does not accept this request (${step.action.substringAfterLast('.')}).")
            }
            delay(700)
        }
        delay(1_200)
        val after = nextAlarm()
        // Keep Atom's own list in step with what was asked.
        when (action) {
            "enable" -> remember(from!!.copy(enabled = true))
            "disable" -> from?.let { f -> save(saved().map { if (it.hour == f.hour && it.minute == f.minute) it.copy(enabled = false) else it }) }
            "change" -> save(saved().filterNot { it.hour == from!!.hour && it.minute == from.minute } + to!!)
            "delete" -> from?.let { f -> save(saved().filterNot { it.hour == f.hour && it.minute == f.minute }) }
            "dismiss_all" -> save(saved().map { it.copy(enabled = false) })
        }
        val what = from?.let { f -> f.time + (f.label.takeIf { it.isNotBlank() }?.let { " \"$it\"" }.orEmpty()) } ?: label?.let { "\"$it\"" } ?: "alarm"
        val nextNow = "next alarm now: ${after?.let { When.at(it) } ?: "none"}"
        val changed = after != before
        val text = when (action) {
            "delete" -> "Turned off $what and opened the clock app's alarm list so the user can delete it (Android has no delete " +
                "intent); unconfirmed until the user removes it. $nextNow."
            "dismiss_timer" -> "Asked the clock app to dismiss the ringing timer."
            "change" -> if (changed) "Moved $what to ${to!!.time} (verified: the next alarm changed). $nextNow."
            else "Sent $what → ${to!!.time} to the clock app, unconfirmed: the next alarm did not change (the clock app may have opened to confirm). $nextNow."
            else -> if (changed) "Done: $action $what (verified: the next alarm changed). $nextNow."
            else "Sent $action for $what to the clock app, unconfirmed: the next alarm did not change (it may not be the next one, " +
                "or the clock app opened to confirm). $nextNow."
        }
        ToolResult.ok(text)
    }

    private companion object {
        const val MAX_SAVED = 30
    }
}
