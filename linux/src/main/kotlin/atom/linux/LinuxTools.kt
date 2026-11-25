package atom.linux

import android.content.Context
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optBoolean
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import atom.linux.jobs.JobKind
import atom.linux.jobs.JobManager
import atom.linux.jobs.JobRecord
import atom.linux.jobs.JobRules
import atom.linux.jobs.JobState
import atom.linux.launch.LaunchSpec
import atom.linux.launch.ProcessRunner
import atom.linux.term.SessionMode
import atom.linux.term.SessionOwner
import atom.linux.term.SimpleBlock
import atom.linux.term.TerminalSessions
import kotlinx.serialization.json.JsonObject

/**
 * Agent tools for terminals and Linux: one-shot commands, persistent sessions, background jobs, daemons and a root
 * check. Every result starts with the exact command, and output is capped.
 */
object LinuxTools {
    fun create(context: Context): List<Tool> {
        val app = context.applicationContext
        return listOf(
            RunTool(app), SessionTool(app), SessionReadTool(), JobStartTool(app), JobInfoTool(app), JobStopTool(app), RootCheckTool(app),
        )
    }

    internal const val CAP = 4_000

    internal fun parseEnv(value: String?): ShellEnv? = when (value?.lowercase()) {
        null, "", "linux" -> ShellEnv.Linux
        "android", "app", "shell" -> ShellEnv.Android
        "root", "su" -> ShellEnv.Root
        else -> null
    }

    internal const val ENV_HELP = "Where to run: \"linux\" (the installed distro, as root inside it; default), " +
        "\"android\" (the phone's /system/bin/sh as the app user) or \"root\" (Android root shell via su)."

    /** Keeps the head and the tail: errors and summaries usually sit at the end. */
    internal fun cap(s: String, max: Int): String {
        if (s.length <= max) return s
        val head = max * 2 / 5
        val tail = max - head
        return s.take(head) + "\n… [${s.length - max} chars cut] …\n" + s.takeLast(tail)
    }

    internal fun linuxProblem(env: LinuxEnv): String? = when (val s = env.state.value) {
        is LinuxState.Ready -> if (s.rootLost) {
            "Linux is set to Root mode but root access is gone. Ask the user to switch Linux to Standard mode (PRoot) on the Linux screen."
        } else {
            null
        }
        is LinuxState.Installing -> "Linux (${s.distro.displayName}) is still being installed (${s.step}, ${(s.progress * 100).toInt()}%). Wait for it to finish."
        else -> "Linux is not installed on this phone. Ask the user to open Atom's Linux screen and tap Install " +
            "(Alpine is about 4 MB; Debian is larger but has more packages), then try again."
    }

    internal fun blockText(command: String, b: SimpleBlock?): String = buildString {
        append("$ ").append(command).append('\n')
        if (b == null) {
            append("(no output yet)")
            return@buildString
        }
        val out = b.outputText()
        if (out.isNotEmpty()) append(cap(out, CAP)).append('\n')
        append(if (b.running) "[still running: send input with action=send, or read again later]" else "[exit ${b.exitCode}]")
    }
}

