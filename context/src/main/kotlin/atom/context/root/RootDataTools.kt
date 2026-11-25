package atom.context.root

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.provider.Settings
import atom.context.notifications.Notifications
import atom.context.search.one
import atom.context.sources.CtxTool
import atom.context.sources.Missing
import atom.context.sources.When
import atom.context.sources.guarded
import atom.context.sources.rootShell
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.ShellExecutor
import atom.device.apps.AppResolver
import atom.device.control.ControlError
import atom.device.control.shQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root-only, read-only access to other apps' private data. Files are copied into Atom's cache, opened there and
 * deleted straight after, so the original is never opened for writing.
 */
internal object RootCopy {
    private fun dir(context: Context) = File(context.cacheDir, "rootcopy").apply { mkdirs() }

    /** Copies [src] (plus -wal/-shm) and returns the local copy; caller must [discard] it. */
    suspend fun copy(context: Context, sh: ShellExecutor, src: String): File {
        val dst = File(dir(context), "c${System.nanoTime()}_" + src.substringAfterLast('/').replace(Regex("[^\\w.-]"), "_"))
        val uid = Process.myUid()
        val script = buildString {
            append("[ -f ${shQuote(src)} ] || { echo NOFILE; exit 3; }\n")
            append("cp ${shQuote(src)} ${shQuote(dst.path)} && chown $uid:$uid ${shQuote(dst.path)} && chmod 600 ${shQuote(dst.path)} || exit 4\n")
            for (s in listOf("-wal", "-shm")) {
                append("[ -f ${shQuote(src + s)} ] && cp ${shQuote(src + s)} ${shQuote(dst.path + s)} && chown $uid:$uid ${shQuote(dst.path + s)} && chmod 600 ${shQuote(dst.path + s)}\n")
            }
            append("echo COPIED")
        }
        val r = sh.run(script, 30_000)
        if (r.stdout.contains("NOFILE")) throw Missing("$src does not exist (the app may not be installed or uses another path).")
        if (!r.stdout.contains("COPIED") || !dst.exists()) throw Missing("Copying $src failed: ${(r.stderr.ifBlank { r.stdout }).take(200)}")
        return dst
    }

    fun discard(f: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(f.path + it).delete() }
    }

    suspend fun <T> withDatabase(context: Context, sh: ShellExecutor, src: String, block: (SQLiteDatabase) -> T): T {
        val f = copy(context, sh, src)
        try {
            return withContext(Dispatchers.IO) {
                SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
            }
        } finally {
            discard(f)
        }
    }

    fun tables(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info(\"${table.replace("\"", "")}\")", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
}

internal class RootDataTools(context: Context) {
    private val app = context.applicationContext

    fun rootOnly(): List<Tool> = listOf(wifi(), clipboard(), appData())

    /** Works without root through notification history; root adds WhatsApp's own database. */
    fun triage(): Tool = messenger()

    private fun wifi() = CtxTool(
        name = "wifi_saved_passwords",
        description = "Saved Wi-Fi networks with their passwords (root). Optional filter by network name.",
        risk = Risk.Read,
        parameters = schema { string("ssid", "Only networks whose name contains this.") },
    ) { args, _ ->
        guarded {
            val sh = rootShell(app, "Reading saved Wi-Fi passwords")
            val r = sh.run("for f in ${WifiConfig.PATHS}; do [ -f \"\$f\" ] && cat \"\$f\" && break; done", 15_000)
            val nets = WifiConfig.parse(r.stdout)
            if (nets.isEmpty()) throw Missing("No saved networks found (the Wi-Fi store may be in a vendor-specific place on this phone).")
            val f = args.optString("ssid")?.lowercase()
            val shown = nets.filter { f == null || it.ssid.lowercase().contains(f) }
            ToolResult.sensitive(
                if (shown.isEmpty()) "No saved network matches \"$f\"." else shown.joinToString("\n") {
                    "${it.ssid} (${it.security}): ${it.password ?: if (it.security == "Open") "no password" else "password not stored in plain text"}"
                },
            )
        }
    }

