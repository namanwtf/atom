package atom.context.notifications

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import atom.context.search.Slice
import atom.context.search.one
import atom.context.sources.CtxTool
import atom.context.sources.Missing
import atom.context.sources.When
import atom.context.sources.guarded
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.device.apps.AppResolver
import atom.device.control.ControlError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class NotificationTools(context: Context) {
    private val app = context.applicationContext

    fun sensitive(): List<Tool> = listOf(current(), history())

    fun everyday(): List<Tool> = listOf(media())

    private fun requireAccess() {
        if (!Notifications.accessGranted(app)) {
            throw Missing("Notification access is off. Ask the user to switch on \"Atom notification history\" on the Permissions page; history records from then on.")
        }
    }

    private fun current() = CtxTool(
        name = "notifications_current",
        description = "Notifications showing in the shade right now (app, title, text), newest first.",
        risk = Risk.Read,
        parameters = schema {
            string("app", "Only this app (name or package).")
            integer("limit", "Max entries, default 30.")
        },
    ) { args, _ ->
        guarded {
            requireAccess()
            val listener = AtomNotificationListener.instance
                ?: throw Missing("Notification access is on but the listener is not connected yet; try again in a moment.")
            val pkg = args.optString("app")?.takeIf { it.isNotBlank() }?.let { resolve(it) }
            val limit = (args.optInt("limit") ?: 30).coerceIn(1, 100)
            val active = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
                .filter { it.packageName != app.packageName && (pkg == null || it.packageName == pkg) }
                .sortedByDescending { it.postTime }
                .mapNotNull { AtomNotificationListener.toRecord(app, it) }
                .take(limit)
            if (active.isEmpty()) return@guarded ToolResult.ok("No notifications are showing.")
            ToolResult.sensitive(
                "${active.size} notifications:\n" + active.joinToString("\n") {
                    "${When.ago(it.postedAt)} ${it.appLabel}: ${one(it.title, 80)}${if (it.text.isNotBlank()) " — " + one(it.text, 220) else ""}"
                },
            )
        }
    }

    private suspend fun resolve(q: String): String = try {
        AppResolver.resolve(app, q).packageName
    } catch (e: ControlError) {
        throw Missing(e.message ?: "No such app.")
    }

    private fun history() = CtxTool(
        name = "notification_history",
        description = "Search notification history kept on this phone (last 7 days, up to 1000) by text, app and hours. " +
            "Telegram, Discord and other chats can be triaged from here. Each hit has a ref for read_item.",
        risk = Risk.Read,
        parameters = schema {
            string("query", "Text in the title, message, app or conversation name.")
            string("app", "Only this app (name or package).")
            integer("hours", "Only the last N hours.")
            integer("limit", "Page size, default 20.")
            integer("offset", "Paging offset.")
        },
    ) { args, _ ->
        guarded {
            requireAccess()
            val h = Notifications.history(app)
            val pkg = args.optString("app")?.takeIf { it.isNotBlank() }?.let { resolve(it) }
            val since = args.optInt("hours")?.let { System.currentTimeMillis() - it.coerceIn(1, 168) * 3_600_000L }
            val limit = Slice.clampLimit(args.optInt("limit"))
            val offset = Slice.clampOffset(args.optInt("offset"))
            val (rows, total) = withContext(Dispatchers.IO) { h.search(args.optString("query"), pkg, since, offset, limit) }
            val stored = withContext(Dispatchers.IO) { h.count() }
            val slice = Slice(rows, offset, total, limit)
            val text = slice.render("notifications") {
                "[notif:${it.id}] ${When.at(it.postedAt)} ${it.appLabel}${it.conversation?.let { c -> " · $c" }.orEmpty()}: ${one(it.title, 80)} — ${one(it.text, 200)}"
            }
            ToolResult.sensitive(if (stored == 0) "$text\n(History is empty: it records from the moment notification access was granted.)" else text)
        }
    }

    // ---- media ------------------------------------------------------------------------------------

    private val actions = listOf("play", "pause", "toggle", "next", "previous", "stop", "status")

    private fun media() = CtxTool(
        name = "media_control",
        description = "Control what is playing without touching the screen: play, pause, toggle, next, previous, stop, " +
            "or status. Reports the player and state before → after.",
        risk = Risk.Act,
        parameters = schema { string("action", "What to do.", required = true, enum = actions) },
    ) { args, _ ->
        val action = args.string("action").lowercase()
        if (action !in actions) throw ToolArgumentException("action must be one of ${actions.joinToString()}")
        val controller = activeController()
        if (action == "status") {
            return@CtxTool ToolResult.ok(controller?.let { describe(it) } ?: if (Notifications.accessGranted(app)) "Nothing is playing." else
                "Cannot see players without notification access; media keys still work.")
        }
        if (controller != null) {
            val before = describe(controller)
            val t = controller.transportControls
            when (action) {
                "play" -> t.play()
                "pause" -> t.pause()
                "toggle" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
                "stop" -> t.stop()
            }
            delay(700)
            val after = describe(controller)
            val verified = after != before
            return@CtxTool ToolResult.ok("$before → $after (${if (verified) "verified" else "unconfirmed"}).")
        }
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        val am = app.getSystemService(AudioManager::class.java) ?: return@CtxTool ToolResult.error("Audio service unavailable.")
        val wasPlaying = am.isMusicActive
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        delay(700)
        val playing = am.isMusicActive
        ToolResult.ok(
            "Sent $action to the active player. Audio: ${if (wasPlaying) "playing" else "silent"} → ${if (playing) "playing" else "silent"}" +
                if (wasPlaying != playing) " (verified)." else " (unconfirmed; grant notification access to see the player's state).",
        )
    }

    private fun activeController(): MediaController? {
        if (!Notifications.accessGranted(app)) return null
        val msm = app.getSystemService(MediaSessionManager::class.java) ?: return null
        val list = runCatching { msm.getActiveSessions(AtomNotificationListener.component(app)) }.getOrNull().orEmpty()
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }

    private fun describe(c: MediaController): String {
        val state = when (c.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_STOPPED -> "stopped"
            PlaybackState.STATE_BUFFERING -> "buffering"
            else -> "idle"
        }
        val md = c.metadata
        val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val label = AtomNotificationListener.label(app, c.packageName)
        return "$label $state" + (title?.let { " \"$it\"" + (artist?.let { a -> " by $a" }.orEmpty()) }.orEmpty())
    }
}