private class RunTool(private val app: Context) : Tool {
    override val risk = Risk.Sensitive
    // timeout_s goes up to 600; the command enforces its own limit, this only has to outlast it.
    override val timeoutMs: Long = 615_000
    override val spec = ToolSpec(
        name = "terminal_run",
        description = "Run one shell command and wait for it. In Linux you are root inside the distro: install software " +
            "with `apk add` (Alpine) or `apt-get install -y` (Debian); git, curl, jq, rg, fd, sqlite are there after the " +
            "base tools; /workspace is your private working folder and /sdcard the phone's shared storage. Each call is a " +
            "fresh login shell (chain with &&). For long work use terminal_job_start; for interactive programs use terminal_session.",
        parameters = schema {
            string("command", "The sh command line.", required = true)
            string("env", LinuxTools.ENV_HELP, enum = listOf("linux", "android", "root"))
            integer("timeout_s", "Seconds before it and everything it started is killed, 1-600. Default 120.")
        },
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.string("command")
        val env = LinuxTools.parseEnv(args.optString("env")) ?: return ToolResult.error("Unknown env; use linux, android or root.")
        val timeout = (args.optInt("timeout_s") ?: 120).coerceIn(1, 600) * 1_000L
        val linux = LinuxEnv.get(app)
        val r = when (env) {
            ShellEnv.Linux -> {
                LinuxTools.linuxProblem(linux)?.let { return ToolResult.error(it) }
                linux.run(command, timeout)
            }
            ShellEnv.Android -> ProcessRunner.run(LaunchSpec(listOf("/system/bin/sh", "-c", command), linux.hostEnv(), app.filesDir.path), timeout, LinuxEnv.OUTPUT_CAP)
            ShellEnv.Root -> {
                if (!linux.probeRoot().root) return ToolResult.error("Root access is not available on this phone (su did not grant root).")
                ProcessRunner.run(LaunchSpec(AndroidShell.rootArgv(app, command), linux.hostEnv(), "/"), timeout, LinuxEnv.OUTPUT_CAP)
            }
        }
        val body = buildString {
            append("$ ").append(command).append("   [").append(env.label).append("]\n")
            append("exit ").append(r.exitCode)
            if (r.timedOut) append(" (timed out after ${timeout / 1000}s; killed)")
            if (r.stdout.isNotBlank()) append("\nstdout:\n").append(LinuxTools.cap(r.stdout.trimEnd(), if (r.stderr.isBlank()) 3_900 else 2_900))
            if (r.stderr.isNotBlank()) append("\nstderr:\n").append(LinuxTools.cap(r.stderr.trimEnd(), 1_000))
            if (r.stdout.isBlank() && r.stderr.isBlank()) append(" (no output)")
        }
        return ToolResult(body, isError = r.exitCode != 0)
    }
}

private class SessionTool(private val app: Context) : Tool {
    override val risk = Risk.Sensitive
    // open waits up to 15 s for the shell, send up to wait_s (120 s max).
    override val timeoutMs: Long = 140_000
    override val spec = ToolSpec(
        name = "terminal_session",
        description = "A persistent shell that keeps its state (cwd, variables, running programs) between calls, also " +
            "shown to the user in the terminal. action=open returns an id. action=send runs a command, or, while one is " +
            "still running (a REPL, a prompt), sends it as input; it waits up to wait_s for the result. action=close ends it.",
        parameters = schema {
            string("action", "open, send or close.", required = true, enum = listOf("open", "send", "close"))
            string("id", "Session id (for send and close).")
            string("input", "The command or input line (for send).")
            string("env", LinuxTools.ENV_HELP + " For open.", enum = listOf("linux", "android", "root"))
            integer("wait_s", "Seconds to wait for the command to finish, 0-120. Default 20.")
            boolean("interrupt", "For send: press Ctrl-C first.")
        },
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        when (args.string("action")) {
            "open" -> {
                val env = LinuxTools.parseEnv(args.optString("env")) ?: return ToolResult.error("Unknown env.")
                val linux = LinuxEnv.get(app)
                if (env == ShellEnv.Linux) LinuxTools.linuxProblem(linux)?.let { return ToolResult.error(it) }
                if (env == ShellEnv.Root && !linux.probeRoot().root) return ToolResult.error("Root access is not available.")
                val entry = try {
                    TerminalSessions.open(app, SessionMode.Simple, env, 120, 40, SessionOwner.Agent)
                } catch (e: IllegalStateException) {
                    return ToolResult.error(e.message ?: "Could not open a session.")
                }
                entry.simple?.awaitReady(15_000)
                return ToolResult.ok("Opened session ${entry.id} (${env.label}). Send commands with action=send, id=${entry.id}.")
            }
            "send" -> {
                val id = args.optString("id") ?: return ToolResult.error("Missing id.")
                val input = args.optString("input") ?: return ToolResult.error("Missing input.")
                val entry = TerminalSessions.find(id) ?: return ToolResult.error("No session $id (it may have been closed).")
                val simple = entry.simple ?: return ToolResult.error("Session $id is a console session; the agent can only use its own sessions.")
                if (entry.terminal?.exitCode?.value != null) return ToolResult.error("Session $id has ended; open a new one.")
                val wait = (args.optInt("wait_s") ?: 20).coerceIn(0, 120) * 1_000L
                if (args.optBoolean("interrupt") == true) simple.interrupt()
                val blockId = simple.send(input)
                return if (blockId != null) {
                    val b = simple.awaitBlock(blockId, wait)
                    ToolResult(LinuxTools.blockText(input, b), isError = b?.exitCode?.let { it != 0 } == true)
                } else {
                    simple.awaitQuiet(800, wait)
                    val b = simple.blocks.value.lastOrNull()
                    ToolResult.ok("(input sent to the running command)\n" + LinuxTools.blockText(b?.command ?: input, b))
                }
            }
            "close" -> {
                val id = args.optString("id") ?: return ToolResult.error("Missing id.")
                TerminalSessions.find(id) ?: return ToolResult.error("No session $id.")
                TerminalSessions.close(id)
                return ToolResult.ok("Closed session $id; everything it started was stopped.")
            }
            else -> return ToolResult.error("action must be open, send or close.")
        }
    }
}

