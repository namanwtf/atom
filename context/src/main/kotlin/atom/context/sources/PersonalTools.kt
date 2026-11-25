package atom.context.sources

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import atom.context.search.Slice
import atom.context.search.likeArg
import atom.context.search.one
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

/** On-demand, bounded reads of the phone's personal data. Nothing here runs unless the agent calls it. */
internal class PersonalTools(context: Context) {
    private val app = context.applicationContext
    private val cr get() = app.contentResolver

    /** Tools whose results are personal but not secret. */
    fun everyday(): List<Tool> = listOf(timeLocation(), contacts(), files(), usage())

    /** Messages, codes and call history: behind the "sensitive reading" switch. */
    fun sensitive(): List<Tool> = listOf(otp(), sms(), calls())

    private fun limitOffset(args: kotlinx.serialization.json.JsonObject) =
        Slice.clampLimit(args.optInt("limit")) to Slice.clampOffset(args.optInt("offset"))

    // ---- time and location -------------------------------------------------------------------------

    private fun timeLocation() = CtxTool(
        name = "time_and_location",
        description = "Local time, UTC offset and the phone's most recent location fix with its age (and an address " +
            "when available). fresh=true asks for a new fix if the last one is older than 15 minutes.",
        risk = Risk.Read,
        parameters = schema {
            boolean("fresh", "Request a new fix when the last one is stale (uses GPS briefly). Default false.")
            boolean("address", "Look up the street address (needs internet). Default true.")
        },
    ) { args, _ ->
        val now = ZonedDateTime.now()
        val lines = mutableListOf(
            "time: " + now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.US)) +
                " (${now.zone.id}, UTC${now.offset.id.replace("Z", "+00:00")})",
        )
        lines += location(args.optBoolean("fresh") == true, args.optBoolean("address") != false)
        ToolResult.sensitive(lines.joinToString("\n"))
    }

    private suspend fun location(fresh: Boolean, address: Boolean): String {
        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == 0
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == 0
        if (!fine && !coarse) return "location: Atom has no location permission (grant it on the Permissions page)."
        val lm = app.getSystemService(LocationManager::class.java) ?: return "location: unavailable."
        if (!lm.isLocationEnabled) return "location: location is switched off on the phone."
        val background = app.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == 0
        val foreground = app.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.importance?.let { it <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE } ?: true
        if (!foreground && !background) {
            return "location: Atom only has \"while using the app\" location and is in the background now; " +
                "background tasks need \"Allow all the time\" (Permissions page)."
        }
        var best: Location? = lm.allProviders.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        val stale = best == null || System.currentTimeMillis() - best.time > 15 * 60_000
        if (fresh && stale) current(lm)?.let { best = it }
        val loc = best ?: return "location: no recent fix is stored. Ask again with fresh=true, or open a maps app once."
        val line = StringBuilder(String.format(Locale.US, "location: %.5f, %.5f ±%.0f m, %s (%s)", loc.latitude, loc.longitude, loc.accuracy, When.ago(loc.time), loc.provider))
        if (!fine) line.append(", approximate only")
        if (address && Geocoder.isPresent()) {
            val addr = withTimeoutOrNull(4_000) {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { Geocoder(app).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
                }
            }
            addr?.getAddressLine(0)?.let { line.append("\naddress: ").append(it) }
        }
        return line.toString()
    }

    private suspend fun current(lm: LocationManager): Location? {
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val signal = android.os.CancellationSignal()
                try {
                    lm.getCurrentLocation(provider, signal, app.mainExecutor) { if (cont.isActive) cont.resume(it) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { signal.cancel() }
            }
        }
    }

    // ---- SMS --------------------------------------------------------------------------------------

    private fun otp() = CtxTool(
        name = "sms_verification_code",
        description = "Find one-time verification codes in recent SMS (default last 30 minutes). Returns only the " +
            "codes with sender and age, never the rest of the message.",
        risk = Risk.Read,
        parameters = schema {
            integer("minutes", "How far back, 1-1440. Default 30.")
            string("sender", "Only messages from this sender (part of the name or number).")
        },
    ) { args, _ ->
        guarded {
            app.requirePermission(Manifest.permission.READ_SMS, what = "Reading verification codes")
            val since = System.currentTimeMillis() - (args.optInt("minutes") ?: 30).coerceIn(1, 1_440) * 60_000L
            val sender = args.optString("sender")?.takeIf { it.isNotBlank() }
            val sel = StringBuilder("${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}")
            val selArgs = mutableListOf(since.toString())
            if (sender != null) {
                sel.append(" AND ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'")
                selArgs += likeArg(sender)
            }
            val codes = withContext(Dispatchers.IO) {
                cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE), sel.toString(), selArgs.toTypedArray(), "${Telephony.Sms.DATE} DESC")
                    ?.use { c ->
                        val out = mutableListOf<String>()
                        while (c.moveToNext() && out.size < 10) {
                            val code = Otp.extract(c.getString(1).orEmpty()) ?: continue
                            out += "$code — from ${c.getString(0)}, ${When.ago(c.getLong(2))}"
                        }
                        out
                    }.orEmpty()
            }
            if (codes.isEmpty()) ToolResult.ok("No verification code in SMS from the last ${(args.optInt("minutes") ?: 30)} minutes.")
            else ToolResult.sensitive("Codes (newest first):\n" + codes.joinToString("\n"))
        }
    }

    private fun sms() = CtxTool(
        name = "sms_search",
        description = "Search SMS by text and/or sender, newest first, in pages. Each hit has a ref for read_item.",
        risk = Risk.Read,
        parameters = schema {
            string("query", "Text in the message.")
            string("sender", "Part of the sender's number or name.")
            integer("days", "Only the last N days.")
            integer("limit", "Page size, default 20, max 50.")
            integer("offset", "Skip this many hits (paging).")
        },
    ) { args, _ ->
        guarded {
            app.requirePermission(Manifest.permission.READ_SMS, what = "Reading SMS")
            val (limit, offset) = limitOffset(args)
            val where = mutableListOf<String>()
            val wa = mutableListOf<String>()
            args.optString("query")?.takeIf { it.isNotBlank() }?.let { where += "${Telephony.Sms.BODY} LIKE ? ESCAPE '\\'"; wa += likeArg(it) }
            args.optString("sender")?.takeIf { it.isNotBlank() }?.let { s ->
                val numbers = contactNumbers(s)
                val parts = listOf("${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'") + numbers.map { "${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'" }
                where += "(" + parts.joinToString(" OR ") + ")"
                wa += likeArg(s)
                numbers.forEach { wa += likeArg(it.takeLast(8)) }
            }
            args.optInt("days")?.let { where += "${Telephony.Sms.DATE} >= ?"; wa += (System.currentTimeMillis() - it.coerceIn(1, 3650) * 86_400_000L).toString() }
            val slice = withContext(Dispatchers.IO) {
                Slice.fromCursor(
                    cr.query(
                        Telephony.Sms.CONTENT_URI,
                        arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                        where.joinToString(" AND ").ifEmpty { null }, wa.toTypedArray(), "${Telephony.Sms.DATE} DESC",
                    ),
                    offset, limit,
                ) { c ->
                    val dir = if (c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_SENT) "to" else "from"
                    "[sms:${c.getLong(0)}] ${When.at(c.getLong(3))} $dir ${c.getString(1)}: ${one(c.getString(2), 200)}"
                }
            }
            ToolResult.sensitive(slice.render("messages") { it })
        }
    }

    /** Phone numbers of contacts whose name matches [name], so "messages from Mum" finds her number. */
    private fun contactNumbers(name: String): List<String> {
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != 0 || name.any { it.isDigit() }) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(name))
        return cr.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            val out = mutableListOf<String>()
            while (c.moveToNext() && out.size < 5) c.getString(0)?.filter { it.isDigit() || it == '+' }?.takeIf { it.length >= 5 }?.let(out::add)
            out
        }.orEmpty()
    }

    // ---- contacts and calls -----------------------------------------------------------------------

    private fun contacts() = CtxTool(
        name = "contacts_search",
        description = "Find contacts by name (fuzzy, includes nicknames) with their phone numbers and emails.",
        risk = Risk.Read,
        parameters = schema {
            string("name", "Name or part of it.", required = true)
            integer("limit", "Page size, default 20.")
            integer("offset", "Paging offset.")
        },
    ) { args, _ ->
        guarded {
            app.requirePermission(Manifest.permission.READ_CONTACTS, what = "Searching contacts")
            val (limit, offset) = limitOffset(args)
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(args.string("name")))
            val slice = withContext(Dispatchers.IO) {
                Slice.fromCursor(
                    cr.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    offset, limit,
                ) { c -> c.getLong(0) to c.getString(1).orEmpty() }
            }
            val withDetails = withContext(Dispatchers.IO) { slice.items.map { (id, name) -> "[contact:$id] $name" + contactDetails(id, short = true) } }
            ToolResult.sensitive(Slice(withDetails, slice.offset, slice.total, slice.limit).render("contacts") { it })
        }
    }

    fun contactDetails(id: Long, short: Boolean): String {
        val parts = mutableListOf<String>()
        cr.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2),
            "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val v = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                when (c.getString(0)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> parts += "phone $v"
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> parts += "email $v"
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> if (!short) parts += "org $v"
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> if (!short) parts += "address ${one(v, 120)}"
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> if (!short) parts += "date $v"
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> if (!short) parts += "note ${one(v, 200)}"
                }
            }
        }
        val distinct = parts.distinct()
        return if (distinct.isEmpty()) "" else (if (short) " — " else "\n") + distinct.take(if (short) 4 else 30).joinToString(if (short) ", " else "\n")
    }

    private fun calls() = CtxTool(
        name = "call_log_search",
        description = "Search call history by number or contact name, newest first: direction, duration, time.",
        risk = Risk.Read,
        parameters = schema {
            string("query", "Number or name; omit for the latest calls.")
            string("type", "Only this kind.", enum = listOf("incoming", "outgoing", "missed", "rejected"))
            integer("days", "Only the last N days.")
            integer("limit", "Page size, default 20.")
            integer("offset", "Paging offset.")
        },
    ) { args, _ ->
        guarded {
            app.requirePermission(Manifest.permission.READ_CALL_LOG, what = "Reading call history")
            val (limit, offset) = limitOffset(args)
            val where = mutableListOf<String>()
            val wa = mutableListOf<String>()
            args.optString("query")?.takeIf { it.isNotBlank() }?.let {
                where += "(${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\' OR ${CallLog.Calls.CACHED_NAME} LIKE ? ESCAPE '\\')"
                wa += likeArg(it); wa += likeArg(it)
            }
            when (args.optString("type")) {
                "incoming" -> where += "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}"
                "outgoing" -> where += "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"
                "missed" -> where += "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}"
                "rejected" -> where += "${CallLog.Calls.TYPE} = ${CallLog.Calls.REJECTED_TYPE}"
            }
            args.optInt("days")?.let { where += "${CallLog.Calls.DATE} >= ?"; wa += (System.currentTimeMillis() - it.coerceIn(1, 3650) * 86_400_000L).toString() }
            val slice = withContext(Dispatchers.IO) {
                Slice.fromCursor(
                    cr.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                        where.joinToString(" AND ").ifEmpty { null }, wa.toTypedArray(), "${CallLog.Calls.DATE} DESC",
                    ),
                    offset, limit,
                ) { c ->
                    val kind = when (c.getInt(5)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        CallLog.Calls.BLOCKED_TYPE -> "blocked"
                        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
                        else -> "call"
                    }
                    val who = c.getString(2)?.takeIf { it.isNotBlank() }?.let { "$it (${c.getString(1)})" } ?: c.getString(1)
                    val dur = c.getLong(4).takeIf { it > 0 }?.let { ", ${When.duration(it * 1000)}" }.orEmpty()
                    "[call:${c.getLong(0)}] ${When.at(c.getLong(3))} $kind $who$dur"
                }
            }
            ToolResult.sensitive(slice.render("calls") { it })
        }
    }

    // ---- files ------------------------------------------------------------------------------------

    private val kinds = listOf("photos", "screenshots", "videos", "audio", "recordings", "documents", "downloads", "any")

    private fun files() = CtxTool(
        name = "files_search",
        description = "Search the phone's shared storage, newest first: photos (file name or album path), screenshots, " +
            "videos, audio (title, file name or artist), recordings (voice and call recordings), documents, downloads, or any. " +
            "Returns paths the file and image tools can open.",
        risk = Risk.Read,
        parameters = schema {
            string("kind", "What to search.", required = true, enum = kinds)
            string("query", "Text in the name, album/folder, title or artist. Omit for the newest.")
            integer("days", "Only files from the last N days.")
            integer("limit", "Page size, default 20.")
            integer("offset", "Paging offset.")
        },
    ) { args, _ ->
        guarded {
            val kind = args.string("kind").lowercase()
            val (limit, offset) = limitOffset(args)
            val q = args.optString("query")?.takeIf { it.isNotBlank() }
            val days = args.optInt("days")
            withContext(Dispatchers.IO) { searchMedia(kind, q, days, offset, limit) }
        }
    }

    private fun searchMedia(kind: String, q: String?, days: Int?, offset: Int, limit: Int): ToolResult {
        val visual = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val video = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        val audio = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        val allFiles = Environment.isExternalStorageManager()
        val (uri, perm) = when (kind) {
            "photos", "screenshots" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to visual
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to video
            "audio", "recordings" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to audio
            "downloads" -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to null
            else -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) to null
        }
        if (perm != null && !allFiles) {
            val partial = Build.VERSION.SDK_INT >= 34 && app.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == 0
            if (app.checkSelfPermission(perm) != 0 && !partial) app.requirePermission(perm, what = "Searching $kind")
        }
        val where = mutableListOf<String>()
        val wa = mutableListOf<String>()
        val name = MediaStore.MediaColumns.DISPLAY_NAME
        val path = MediaStore.MediaColumns.RELATIVE_PATH
        if (q != null) {
            val cols = mutableListOf(name, path)
            if (kind == "audio" || kind == "recordings") cols += listOf(MediaStore.Audio.AudioColumns.TITLE, MediaStore.Audio.AudioColumns.ARTIST, MediaStore.Audio.AudioColumns.ALBUM)
            if (kind == "photos" || kind == "videos") cols += MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            where += "(" + cols.joinToString(" OR ") { "$it LIKE ? ESCAPE '\\'" } + ")"
            cols.forEach { _ -> wa += likeArg(q) }
        }
        when (kind) {
            "screenshots" -> where += "($path LIKE '%Screenshot%' OR $name LIKE 'Screenshot%')"
            "recordings" -> where += buildString {
                append("(")
                if (Build.VERSION.SDK_INT >= 31) append("${MediaStore.Audio.AudioColumns.IS_RECORDING} = 1 OR ")
                append("$path LIKE '%Record%' OR $path LIKE '%Call%' OR $path LIKE '%Voice%')")
            }
            "documents" -> where += "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} AND " +
                "(${MediaStore.MediaColumns.MIME_TYPE} LIKE 'application/%' OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'text/%'))"
        }
        days?.let { where += "${MediaStore.MediaColumns.DATE_MODIFIED} >= ?"; wa += (System.currentTimeMillis() / 1000 - it.coerceIn(1, 3650) * 86_400L).toString() }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID); add(name); add(path); add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (kind == "audio" || kind == "recordings") { add(MediaStore.Audio.AudioColumns.ARTIST); add(MediaStore.MediaColumns.DURATION) }
        }.toTypedArray()
        val slice = Slice.fromCursor(
            runCatching { cr.query(uri, projection, where.joinToString(" AND ").ifEmpty { null }, wa.toTypedArray(), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC") }.getOrNull(),
            offset, limit,
        ) { c -> mediaLine(c, kind) }
        val note = when {
            (kind == "documents" || kind == "downloads" || kind == "any") && !allFiles ->
                "\n(Without All files access Android only shows media and files Atom created; grant it on the Permissions page to search every document and download.)"
            else -> ""
        }
        return ToolResult.sensitive(slice.render(kind) { it } + note)
    }

    private fun mediaLine(c: Cursor, kind: String): String {
        val id = c.getLong(0)
        val rel = c.getString(2).orEmpty()
        val full = File(Environment.getExternalStorageDirectory(), rel + c.getString(1).orEmpty()).path
        val size = c.getLong(3)
        val extra = if ((kind == "audio" || kind == "recordings") && c.columnCount > 7) {
            listOfNotNull(c.getString(6)?.takeIf { it != "<unknown>" }, c.getLong(7).takeIf { it > 0 }?.let { When.duration(it) }).joinToString(", ").let { if (it.isEmpty()) "" else ", $it" }
        } else {
            ""
        }
        return "[media:$id] $full (${human(size)}, ${When.at(c.getLong(4) * 1000)}$extra)"
    }

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "${b / 1024} KB"
        else -> "$b B"
    }

    // ---- usage ------------------------------------------------------------------------------------

    private fun usage() = CtxTool(
        name = "app_usage",
        description = "Recently used apps and foreground screen time per app over the last N hours (needs usage access).",
        risk = Risk.Read,
        parameters = schema {
            integer("hours", "Window, 1-168. Default 24.")
            integer("top", "How many apps, default 15.")
        },
    ) { args, _ ->
        guarded {
            if (!Usage.granted(app)) throw Missing("App usage needs usage access. Ask the user to grant it on Atom's Permissions page.")
            val hours = (args.optInt("hours") ?: 24).coerceIn(1, 168)
            val top = (args.optInt("top") ?: 15).coerceIn(1, 50)
            val usm = app.getSystemService(UsageStatsManager::class.java) ?: throw Missing("Usage stats unavailable.")
            val end = System.currentTimeMillis()
            val start = end - hours * 3_600_000L
            val stats = withContext(Dispatchers.IO) { usm.queryAndAggregateUsageStats(start, end) }.values
                .filter { it.packageName != app.packageName && (it.totalTimeInForeground > 0 || it.lastTimeUsed >= start) }
            val pm = app.packageManager
            fun label(p: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
            val byTime = stats.sortedByDescending { it.totalTimeInForeground }.take(top).filter { it.totalTimeInForeground > 0 }
            val recent = stats.filter { it.lastTimeUsed >= start }.sortedByDescending { it.lastTimeUsed }.take(10)
            val total = stats.sumOf { it.totalTimeInForeground }
            ToolResult.sensitive(
                buildString {
                    append("Screen time in the last $hours h: ${When.duration(total)} across ${stats.count { it.totalTimeInForeground > 0 }} apps.\n")
                    append("Most used:\n")
                    byTime.forEach { append("  ${label(it.packageName)} (${it.packageName}): ${When.duration(it.totalTimeInForeground)}\n") }
                    append("Recently opened:\n")
                    recent.forEach { append("  ${label(it.packageName)}: ${When.ago(it.lastTimeUsed)}\n") }
                }.trimEnd(),
            )
        }
    }

    /** Full detail for refs this class produces. */
    fun read(ref: String, id: Long): String? = when (ref) {
        "sms" -> {
            app.requirePermission(Manifest.permission.READ_SMS, what = "Reading SMS")
            cr.query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) "SMS ${if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "to" else "from"} ${c.getString(0)} at ${When.at(c.getLong(2))}:\n${c.getString(1)}" else null }
        }
        "contact" -> {
            app.requirePermission(Manifest.permission.READ_CONTACTS, what = "Reading contacts")
            val name = cr.query(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            name?.let { it + contactDetails(id, short = false) }
        }
        "call" -> {
            app.requirePermission(Manifest.permission.READ_CALL_LOG, what = "Reading call history")
            cr.query(ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, id), arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.GEOCODED_LOCATION), null, null, null)
                ?.use { c -> if (c.moveToFirst()) "Call with ${c.getString(1) ?: ""} ${c.getString(0)} at ${When.at(c.getLong(2))}, ${When.duration(c.getLong(3) * 1000)}${c.getString(4)?.let { ", $it" }.orEmpty()}" else null }
        }
        "media" -> cr.query(
            ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DURATION),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val path = File(Environment.getExternalStorageDirectory(), c.getString(1).orEmpty() + c.getString(0)).path
            buildString {
                append("path: $path\ntype: ${c.getString(3)}\nsize: ${human(c.getLong(2))}\nmodified: ${When.at(c.getLong(4) * 1000)}")
                if (c.getInt(5) > 0) append("\ndimensions: ${c.getInt(5)}x${c.getInt(6)}")
                if (c.getLong(7) > 0) append("\nduration: ${When.duration(c.getLong(7))}")
            }
        }
        else -> null
    }
}

internal object Usage {
    @Suppress("DEPRECATION")
    fun granted(context: Context): Boolean {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        return runCatching {
            ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
