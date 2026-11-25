package atom.context.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import atom.context.search.Slice
import atom.context.search.one
import atom.context.sources.CtxTool
import atom.context.sources.Missing
import atom.context.sources.When
import atom.context.sources.guarded
import atom.context.sources.requirePermission
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.device.undo.Undo
import atom.device.undo.UndoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Everything needed to recreate or restore an event; also the journal's undo payload. */
@Serializable
data class EventData(
    val calendarId: Long,
    val title: String,
    val start: Long,
    val end: Long? = null,
    val duration: String? = null,
    val allDay: Boolean = false,
    val timezone: String,
    val location: String? = null,
    val description: String? = null,
    val rrule: String? = null,
    val reminders: List<Int> = emptyList(),
)

@Serializable
private data class EventSnapshot(val id: Long, val data: EventData)

/** Calendar search and editing through the system calendar provider, every write verified by reading it back. */
internal class CalendarTools(context: Context) {
    private val app = context.applicationContext
    private val cr get() = app.contentResolver
    private val json = Json { ignoreUnknownKeys = true }

    fun reads(): List<Tool> = listOf(calendars(), search(), read())

    fun writes(): List<Tool> = listOf(create(), update(), delete())

    private fun needRead() = app.requirePermission(Manifest.permission.READ_CALENDAR, what = "Reading the calendar")

    private fun needWrite() {
        needRead()
        app.requirePermission(Manifest.permission.WRITE_CALENDAR, what = "Changing the calendar")
    }

    // ---- time parsing -----------------------------------------------------------------------------

    private class When2(val millis: Long, val dateOnly: Boolean)

    private fun parse(s: String, zone: ZoneId = ZoneId.systemDefault()): When2 {
        val t = s.trim().replace(' ', 'T')
        runCatching { return When2(OffsetDateTime.parse(t).toInstant().toEpochMilli(), false) }
        runCatching { return When2(LocalDateTime.parse(t).atZone(zone).toInstant().toEpochMilli(), false) }
        runCatching { return When2(LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true) }
        throw ToolArgumentException("Could not read the time \"$s\". Use ISO like 2026-11-26T15:00 (local) or 2026-11-26 for a whole day.")
    }

    // ---- reads ------------------------------------------------------------------------------------

    private fun calendars() = CtxTool(
        name = "calendar_list_calendars",
        description = "List the phone's calendars with ids, account and whether Atom can write to them.",
        risk = Risk.Read,
        parameters = schema(),
    ) { _, _ ->
        guarded {
            needRead()
            val rows = withContext(Dispatchers.IO) { calendarRows() }
            if (rows.isEmpty()) ToolResult.ok("No calendars on this phone.") else ToolResult.ok(rows.joinToString("\n") { it.second })
        }
    }