private class SessionReadTool : Tool {
    override val risk = Risk.Read
    override val timeoutMs: Long = 130_000
    override val spec = ToolSpec(
        name = "terminal_session_read",
        description = "Read the latest command and output of a terminal_session (e.g. after a long command), or list sessions when id is omitted.",
        parameters = schema {
            string("id", "Session id; omit to list the open sessions.")
            integer("wait_s", "Seconds to wait for a running command to finish first, 0-120. Default 0.")
        },
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val id = args.optString("id")
        if (id == null) {
            val list = TerminalSessions.sessions.value
            if (list.isEmpty()) return ToolResult.ok("No terminal sessions are open.")
            return ToolResult.ok(list.joinToString("\n") { s ->
                val state = if (s.terminal?.exitCode?.value != null) "ended" else "running"
                "${s.id}: ${s.title} (${s.mode.name.lowercase()}, $state)"
            })
        }
        val entry = TerminalSessions.find(id) ?: return ToolResult.error("No session $id.")
        val simple = entry.simple
        if (simple == null) {
            val text = entry.terminal?.screen?.value?.text().orEmpty()
            return ToolResult.ok("Screen of ${entry.title}:\n" + LinuxTools.cap(text, LinuxTools.CAP))
        }
        val wait = (args.optInt("wait_s") ?: 0).coerceIn(0, 120) * 1_000L
        val last = simple.blocks.value.lastOrNull() ?: return ToolResult.ok("No commands run in $id yet.")
        val b = if (last.running && wait > 0) simple.awaitBlock(last.id, wait) else last
        return ToolResult.ok(LinuxTools.blockText(b?.command ?: "", b))
    }
}

private class JobStartTool(private val app: Context) : Tool {
    override val risk = Risk.Sensitive
    override val spec = ToolSpec(
        name = "terminal_job_start",
        description = "Start a command in the background; it keeps running after your reply ends and the user is notified " +
            "when it completes, fails or times out. kind=job (default) has a time limit (10 minutes unless the user changed " +
            "it). kind=daemon (a server: sshd, http server...) has no limit, needs a unique name and runs until stopped " +
            "or the phone restarts. Check on it with terminal_job_info.",
        parameters = schema {
            string("command", "The sh command line.", required = true)
            string("kind", "job or daemon.", enum = listOf("job", "daemon"))
            string("name", "Daemon name (required for daemons), or a short title for a job.")
            string("env", LinuxTools.ENV_HELP, enum = listOf("linux", "android", "root"))
            integer("timeout_minutes", "Job time limit in minutes; cannot exceed the user's cap.")
        },
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.string("command")
        val kind = if (args.optString("kind") == "daemon") JobKind.Daemon else JobKind.Job
        val env = LinuxTools.parseEnv(args.optString("env")) ?: return ToolResult.error("Unknown env.")
        if (env == ShellEnv.Linux) LinuxTools.linuxProblem(LinuxEnv.get(app))?.let { return ToolResult.error(it) }
        if (kind == JobKind.Daemon && args.optString("name").isNullOrBlank()) return ToolResult.error("A daemon needs a name.")
        val r = try {
            JobManager.get(app).start(kind, env, command, args.optString("name"), args.optInt("timeout_minutes"))
        } catch (e: IllegalStateException) {
            return ToolResult.error(e.message ?: "Could not start.")
        }
        val limit = if (r.timeoutMs > 0) "time limit ${r.timeoutMs / 60_000} min" else "no time limit"
        return ToolResult.ok("$ $command   [${env.label}]\nStarted ${if (kind == JobKind.Daemon) "daemon ${r.name}" else "job"} ${r.id} ($limit). Logs: terminal_job_info id=${r.id}.")
    }
}

