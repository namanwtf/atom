package atom.context.sources

import android.content.Intent
import android.provider.AlarmClock

/** An intent described as plain data, so how alarm requests are built is unit-testable. */
internal data class IntentSpec(val action: String, val extras: Map<String, Any> = emptyMap()) {
    fun toIntent(): Intent {
        val i = Intent(action)
        extras.forEach { (k, v) ->
            when (v) {
                is Int -> i.putExtra(k, v)
                is Boolean -> i.putExtra(k, v)
                is String -> i.putExtra(k, v)
                is List<*> -> i.putIntegerArrayListExtra(k, ArrayList(v.filterIsInstance<Int>()))
            }
        }
        return i
    }
}

/** An alarm Atom created (Android offers no API to list the clock app's alarms, so Atom remembers its own). */
internal data class AlarmRecord(val hour: Int, val minute: Int, val days: List<Int>, val label: String, val enabled: Boolean = true) {
    val time: String get() = "%02d:%02d".format(hour, minute)

    fun encode(): String = listOf(hour, minute, days.joinToString(","), if (enabled) "1" else "0", label.replace('\n', ' ').replace('|', '/'))
        .joinToString("|")

    companion object {
        fun decode(line: String): AlarmRecord? {
            val p = line.split('|', limit = 5)
            if (p.size < 5) return null
            val hour = p[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = p[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            val days = p[2].split(',').mapNotNull { it.trim().toIntOrNull() }
            return AlarmRecord(hour, minute, days, p[4], enabled = p[3] == "1")
        }

        fun decodeAll(text: String?): List<AlarmRecord> = text.orEmpty().lines().mapNotNull(::decode)

        fun encodeAll(list: List<AlarmRecord>): String = list.joinToString("\n") { it.encode() }
    }
}

/**
 * The documented [AlarmClock] intents behind the alarm tools. Android has intents to create an alarm, to dismiss
 * (turn off) an alarm found by time, label, "next" or "all", to dismiss a ringing timer, and to show the alarm list;
 * there is no intent that deletes an alarm or edits one in place. "Change" is therefore: turn the old one off, then
 * set the new time; "enable" re-sends the same alarm (clock apps reuse a matching alarm instead of duplicating it).
 */
internal object AlarmIntents {
    fun set(hour: Int, minute: Int, label: String?, days: List<Int>, vibrate: Boolean = true, skipUi: Boolean = true): IntentSpec {
        val extras = linkedMapOf<String, Any>(
            AlarmClock.EXTRA_HOUR to hour,
            AlarmClock.EXTRA_MINUTES to minute,
            AlarmClock.EXTRA_SKIP_UI to skipUi,
            AlarmClock.EXTRA_VIBRATE to vibrate,
        )
        if (!label.isNullOrBlank()) extras[AlarmClock.EXTRA_MESSAGE] = label
        if (days.isNotEmpty()) extras[AlarmClock.EXTRA_DAYS] = days
        return IntentSpec(AlarmClock.ACTION_SET_ALARM, extras)
    }

    /** Turns off the alarm at [hour]:[minute] (24 h). The clock app may ask the user to pick when several match. */
    fun dismissAt(hour: Int, minute: Int): IntentSpec = IntentSpec(
        AlarmClock.ACTION_DISMISS_ALARM,
        linkedMapOf(
            AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_TIME,
            AlarmClock.EXTRA_HOUR to hour,
            AlarmClock.EXTRA_MINUTES to minute,
            // Some clock apps read the hour as 12-hour with this flag; with a 24-hour hour it is consistent either way.
            AlarmClock.EXTRA_IS_PM to (hour >= 12),
        ),
    )

    fun dismissLabelled(label: String): IntentSpec = IntentSpec(
        AlarmClock.ACTION_DISMISS_ALARM,
        linkedMapOf(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_LABEL, AlarmClock.EXTRA_MESSAGE to label),
    )

    fun dismissNext(): IntentSpec =
        IntentSpec(AlarmClock.ACTION_DISMISS_ALARM, mapOf(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_NEXT))

    fun dismissAll(): IntentSpec =
        IntentSpec(AlarmClock.ACTION_DISMISS_ALARM, mapOf(AlarmClock.EXTRA_ALARM_SEARCH_MODE to AlarmClock.ALARM_SEARCH_MODE_ALL))

    fun dismissTimer(): IntentSpec = IntentSpec(AlarmClock.ACTION_DISMISS_TIMER)

    fun showAlarms(): IntentSpec = IntentSpec(AlarmClock.ACTION_SHOW_ALARMS)

    /** The steps of one alarm change; each is sent in order. */
    fun plan(action: String, from: AlarmRecord?, to: AlarmRecord?, label: String?): List<IntentSpec> = when (action) {
        "enable" -> listOf(set(requireNotNull(from).hour, from.minute, from.label.ifBlank { null }, from.days))
        "disable" -> listOf(if (from != null) dismissAt(from.hour, from.minute) else dismissLabelled(requireNotNull(label)))
        "change" -> {
            val old = requireNotNull(from)
            val new = requireNotNull(to)
            listOf(dismissAt(old.hour, old.minute), set(new.hour, new.minute, new.label.ifBlank { null }, new.days))
        }
        // No delete intent exists: turn it off, then show the list so the user can delete it.
        "delete" -> listOf(if (from != null) dismissAt(from.hour, from.minute) else dismissLabelled(requireNotNull(label)), showAlarms())
        "dismiss_next" -> listOf(dismissNext())
        "dismiss_all" -> listOf(dismissAll())
        "dismiss_timer" -> listOf(dismissTimer())
        else -> throw IllegalArgumentException("Unknown action $action")
    }

    val ACTIONS = listOf("enable", "disable", "change", "delete", "dismiss_next", "dismiss_all", "dismiss_timer")
}