    /** (id, line, writable, primary) */
    private fun calendarRows(): List<Pair<Long, String>> =
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE,
            ),
            null, null, null,
        )?.use { c ->
            val out = mutableListOf<Pair<Long, String>>()
            while (c.moveToNext()) {
                val writable = c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
                val primary = c.getInt(4) == 1
                out += c.getLong(0) to "calendar ${c.getLong(0)}: ${c.getString(1)} (${c.getString(2)})" +
                    (if (writable) ", writable" else ", read-only") + (if (primary) ", primary" else "") + (if (c.getInt(5) == 0) ", hidden" else "")
            }
            out
        }.orEmpty()

    private fun defaultCalendar(): Long =
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, null,
        )?.use { c ->
            var best: Long? = null
            var score = -1
            while (c.moveToNext()) {
                val s = c.getInt(1) * 2 + c.getInt(2)
                if (s > score) { score = s; best = c.getLong(0) }
            }
            best
        } ?: throw Missing("There is no writable calendar on this phone. Add an account with a calendar first.")

    private fun search() = CtxTool(
        name = "calendar_search",
        description = "Find calendar events by title, location or description in a date range (default 30 days back " +
            "to 60 days ahead), soonest first. Recurring events appear per occurrence. Each hit has an event id.",
        risk = Risk.Read,
        parameters = schema {
            string("query", "Text to match; omit to list everything in the range.")
            string("from", "Range start (ISO date or date-time). Default 30 days ago.")
            string("to", "Range end. Default 60 days ahead.")
            integer("limit", "Page size, default 20.")
            integer("offset", "Paging offset.")
        },
    ) { args, _ ->
        guarded {
            needRead()
            val from = args.optString("from")?.let { parse(it).millis } ?: (System.currentTimeMillis() - 30 * 86_400_000L)
            val to = args.optString("to")?.let { parse(it).millis + if (parse(it).dateOnly) 86_400_000L else 0L } ?: (System.currentTimeMillis() + 60 * 86_400_000L)
            val q = args.optString("query")?.takeIf { it.isNotBlank() }
            val uri = if (q != null) {
                Instances.CONTENT_SEARCH_URI.buildUpon().appendPath(from.toString()).appendPath(to.toString()).appendPath(q).build()
            } else {
                Instances.CONTENT_URI.buildUpon().appendPath(from.toString()).appendPath(to.toString()).build()
            }
            val limit = Slice.clampLimit(args.optInt("limit"))
            val offset = Slice.clampOffset(args.optInt("offset"))
            val slice = withContext(Dispatchers.IO) {
                Slice.fromCursor(
                    cr.query(
                        uri,
                        arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.EVENT_LOCATION, Instances.ALL_DAY, Instances.RRULE, Instances.CALENDAR_DISPLAY_NAME),
                        null, null, "${Instances.BEGIN} ASC",
                    ),
                    offset, limit,
                ) { c -> instanceLine(c) }
            }
            ToolResult.sensitive(slice.render("events") { it })
        }
    }

    private fun instanceLine(c: android.database.Cursor): String {
        val allDay = c.getInt(5) == 1
        val begin = c.getLong(1)
        val time = if (allDay) {
            java.time.Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate().toString() + " all day"
        } else {
            When.at(begin) + "–" + java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5)
        }
        val loc = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " @ ${one(it, 60)}" }.orEmpty()
        val rec = if (!c.getString(6).isNullOrBlank()) " (repeats)" else ""
        return "[event:${c.getLong(0)}] $time ${one(c.getString(3), 80)}$loc$rec · ${c.getString(7)}"
    }

    private fun read() = CtxTool(
        name = "calendar_read",
        description = "Full details of one event by id: times, calendar, location, description, recurrence, reminders, attendees.",
        risk = Risk.Read,
        parameters = schema { integer("event_id", "Event id from calendar_search.", required = true) },
    ) { args, _ ->
        guarded {
            needRead()
            val id = args.optInt("event_id")?.toLong() ?: throw ToolArgumentException("Missing event_id.")
            ToolResult.sensitive(withContext(Dispatchers.IO) { detail(id) } ?: "No event $id.")
        }
    }

    fun detail(id: Long): String? {
        val d = load(id) ?: return null
        val zone = ZoneId.of(d.timezone)
        val start = if (d.allDay) java.time.Instant.ofEpochMilli(d.start).atZone(ZoneOffset.UTC).toLocalDate().toString() else When.at(d.start, zone)
        val end = when {
            d.end != null && !d.allDay -> When.at(d.end, zone)
            d.end != null -> java.time.Instant.ofEpochMilli(d.end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).toString()
            else -> "duration ${d.duration}"
        }
        val attendees = cr.query(
            CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees.ATTENDEE_NAME, CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(id.toString()), null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)?.takeIf { it.isNotBlank() } ?: c.getString(1)) } }.orEmpty()
        return buildString {
            append("event $id: ${d.title}\n")
            append(if (d.allDay) "all day $start → $end\n" else "$start → $end (${d.timezone})\n")
            append("calendar ${d.calendarId}\n")
            d.location?.let { append("location: $it\n") }
            d.rrule?.let { append("repeats: $it\n") }
            if (d.reminders.isNotEmpty()) append("reminders: ${d.reminders.joinToString { "$it min before" }}\n")
            if (attendees.isNotEmpty()) append("attendees: ${attendees.filterNotNull().joinToString()}\n")
            d.description?.let { append("description: ${it.take(2_000)}") }
        }.trimEnd()
    }

    private fun load(id: Long): EventData? = cr.query(
        ContentUris.withAppendedId(Events.CONTENT_URI, id),
        arrayOf(Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.RRULE, Events.DELETED),
        null, null, null,
    )?.use { c ->
        if (!c.moveToFirst() || c.getInt(10) == 1) return@use null
        EventData(
            calendarId = c.getLong(0),
            title = c.getString(1).orEmpty(),
            start = c.getLong(2),
            end = if (c.isNull(3)) null else c.getLong(3),
            duration = c.getString(4),
            allDay = c.getInt(5) == 1,
            timezone = c.getString(6) ?: ZoneId.systemDefault().id,
            location = c.getString(7)?.takeIf { it.isNotBlank() },
            description = c.getString(8)?.takeIf { it.isNotBlank() },
            rrule = c.getString(9)?.takeIf { it.isNotBlank() },
            reminders = reminders(id),
        )
    }

    private fun reminders(id: Long): List<Int> = cr.query(
        Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()), null,
    )?.use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }.orEmpty()

    // ---- writes -----------------------------------------------------------------------------------

    private fun values(d: EventData) = ContentValues().apply {
        put(Events.CALENDAR_ID, d.calendarId)
        put(Events.TITLE, d.title)
        put(Events.DTSTART, d.start)
        put(Events.ALL_DAY, if (d.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, d.timezone)
        put(Events.EVENT_LOCATION, d.location)
        put(Events.DESCRIPTION, d.description)
        if (d.rrule != null) {
            put(Events.RRULE, d.rrule)
            put(Events.DURATION, d.duration ?: Duration.ofMillis(((d.end ?: (d.start + 3_600_000)) - d.start).coerceAtLeast(60_000)).toString())
            putNull(Events.DTEND)
        } else {
            put(Events.DTEND, d.end ?: (d.start + 3_600_000))
            putNull(Events.RRULE)
            putNull(Events.DURATION)
        }
    }

    /** Inserts [d] and its reminders, then reads it back; returns the new id or throws with the reason. */
    fun insert(d: EventData): Long {
        val uri = cr.insert(Events.CONTENT_URI, values(d)) ?: throw Missing("The calendar provider refused to add \"${d.title}\".")
        val id = ContentUris.parseId(uri)
        for (m in d.reminders.distinct().take(5)) {
            cr.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, m); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    private fun spec(o: JsonObject, defaultCal: () -> Long): EventData {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content?.takeIf { it.isNotBlank() }
        val title = s("title") ?: throw ToolArgumentException("Each event needs a title.")
        val start = parse(s("start") ?: throw ToolArgumentException("\"$title\" needs a start."))
        val allDay = s("all_day")?.toBoolean() ?: start.dateOnly
        val end = s("end")?.let { parse(it) }
        val durMin = s("duration_min")?.toIntOrNull()
        val startMs = if (allDay) LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(start.millis), if (start.dateOnly) ZoneOffset.UTC else ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else start.millis
        val endMs = when {
            allDay -> (end?.let { if (it.dateOnly) it.millis + 86_400_000L else it.millis } ?: (startMs + 86_400_000L)).coerceAtLeast(startMs + 86_400_000L)
            end != null -> end.millis
            durMin != null -> startMs + durMin.coerceIn(1, 7 * 24 * 60) * 60_000L
            else -> startMs + 3_600_000L
        }
        if (endMs <= startMs) throw ToolArgumentException("\"$title\" ends before it starts.")
        val reminders = when (val r = o["reminders_min"]) {
            is JsonArray -> r.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            is JsonPrimitive -> r.content.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
            else -> emptyList()
        }.filter { it in 0..40_320 }
        return EventData(
            calendarId = s("calendar_id")?.toLongOrNull() ?: defaultCal(),
            title = title.take(500),
            start = startMs,
            end = endMs,
            allDay = allDay,
            timezone = if (allDay) "UTC" else ZoneId.systemDefault().id,
            location = s("location"),
            description = s("description"),
            rrule = s("rrule")?.removePrefix("RRULE:"),
            reminders = reminders,
            duration = if (s("rrule") != null) Duration.ofMillis(endMs - startMs).let { if (allDay) "P${it.toDays()}D" else it.toString() } else null,
        )
    }

    private val eventFields: atom.core.tool.SchemaBuilder.() -> Unit = {
        string("title", "Event title.")
        string("start", "Start: ISO local date-time (2026-11-26T15:00) or a date for all-day.")
        string("end", "End (ISO). Or give duration_min.")
        integer("duration_min", "Length in minutes when no end is given. Default 60.")
        boolean("all_day", "All-day event.")
        string("location", "Location.")
        string("description", "Notes.")
        string("reminders_min", "Reminder minutes before start, comma-separated, e.g. \"10,60\".")
        string("rrule", "Recurrence rule, e.g. FREQ=WEEKLY;BYDAY=MO.")
        integer("calendar_id", "Calendar id from calendar_list_calendars. Default: the primary writable one.")
    }

    private fun create() = CtxTool(
        name = "calendar_create",
        description = "Create one event (fields) or up to 30 at once (events_json: a JSON array of objects with the same " +
            "fields), with reminders. Each is verified by reading it back; all can be undone together.",
        risk = Risk.Act,
        parameters = schema {
            eventFields()
            string("events_json", "Batch: JSON array of event objects, at most 30.")
        },
    ) { args, _ ->
        guarded {
            needWrite()
            val specs = withContext(Dispatchers.IO) {
                val cal by lazy { defaultCalendar() }
                val batch = args.optString("events_json")?.takeIf { it.isNotBlank() }
                if (batch != null) {
                    val arr = runCatching { json.parseToJsonElement(batch) as JsonArray }.getOrNull()
                        ?: throw ToolArgumentException("events_json must be a JSON array of objects.")
                    if (arr.size > 30) throw ToolArgumentException("At most 30 events per call (got ${arr.size}).")
                    arr.map { spec(it.jsonObject) { cal } }
                } else {
                    listOf(spec(args) { cal })
                }
            }
            val lines = mutableListOf<String>()
            val created = mutableListOf<Long>()
            withContext(Dispatchers.IO) {
                for (d in specs) {
                    try {
                        val id = insert(d)
                        val back = load(id)
                        val ok = back != null && back.title == d.title && back.start == d.start
                        created += id
                        lines += "[event:$id] ${d.title} ${if (d.allDay) java.time.Instant.ofEpochMilli(d.start).atZone(ZoneOffset.UTC).toLocalDate() else When.at(d.start)}" +
                            (if (d.reminders.isNotEmpty()) ", reminders ${d.reminders.joinToString()} min" else "") + if (ok) " (verified)" else " (saved, read-back did not match: unconfirmed)"
                    } catch (e: Exception) {
                        lines += "✗ ${d.title}: ${e.message}"
                    }
                }
            }
            if (created.isNotEmpty()) {
                Undo.journal(app).record(
                    tool = "calendar_create", label = if (created.size == 1) "Calendar: ${specs.first().title}" else "Calendar: ${created.size} events",
                    before = "absent", after = "created", kind = "calendar:create", undoData = created.joinToString(","),
                )
            }
            ToolResult("Created ${created.size} of ${specs.size}:\n" + lines.joinToString("\n"), isError = created.isEmpty())
        }
    }

    private fun update() = CtxTool(
        name = "calendar_update",
        description = "Change an event's fields. For a repeating event, give occurrence_start to change only that " +
            "occurrence, or whole_series=true to change all of them. Verified by reading back; undoable.",
        risk = Risk.Act,
        parameters = schema {
            integer("event_id", "Event id.", required = true)
            eventFields()
            string("occurrence_start", "For repeating events: start of the one occurrence to change (ISO).")
            boolean("whole_series", "Change every occurrence of a repeating event.")
        },
    ) { args, _ ->
        guarded {
            needWrite()
            val id = args.optInt("event_id")?.toLong() ?: throw ToolArgumentException("Missing event_id.")
            withContext(Dispatchers.IO) {
                val before = load(id) ?: throw Missing("No event $id.")
                val occurrence = args.optString("occurrence_start")
                if (before.rrule != null && occurrence == null && args.optBoolean("whole_series") != true) {
                    throw ToolArgumentException("Event $id repeats. Pass occurrence_start for one occurrence, or whole_series=true to change all of them.")
                }
                val merged = buildJsonFor(before, args)
                val next = spec(merged) { before.calendarId }.copy(rrule = if (occurrence != null) null else args.optString("rrule") ?: before.rrule)
                if (occurrence != null && before.rrule != null) {
                    val original = parse(occurrence).millis
                    val v = ContentValues().apply {
                        put(Events.ORIGINAL_INSTANCE_TIME, original)
                        put(Events.TITLE, next.title)
                        put(Events.DTSTART, next.start)
                        put(Events.DTEND, next.end)
                        put(Events.EVENT_LOCATION, next.location)
                        put(Events.DESCRIPTION, next.description)
                        put(Events.EVENT_TIMEZONE, next.timezone)
                    }
                    val ex = cr.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), v)
                        ?: throw Missing("The calendar refused to change that occurrence.")
                    val exId = ContentUris.parseId(ex)
                    Undo.journal(app).record("calendar_update", "Calendar: ${before.title} (one occurrence)", "original", "changed", "calendar:create", exId.toString())
                    return@withContext ToolResult.ok("Changed one occurrence of \"${before.title}\" (new event $exId, ${When.at(next.start)}). Undo removes the change.")
                }
                cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), values(next), null, null)
                if (args.optString("reminders_min") != null) {
                    cr.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(id.toString()))
                    next.reminders.forEach { m ->
                        cr.insert(Reminders.CONTENT_URI, ContentValues().apply { put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, m); put(Reminders.METHOD, Reminders.METHOD_ALERT) })
                    }
                }
                val after = load(id)
                val ok = after != null && after.title == next.title && after.start == next.start
                Undo.journal(app).record(
                    "calendar_update", "Calendar: ${before.title}", describe(before), after?.let(::describe) ?: "?", "calendar:update",
                    json.encodeToString(EventSnapshot.serializer(), EventSnapshot(id, before)), ok,
                )
                ToolResult.ok("Event $id: ${describe(before)} → ${after?.let(::describe) ?: "unreadable"} (${if (ok) "verified" else "unconfirmed"}).")
            }
        }
    }

    private fun buildJsonFor(before: EventData, args: JsonObject): JsonObject {
        val zone = ZoneId.of(before.timezone)
        fun iso(ms: Long) = if (before.allDay) java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
        else ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().toString()
        val base = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "title" to JsonPrimitive(before.title),
            "start" to JsonPrimitive(iso(before.start)),
            "all_day" to JsonPrimitive(before.allDay.toString()),
        )
        val endMs = before.end ?: before.duration?.let { runCatching { before.start + Duration.parse(it).toMillis() }.getOrNull() }
        endMs?.let { base["end"] = JsonPrimitive(if (before.allDay) java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).toString() else iso(it)) }
        before.location?.let { base["location"] = JsonPrimitive(it) }
        before.description?.let { base["description"] = JsonPrimitive(it) }
        if (before.reminders.isNotEmpty()) base["reminders_min"] = JsonPrimitive(before.reminders.joinToString(","))
        base["calendar_id"] = JsonPrimitive(before.calendarId.toString())
        for ((k, v) in args) {
            if (k in setOf("event_id", "occurrence_start", "whole_series")) continue
            base[k] = v
        }
        if (args.containsKey("start") && !args.containsKey("end") && !args.containsKey("duration_min") && endMs != null) {
            base.remove("end")
            base["duration_min"] = JsonPrimitive(((endMs - before.start) / 60_000).toString())
        }
        return JsonObject(base)
    }

    private fun describe(d: EventData) = "\"${d.title}\" " +
        if (d.allDay) java.time.Instant.ofEpochMilli(d.start).atZone(ZoneOffset.UTC).toLocalDate().toString() else When.at(d.start)

    private fun delete() = CtxTool(
        name = "calendar_delete",
        description = "Delete an event. A repeating event is only deleted as a whole series with whole_series=true; " +
            "occurrence_start deletes one occurrence. Verified, and undo recreates it.",
        risk = Risk.Act,
        parameters = schema {
            integer("event_id", "Event id.", required = true)
            string("occurrence_start", "For repeating events: start of the one occurrence to delete (ISO).")
            boolean("whole_series", "Delete every occurrence of a repeating event.")
        },
    ) { args, _ ->
        guarded {
            needWrite()
            val id = args.optInt("event_id")?.toLong() ?: throw ToolArgumentException("Missing event_id.")
            withContext(Dispatchers.IO) {
                val before = load(id) ?: throw Missing("No event $id (it may already be deleted).")
                val occurrence = args.optString("occurrence_start")
                if (before.rrule != null && args.optBoolean("whole_series") != true) {
                    if (occurrence == null) {
                        throw ToolArgumentException("Event $id repeats (${before.rrule}). Pass occurrence_start to delete one occurrence, or whole_series=true to delete the whole series.")
                    }
                    val v = ContentValues().apply {
                        put(Events.ORIGINAL_INSTANCE_TIME, parse(occurrence).millis)
                        put(Events.STATUS, Events.STATUS_CANCELED)
                    }
                    val ex = cr.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), v)
                        ?: throw Missing("The calendar refused to cancel that occurrence.")
                    Undo.journal(app).record("calendar_delete", "Calendar: ${before.title} (one occurrence)", "present", "cancelled", "calendar:create", ContentUris.parseId(ex).toString())
                    return@withContext ToolResult.ok("Cancelled the $occurrence occurrence of \"${before.title}\"; the rest of the series stays.")
                }
                cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null)
                val gone = load(id) == null
                Undo.journal(app).record(
                    "calendar_delete", "Calendar: ${before.title}", "present", if (gone) "deleted" else "?", "calendar:delete",
                    json.encodeToString(EventSnapshot.serializer(), EventSnapshot(id, before)), gone,
                )
                ToolResult.ok("${describe(before)}${if (before.rrule != null) " (whole series)" else ""}: present → deleted (${if (gone) "verified" else "unconfirmed"}).")
            }
        }
    }

    companion object {
        fun installRestorers(context: Context) {
            val app = context.applicationContext
            val json = Json { ignoreUnknownKeys = true }
            Undo.register("calendar:create") { e ->
                val ids = e.undoData!!.split(',').mapNotNull { it.trim().toLongOrNull() }
                withContext(Dispatchers.IO) { ids.forEach { app.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, it), null, null) } }
                UndoResult(true, "Removed ${ids.size} event${if (ids.size == 1) "" else "s"}.")
            }
            Undo.register("calendar:update") { e ->
                val snap = json.decodeFromString(EventSnapshot.serializer(), e.undoData!!)
                val tools = CalendarTools(app)
                withContext(Dispatchers.IO) {
                    app.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, snap.id), tools.values(snap.data), null, null)
                }
                UndoResult(true, "Restored \"${snap.data.title}\".")
            }
            Undo.register("calendar:delete") { e ->
                val snap = json.decodeFromString(EventSnapshot.serializer(), e.undoData!!)
                val id = withContext(Dispatchers.IO) { CalendarTools(app).insert(snap.data) }
                UndoResult(true, "Recreated \"${snap.data.title}\" as event $id.")
            }
        }
    }
}
