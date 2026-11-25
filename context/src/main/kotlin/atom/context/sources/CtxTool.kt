package atom.context.sources

import android.content.Context
import android.content.pm.PackageManager
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.device.ShellExecutor
import atom.device.ShellTier
import atom.device.Shells
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class CtxTool(
    name: String,
    description: String,
    override val risk: Risk,
    parameters: JsonObject,
    private val body: suspend (JsonObject, ToolContext) -> ToolResult,
) : Tool {
    override val spec = ToolSpec(name, description, parameters)
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = body(args, ctx)
}

/** A tool error the model can act on, thrown from helpers. */
internal class Missing(message: String) : Exception(message)

internal fun Context.requirePermission(vararg perms: String, what: String) {
    if (perms.none { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
        throw Missing("$what needs the ${perms.first().substringAfterLast('.').replace('_', ' ').lowercase()} permission. Ask the user to grant it on Atom's Permissions page.")
    }
}

internal suspend fun rootShell(context: Context, what: String): ShellExecutor {
    val sh = Shells.detect(context)
    if (sh.tier != ShellTier.Root) throw Missing("$what needs root, which is not available or not granted (Permissions → Root).")
    return sh
}

internal suspend fun privilegedShell(context: Context, what: String): ShellExecutor {
    val sh = Shells.detect(context)
    if (sh.tier == ShellTier.App) throw Missing("$what needs Shizuku or root (see Permissions).")
    return sh
}

/** Runs [block], turning [Missing] into a tool error. */
internal suspend fun guarded(block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: Missing) {
    ToolResult.error(e.message ?: "Not available.")
} catch (e: SecurityException) {
    ToolResult.error("Android refused access: ${e.message}. Check Atom's Permissions page.")
}

internal object When {
    private val dateTime = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.US)

    fun at(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = dateTime.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val d = Duration.ofMillis((now - millis).coerceAtLeast(0))
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 48 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} days ago"
        }
    }

    fun duration(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "${ms / 1000} s"
            m < 60 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }
}
