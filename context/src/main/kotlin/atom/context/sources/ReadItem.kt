package atom.context.sources

import android.content.Context
import atom.context.calendar.CalendarTools
import atom.context.notifications.Notifications
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolResult
import atom.core.tool.schema
import atom.core.tool.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Read the full detail of an earlier hit": every search result carries a ref like sms:123 or event:45. */
internal class ReadItem(context: Context, private val personal: PersonalTools, private val calendar: CalendarTools) {
    private val app = context.applicationContext

    fun tool(): Tool = CtxTool(
        name = "read_item",
        description = "Open the full detail of a search hit by its ref (sms:…, contact:…, call:…, event:…, notif:…, media:…).",
        risk = Risk.Read,
        parameters = schema { string("ref", "The ref shown in brackets, e.g. sms:1234.", required = true) },
    ) { args, _ ->
        guarded {
            val ref = args.string("ref").trim().removePrefix("[").removeSuffix("]")
            val kind = ref.substringBefore(':').lowercase()
            val id = ref.substringAfter(':', "").toLongOrNull() ?: throw ToolArgumentException("A ref looks like sms:1234.")
            val text = withContext(Dispatchers.IO) {
                when (kind) {
                    "event" -> calendar.detail(id)
                    "notif" -> {
                        if (!Notifications.accessGranted(app)) throw Missing(atom.ui.permission.PermissionAsks.toolMessage(app, JitAsks.notificationAccess(app), "Reading notifications"))
                        Notifications.history(app).byId(id)?.let {
                            "${it.appLabel} (${it.packageName}) at ${When.at(it.postedAt)}${it.conversation?.let { c -> " in $c" }.orEmpty()}\n${it.title}\n${it.text}"
                        }
                    }
                    "sms", "contact", "call", "media" -> personal.read(kind, id)
                    else -> throw ToolArgumentException("Unknown ref kind '$kind'.")
                }
            }
            if (text == null) ToolResult.error("Nothing found for $ref (it may have been deleted).") else ToolResult.sensitive(text)
        }
    }
}
