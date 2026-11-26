package atom.app.data

import atom.core.llm.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A readable Markdown record of one conversation: both sides, thinking, tool calls with arguments and results. */
object ConversationExport {
    fun markdown(c: Conversation, now: Long = System.currentTimeMillis()): String = buildString {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        append("# ").append(c.title.ifBlank { "Conversation" }).append("\n\n")
        append("_Exported from Atom on ").append(date.format(Date(now)))
        append(" · started ").append(date.format(Date(c.createdAt))).append("_\n")
        var images = 0
        for (m in c.messages) {
            val msg = m.message
            when {
                // Agent-internal turns (loop-guard nudges, the run's own tool preload) are not part of the record.
                m.hidden -> Unit
                m.kind == EntryKind.Notice -> append("\n> ").append(msg.text.replace("\n", "\n> ")).append('\n')
                m.kind == EntryKind.Summary -> {
                    append("\n<details><summary>Context summary</summary>\n\n")
                    append(msg.text.substringAfter('\n')).append("\n\n</details>\n")
                }
                msg.role == Role.User -> {
                    append("\n## User\n\n").append(msg.text).append('\n')
                    m.attachments.forEach { a ->
                        if (a.kind == AttachmentKind.Image) images++
                        append("\n- Attached ").append(a.kind.name.lowercase()).append(": `").append(a.path).append('`')
                    }
                    if (m.attachments.isNotEmpty()) append('\n')
                }
                msg.role == Role.Assistant -> {
                    append("\n## Atom\n\n")
                    m.reasoning?.takeIf { it.isNotBlank() }?.let { r ->
                        append("<details><summary>Thinking")
                        m.reasoningMs?.let { append(" · ").append((it + 500) / 1000).append(" s") }
                        append("</summary>\n\n").append(r.trim()).append("\n\n</details>\n\n")
                    }
                    if (msg.text.isNotBlank()) append(msg.text.trim()).append('\n')
                    msg.toolCalls.forEach { call ->
                        append("\n**Tool call:** `").append(call.name).append("`\n\n```json\n")
                            .append(call.argumentsJson.ifBlank { "{}" }).append("\n```\n")
                    }
                }
                msg.role == Role.Tool -> {
                    images += m.message.parts.count { it is atom.core.llm.ContentPart.Image }
                    append("\n**Result")
                    msg.toolName?.let { append(" of `").append(it).append('`') }
                    if (m.isError) append(" (failed)")
                    m.durationMs?.let { append(" · ").append(it).append(" ms") }
                    append(":**\n\n```\n").append(msg.text.trim().replace("```", "ˋˋˋ")).append("\n```\n")
                }
            }
        }
        if (images > 0) append("\n---\n_").append(images).append(" image(s) not included._\n")
    }

    fun fileName(c: Conversation): String {
        val base = c.title.ifBlank { "conversation" }.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(' ', '-').take(48)
        return base.ifBlank { "conversation" } + ".md"
    }
}
