package atom.device.undo

import android.content.Context
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.schema
import atom.device.tools.SimpleTool
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class UndoTools(context: Context) {
    private val journal = Undo.journal(context)
    private val time = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())

    fun all(): List<Tool> = listOf(list(), undoLast(), undoOne(), undoToday())

    private fun line(e: JournalEntry) = buildString {
        append('#').append(e.id).append(' ').append(time.format(Instant.ofEpochMilli(e.atMillis))).append("  ").append(e.summary)
        if (!e.verified) append(" (unconfirmed)")
        when {
            e.undoneAtMillis != null -> append("  [undone]")
            e.undoData == null -> append("  [cannot be undone]")
        }
    }

    private fun list() = SimpleTool(
        name = "list_changes",
        description = "The undo journal: state changes Atom's tools made (newest first), with ids for undo_change.",
        risk = Risk.Read,
        parameters = schema {
            boolean("today_only", "Only today's changes. Default false.")
            integer("limit", "How many, default 20.")
        },
    ) { args, _ ->
        val all = if (args.optBoolean("today_only") == true) journal.today() else journal.entries.value
        if (all.isEmpty()) return@SimpleTool ToolResult.ok("No changes recorded.")
        val limit = (args.optInt("limit") ?: 20).coerceIn(1, 100)
        ToolResult.ok(all.asReversed().take(limit).joinToString("\n", transform = ::line))
    }

    private fun undoLast() = SimpleTool(
        name = "undo_last_change",
        description = "Undo the most recent state change Atom made (a toggle, volume, firewall rule, frozen app, setting...). " +
            "Restores the recorded before-state and reports whether it was verified.",
        risk = Risk.Act,
        parameters = schema(),
    ) { _, _ ->
        val e = journal.lastUndoable() ?: return@SimpleTool ToolResult.ok("There is nothing to undo.")
        val r = journal.undo(e.id)
        ToolResult(if (r.ok) "Undid #${e.id} (${e.summary}): ${r.message}" else "Could not undo #${e.id} (${e.summary}): ${r.message}", isError = !r.ok)
    }

    private fun undoOne() = SimpleTool(
        name = "undo_change",
        description = "Undo one change from list_changes by its id.",
        risk = Risk.Act,
        parameters = schema { integer("id", "Change id, e.g. 12 for #12.", required = true) },
    ) { args, _ ->
        val id = args.optInt("id")?.toLong() ?: return@SimpleTool ToolResult.error("Missing id.")
        val r = journal.undo(id)
        ToolResult(r.message, isError = !r.ok)
    }

    private fun undoToday() = SimpleTool(
        name = "undo_changes_today",
        description = "Undo every change Atom made today, newest first, putting things back as they were this morning.",
        risk = Risk.Act,
        parameters = schema(),
    ) { _, _ ->
        val results = journal.undoToday()
        if (results.isEmpty()) return@SimpleTool ToolResult.ok("Nothing from today can be undone.")
        val ok = results.count { it.second.ok }
        ToolResult.ok(
            "Undid $ok of ${results.size} changes:\n" +
                results.joinToString("\n") { (e, r) -> (if (r.ok) "✓ " else "✗ ") + "#${e.id} ${e.summary}: ${r.message}" },
        )
    }
}
