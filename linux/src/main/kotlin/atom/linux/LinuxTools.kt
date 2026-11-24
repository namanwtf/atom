package atom.linux

import android.content.Context
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.schema
import atom.core.tool.string
import kotlinx.serialization.json.JsonObject

/** Agent tools for the on-device Linux environment. */
object LinuxTools {
    fun create(context: Context): List<Tool> = listOf(LinuxRunTool(LinuxEnv.get(context)))
}

internal class LinuxRunTool(private val env: LinuxEnv) : Tool {
    override val risk = Risk.Sensitive

    override val spec = ToolSpec(
        name = "linux_run",
        description = "Run a shell command inside the phone's own Linux environment (Alpine or Debian, installed " +
            "by the user in Atom). You are root inside it; install software with `apk add` (Alpine) or " +
            "`apt-get install -y` (Debian); network works; the phone's shared storage is at /sdcard. Each call " +
            "is a fresh login shell, so cd and exports do not persist (chain with &&). Use it for scripting, " +
            "git, python, compilers and file processing. Returns exit code, stdout and stderr (truncated).",
        parameters = schema {
            string("command", "The sh command line to run.", required = true)
            integer("timeout_s", "Seconds before the command and everything it started is killed, 1-600. Default 120.")
        },
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.string("command")
        val timeout = (args.optInt("timeout_s") ?: 120).coerceIn(1, 600) * 1_000L
        when (val s = env.state.value) {
            is LinuxState.Ready -> Unit
            is LinuxState.Installing -> return ToolResult.error(
                "Linux (${s.distro.displayName}) is still being installed (${s.step}, ${(s.progress * 100).toInt()}%). " +
                    "Wait for the install to finish, then try again.",
            )
            else -> return ToolResult.error(
                "Linux is not installed on this phone. Ask the user to open Atom's Linux screen and tap Install " +
                    "(Alpine is about 4 MB; Debian is larger but has more packages), then try again.",
            )
        }
        val r = env.run(command, timeout)
        val body = buildString {
            append("exit ").append(r.exitCode)
            if (r.timedOut) append(" (timed out after ${timeout / 1000}s; killed)")
            if (r.stdout.isNotBlank()) append("\nstdout:\n").append(cap(r.stdout.trimEnd(), if (r.stderr.isBlank()) 3_900 else 2_900))
            if (r.stderr.isNotBlank()) append("\nstderr:\n").append(cap(r.stderr.trimEnd(), 1_000))
            if (r.stdout.isBlank() && r.stderr.isBlank()) append(" (no output)")
        }
        return ToolResult(body, isError = r.exitCode != 0)
    }

    /** Keeps the head and the tail: errors and summaries usually sit at the end. */
    private fun cap(s: String, max: Int): String {
        if (s.length <= max) return s
        val head = max * 2 / 5
        val tail = max - head
        return s.take(head) + "\n… [${s.length - max} chars cut] …\n" + s.takeLast(tail)
    }
}