    private fun clipboard() = CtxTool(
        name = "clipboard_history",
        description = "Clipboard history saved by the keyboard (root; Gboard and keyboards that keep it in a local " +
            "database). Newest first.",
        risk = Risk.Read,
        parameters = schema {
            integer("limit", "How many clips, default 15.")
            string("query", "Only clips containing this text.")
        },
    ) { args, _ ->
        guarded {
            val sh = rootShell(app, "Reading clipboard history")
            val ime = Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
                ?: throw Missing("No keyboard is set.")
            if (!ime.matches(Regex("[\\w.]+"))) throw Missing("Unexpected keyboard id $ime.")
            val dbs = sh.run("ls /data/data/$ime/databases/ 2>/dev/null", 10_000).stdout.lines()
                .map { it.trim() }.filter { it.endsWith(".db") && it.contains("clip", ignoreCase = true) }
            if (dbs.isEmpty()) {
                throw Missing("The keyboard ($ime) keeps no clipboard database Atom can read. Gboard's clipboard works; others may store it differently.")
            }
            val limit = (args.optInt("limit") ?: 15).coerceIn(1, 50)
            val q = args.optString("query")?.takeIf { it.isNotBlank() }
            val clips = RootCopy.withDatabase(app, sh, "/data/data/$ime/databases/${dbs.first()}") { db ->
                val table = RootCopy.tables(db).firstOrNull { t -> RootCopy.columns(db, t).contains("text") } ?: return@withDatabase emptyList()
                val cols = RootCopy.columns(db, table)
                val time = listOf("timestamp", "time", "created_time", "create_time", "date").firstOrNull { it in cols }
                val where = if (q != null) " WHERE text LIKE ? ESCAPE '\\'" else ""
                val order = time?.let { " ORDER BY \"$it\" DESC" } ?: " ORDER BY rowid DESC"
                db.rawQuery("SELECT text${time?.let { ", \"$it\"" }.orEmpty()} FROM \"$table\"$where$order LIMIT $limit", if (q != null) arrayOf(atom.context.search.likeArg(q)) else null).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val text = c.getString(0) ?: continue
                            val at = if (c.columnCount > 1) c.getLong(1).let { if (it < 10_000_000_000L) it * 1000 else it } else null
                            add((at?.let { When.ago(it) + ": " } ?: "") + one(text, 300))
                        }
                    }
                }
            }
            ToolResult.sensitive(if (clips.isEmpty()) "The keyboard's clipboard history is empty." else "Clipboard history (newest first):\n" + clips.joinToString("\n"))
        }
    }

    private fun appData() = CtxTool(
        name = "app_private_data",
        description = "Read-only look inside another app's private storage (root) when no dedicated tool exists: " +
            "list files, read a text file, or run a SELECT on one of its databases (on a temporary copy).",
        risk = Risk.Read,
        parameters = schema {
            string("app", "App name or package.", required = true)
            string("action", "What to do.", required = true, enum = listOf("list", "read", "query", "tables"))
            string("path", "File or database path relative to the app's data folder, e.g. databases/app.db or shared_prefs/x.xml.")
            string("sql", "For query: a SELECT (or PRAGMA) statement.")
        },
    ) { args, _ ->
        guarded {
            val sh = rootShell(app, "Inspecting app data")
            val pkg = try {
                AppResolver.resolve(app, args.string("app")).packageName
            } catch (e: ControlError) {
                throw Missing(e.message ?: "No such app.")
            }
            val base = "/data/data/$pkg"
            val rel = args.optString("path")?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() }
            if (rel != null && (rel.contains("..") || rel.contains('\u0000'))) throw ToolArgumentException("path must stay inside the app's data folder.")
            val full = if (rel == null) base else "$base/$rel"
            when (args.string("action").lowercase()) {
                "list" -> {
                    val r = sh.run("ls -la ${shQuote(full)} 2>&1 | head -n 200", 15_000)
                    ToolResult.sensitive(r.stdout.ifBlank { "(empty)" })
                }
                "read" -> {
                    rel ?: throw ToolArgumentException("read needs path.")
                    val r = sh.run("head -c 20000 ${shQuote(full)} | tr -d '\\000'", 15_000)
                    ToolResult.sensitive(r.stdout.ifBlank { r.stderr.ifBlank { "(empty file)" } })
                }
                "tables" -> {
                    rel ?: throw ToolArgumentException("tables needs the database path.")
                    val text = RootCopy.withDatabase(app, sh, full) { db ->
                        RootCopy.tables(db).joinToString("\n") { t -> "$t(" + RootCopy.columns(db, t).joinToString(", ") + ")" }
                    }
                    ToolResult.sensitive(text.ifBlank { "(no tables)" }.take(8_000))
                }
                "query" -> {
                    rel ?: throw ToolArgumentException("query needs the database path.")
                    val sql = args.optString("sql")?.trim()?.trimEnd(';') ?: throw ToolArgumentException("query needs sql.")
                    val head = sql.lowercase().trimStart()
                    if (!(head.startsWith("select") || head.startsWith("pragma") || head.startsWith("with")) || sql.contains(';')) {
                        throw ToolArgumentException("Only a single SELECT, WITH or PRAGMA statement is allowed.")
                    }
                    val text = RootCopy.withDatabase(app, sh, full) { db -> db.rawQuery(sql, null).use { c -> table(c, 100) } }
                    ToolResult.sensitive(text)
                }
                else -> throw ToolArgumentException("action must be list, read, tables or query.")
            }
        }
    }

    private fun table(c: Cursor, maxRows: Int): String = buildString {
        append(c.columnNames.joinToString(" | ")).append('\n')
        var n = 0
        while (c.moveToNext() && n++ < maxRows) {
            append((0 until c.columnCount).joinToString(" | ") { i ->
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> "null"
                    Cursor.FIELD_TYPE_BLOB -> "<blob ${c.getBlob(i).size} B>"
                    else -> one(c.getString(i), 200)
                }
            }).append('\n')
        }
        if (c.count > maxRows) append("… ${c.count - maxRows} more rows")
    }.take(12_000)

    // ---- messenger triage -------------------------------------------------------------------------

    private val apps = listOf("whatsapp", "whatsapp_business", "telegram", "discord", "signal", "other")

    private fun messenger() = CtxTool(
        name = "messenger_triage",
        description = "Recent messages grouped per chat for triage, read-only. WhatsApp: its own database with root, " +
            "else notification history. Telegram, Discord and others: notification history. Signal cannot be read.",
        risk = Risk.Read,
        parameters = schema {
            string("app", "Which messenger.", required = true, enum = apps)
            integer("hours", "Look back this many hours, default 24.")
            string("package", "For app=other: the messenger's package or name.")
        },
    ) { args, _ ->
        guarded {
            val which = args.string("app").lowercase()
            val hours = (args.optInt("hours") ?: 24).coerceIn(1, 168)
            val since = System.currentTimeMillis() - hours * 3_600_000L
            when (which) {
                "signal" -> ToolResult.ok(
                    "Signal cannot be read: its messages sit in a database encrypted with a key held in Android's Keystore, " +
                        "which even root cannot extract. If Signal shows previews in notifications, notification_history " +
                        "(app=signal) has whatever the notifications contained.",
                )
                "whatsapp", "whatsapp_business" -> {
                    val pkg = if (which == "whatsapp") "com.whatsapp" else "com.whatsapp.w4b"
                    val sh = atom.device.Shells.detect(app)
                    if (sh.tier == atom.device.ShellTier.Root) {
                        try {
                            return@guarded ToolResult.sensitive(whatsApp(sh, pkg, since, hours))
                        } catch (e: Missing) {
                            val fallback = fromNotifications(pkg, since, hours)
                            return@guarded ToolResult.sensitive("WhatsApp database not readable (${e.message}). From notifications instead:\n$fallback")
                        }
                    }
                    ToolResult.sensitive("(No root: from notification history, which only has what WhatsApp showed in notifications.)\n" + fromNotifications(pkg, since, hours))
                }
                else -> {
                    val pkg = when (which) {
                        "telegram" -> listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
                        "discord" -> listOf("com.discord")
                        else -> listOf(
                            args.optString("package")?.let {
                                try { AppResolver.resolve(app, it).packageName } catch (e: ControlError) { throw Missing(e.message ?: "No such app.") }
                            } ?: throw ToolArgumentException("app=other needs package."),
                        )
                    }
                    ToolResult.sensitive(pkg.joinToString("\n") { fromNotifications(it, since, hours) }.ifBlank { "Nothing recorded." })
                }
            }
        }
    }

    private fun fromNotifications(pkg: String, since: Long, hours: Int): String {
        if (!Notifications.accessGranted(app)) return "Notification access is off, so there is no history (Permissions page)."
        val (rows, _) = Notifications.history(app).search(null, pkg, since, 0, 300)
        if (rows.isEmpty()) return "No $pkg notifications in the last $hours h."
        val chats = rows.groupBy { it.conversation ?: it.title }.entries.sortedByDescending { e -> e.value.maxOf { it.postedAt } }
        return chats.take(25).joinToString("\n") { (chat, msgs) ->
            "• ${one(chat, 60)} — ${msgs.size} notification${if (msgs.size == 1) "" else "s"}, last ${When.ago(msgs.maxOf { it.postedAt })}\n" +
                msgs.sortedBy { it.postedAt }.takeLast(4).joinToString("\n") { "    ${one(it.text, 160)}" }
        }
    }

    private suspend fun whatsApp(sh: ShellExecutor, pkg: String, since: Long, hours: Int): String {
        val names = runCatching {
            RootCopy.withDatabase(app, sh, "/data/data/$pkg/databases/wa.db") { db ->
                db.rawQuery("SELECT jid, COALESCE(display_name, wa_name) FROM wa_contacts WHERE COALESCE(display_name, wa_name) IS NOT NULL", null).use { c ->
                    buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
                }
            }
        }.getOrDefault(emptyMap())
        data class Msg(val chat: String, val at: Long, val fromMe: Boolean, val sender: String?, val text: String)
        val msgs = RootCopy.withDatabase(app, sh, "/data/data/$pkg/databases/msgstore.db") { db ->
            val tables = RootCopy.tables(db)
            val out = mutableListOf<Msg>()
            if ("message" in tables && "chat" in tables && "jid" in tables) {
                db.rawQuery(
                    "SELECT j.raw_string, m.timestamp, m.from_me, sj.raw_string, m.text_data, c.subject FROM message m " +
                        "JOIN chat c ON m.chat_row_id = c._id JOIN jid j ON c.jid_row_id = j._id " +
                        "LEFT JOIN jid sj ON m.sender_jid_row_id = sj._id " +
                        "WHERE m.timestamp >= ? AND m.text_data IS NOT NULL AND m.text_data != '' ORDER BY m.timestamp DESC LIMIT 500",
                    arrayOf(since.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val jid = c.getString(0) ?: continue
                        val chat = c.getString(5)?.takeIf { it.isNotBlank() } ?: names[jid] ?: jid.substringBefore('@')
                        out += Msg(chat, c.getLong(1), c.getInt(2) == 1, c.getString(3)?.let { names[it] ?: it.substringBefore('@') }, c.getString(4))
                    }
                }
            } else if ("messages" in tables) {
                db.rawQuery(
                    "SELECT key_remote_jid, timestamp, key_from_me, remote_resource, data FROM messages WHERE timestamp >= ? AND data IS NOT NULL ORDER BY timestamp DESC LIMIT 500",
                    arrayOf(since.toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        val jid = c.getString(0) ?: continue
                        out += Msg(names[jid] ?: jid.substringBefore('@'), c.getLong(1), c.getInt(2) == 1, c.getString(3)?.takeIf { it.isNotBlank() }?.let { names[it] ?: it.substringBefore('@') }, c.getString(4))
                    }
                }
            } else {
                throw Missing("this WhatsApp version uses a database layout Atom does not know")
            }
            out
        }
        if (msgs.isEmpty()) return "No WhatsApp text messages in the last $hours h."
        val chats = msgs.groupBy { it.chat }.entries.sortedByDescending { e -> e.value.maxOf { it.at } }
        return "WhatsApp, last $hours h: ${msgs.size} messages in ${chats.size} chats (read-only copy, deleted after reading).\n" +
            chats.take(25).joinToString("\n") { (chat, list) ->
                val incoming = list.count { !it.fromMe }
                "• $chat — ${list.size} messages ($incoming from them), last ${When.ago(list.maxOf { it.at })}\n" +
                    list.sortedBy { it.at }.takeLast(5).joinToString("\n") { m ->
                        "    ${if (m.fromMe) "me" else m.sender ?: "them"}: ${one(m.text, 160)}"
                    }
            }
    }
}
