package atom.overlay.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What was on screen when the assistant opened. */
data class ScreenContext(
    val appLabel: String?,
    val packageName: String?,
    val url: String?,
    val text: String,
    val capturedAt: Long,
)

/** Rules for what screen text may leave the device in a prompt. */
object ScreenText {
    private const val CLASS_MASK = 0x0000000f
    private const val VARIATION_MASK = 0x00000ff0
    private const val CLASS_TEXT = 0x00000001
    private const val CLASS_NUMBER = 0x00000002
    private val textPasswords = setOf(0x00000080, 0x00000090, 0x000000e0)
    private const val NUMBER_PASSWORD = 0x00000010
    private val sensitiveHints = listOf("password", "creditcard", "cardnumber", "securitycode", "cvc", "cvv", "otp")
    private val sensitiveIds = listOf("password", "passwd", "pwd", "cvv", "cvc")

    /** Password, card-number and one-time-code fields are never captured. */
    fun isSensitive(inputType: Int, autofillHints: Array<String>?, idEntry: String?): Boolean {
        val cls = inputType and CLASS_MASK
        val variation = inputType and VARIATION_MASK
        if (cls == CLASS_TEXT && variation in textPasswords) return true
        if (cls == CLASS_NUMBER && variation == NUMBER_PASSWORD) return true
        if (autofillHints?.any { hint -> val h = hint.lowercase().replace("_", ""); sensitiveHints.any { h.contains(it) } } == true) return true
        val id = idEntry?.lowercase() ?: return false
        return sensitiveIds.any { id.contains(it) }
    }

    /** Collects visible strings in reading order, without duplicates, up to [maxChars]. */
    class Collector(private val maxChars: Int) {
        private val out = StringBuilder()
        private val seen = HashSet<String>()

        private var truncated = false

        val full: Boolean get() = truncated || out.length >= maxChars

        fun add(value: CharSequence?) {
            if (value == null || full) return
            val line = value.toString().replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty() || !seen.add(line)) return
            val room = maxChars - out.length - (if (out.isEmpty()) 0 else 1)
            if (room <= 0) return
            if (out.isNotEmpty()) out.append('\n')
            if (line.length <= room) {
                out.append(line)
            } else {
                out.append(line.substring(0, (room - 1).coerceAtLeast(0)).trimEnd()).append('…')
                truncated = true
            }
        }

        fun text(): String = out.toString()
    }
}

/** The text sent with an assistant question. */
object AssistPrompt {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun build(
        question: String,
        context: ScreenContext?,
        firstTurn: Boolean,
        hasScreenshot: Boolean,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (context == null) return question
        val at = clock.format(Instant.ofEpochMilli(context.capturedAt).atZone(zone))
        if (!firstTurn) return "$question\n\n[Same screen context as before, captured at $at (${age(now - context.capturedAt)})]"
        return buildString {
            append(question)
            append("\n\n[Screen context captured at $at, when the assistant opened")
            append(if (hasScreenshot) ". The attached screenshot is from the same moment.]" else ".]")
            val app = listOfNotNull(context.appLabel, context.packageName?.let { "($it)" }).joinToString(" ")
            if (app.isNotEmpty()) append("\nApp: ").append(app)
            if (!context.url.isNullOrBlank()) append("\nURL: ").append(context.url)
            if (context.text.isNotEmpty()) append("\nVisible text:\n").append(context.text)
        }
    }

    fun age(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "1 minute ago"
            minutes < 60 -> "$minutes minutes ago"
            else -> "${minutes / 60} h ago"
        }
    }
}

/** Reads the assist structure the system handed the session. Runs off the main thread. */
internal object AssistExtract {
    private const val MAX_TEXT = 6_000

    fun read(context: Context, structure: AssistStructure?, content: AssistContent?, capturedAt: Long): ScreenContext {
        val collector = ScreenText.Collector(MAX_TEXT)
        var domain: String? = null
        if (structure != null) {
            for (i in 0 until structure.windowNodeCount) {
                val root = structure.getWindowNodeAt(i).rootViewNode ?: continue
                domain = walk(root, collector) ?: domain
                if (collector.full) break
            }
        }
        val pkg = structure?.activityComponent?.packageName
        val url = content?.webUri?.toString() ?: domain?.let { "https://$it" }
        return ScreenContext(label(context, pkg), pkg, url, collector.text(), capturedAt)
    }

    private fun walk(node: AssistStructure.ViewNode, collector: ScreenText.Collector): String? {
        if (node.visibility != View.VISIBLE || collector.full) return null
        var domain = node.webDomain
        if (!ScreenText.isSensitive(node.inputType, node.autofillHints, node.idEntry)) {
            val text = node.text
            if (!text.isNullOrBlank()) collector.add(text) else collector.add(node.contentDescription)
        }
        for (i in 0 until node.childCount) {
            val child = walk(node.getChildAt(i), collector)
            if (domain == null) domain = child
        }
        return domain
    }

    private fun label(context: Context, pkg: String?): String? {
        pkg ?: return null
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