private class JobInfoTool(private val app: Context) : Tool {
    override val risk = Risk.Read
    override val spec = ToolSpec(
        name = "terminal_job_info",
        description = "Status and log tail of background jobs and daemons. Without id or name, lists them all.",
        parameters = schema {
            string("id", "Job id.")
            string("name", "Daemon name.")
            integer("log_chars", "How much of the log end to return, 0-4000. Default 2000.")
        },
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobs = JobManager.get(app)
        val r = args.optString("id")?.let(jobs::find) ?: args.optString("name")?.let(jobs::findDaemon)
        if (r == null) {
            if (args.optString("id") != null || args.optString("name") != null) return ToolResult.error("No such job or daemon.")
            val list = jobs.jobs.value
            if (list.isEmpty()) return ToolResult.ok("No background jobs or daemons.")
            return ToolResult.ok(list.sortedByDescending { it.startedAtMs }.take(30).joinToString("\n") { line(it) })
        }
        val chars = (args.optInt("log_chars") ?: 2_000).coerceIn(0, LinuxTools.CAP)
        val log = jobs.log(r.id).let { if (it.length > chars) "…" + it.takeLast(chars) else it }
        return ToolResult.ok("${line(r)}\n$ ${r.command}\n--- log ---\n$log")
    }

    private fun line(r: JobRecord): String {
        val age = ((r.endedAtMs ?: System.currentTimeMillis()) - r.startedAtMs) / 1000
        return "${r.id} ${r.kind.name.lowercase()} \"${r.name}\" [${r.env.label}] ${r.state.name.lowercase()}" +
            (r.exitCode?.let { " exit $it" } ?: "") + " (${age}s)"
    }
}

private class JobStopTool(private val app: Context) : Tool {
    override val risk = Risk.Act
    override val spec = ToolSpec(
        name = "terminal_job_stop",
        description = "Stop a background job or daemon (and everything it started).",
        parameters = schema {
            string("id", "Job id.")
            string("name", "Daemon name.")
        },
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobs = JobManager.get(app)
        val r = args.optString("id")?.let(jobs::find) ?: args.optString("name")?.let(jobs::findDaemon)
            ?: return ToolResult.error("No such job or daemon.")
        if (r.state != JobState.Running) return ToolResult.ok("${JobRules.describe(r)}; nothing to stop.")
        jobs.stop(r.id)
        return ToolResult.ok("Stopping ${r.name} (${r.id}).")
    }
}

private class RootCheckTool(private val app: Context) : Tool {
    override val risk = Risk.Read
    override val spec = ToolSpec(
        name = "terminal_root_check",
        description = "Check whether root (su) works, whether Linux can use Root mode (chroot), and how Linux is set up.",
        parameters = schema(),
    )

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val linux = LinuxEnv.get(app)
        val p = linux.probeRoot(force = true)
        val linuxLine = when (val s = linux.state.value) {
            is LinuxState.Ready -> "Linux: ${s.distro.displayName} ${s.release}, ${if (s.mode == RunMode.Chroot) "Root mode (chroot)" else "Standard mode (PRoot)"}" +
                (if (s.rootLost) ", root lost" else "") + ", base tools ${if (s.toolsInstalled) "installed" else "not installed"}" +
                (if (s.profiles.isNotEmpty()) ", profiles: ${s.profiles.joinToString { it.title }}" else "")
            is LinuxState.Installing -> "Linux: installing (${s.step})"
            else -> "Linux: not installed"
        }
        return ToolResult.ok(
            "Root: ${if (p.root) "available" else "not available"}\n" +
                "Private mount namespace (needed for chroot): ${if (p.privateMounts) "yes" else "no"}\n$linuxLine",
        )
    }
}
