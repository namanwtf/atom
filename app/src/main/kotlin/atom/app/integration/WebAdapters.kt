package atom.app.integration

import android.content.Context
import android.net.Uri
import android.util.Base64
import atom.app.AppGraph
import atom.app.ChatRequest
import atom.app.data.Attachments
import atom.app.data.KeyVault
import atom.app.ui.MainActivity
import atom.device.ShellTier
import atom.device.root.RootAccess
import atom.linux.LinuxEnv
import atom.linux.LinuxState
import atom.web.ExecResult
import atom.web.PrivilegedFiles
import atom.web.PrivilegedScope
import atom.web.ProviderKeys
import atom.web.SecretStore
import atom.web.WebHost
import atom.ui.feature.RunSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class AppWebHost(private val context: Context, private val graph: AppGraph, vault: KeyVault) : WebHost {
    override val providerKeys: ProviderKeys = VaultProviderKeys(context, vault)
    override val secrets: SecretStore = VaultWebSecrets(vault)
    override val privilegedFiles: PrivilegedFiles = AppPrivilegedFiles(context, graph)

    override fun conversationOf(taskId: String): String? = graph.tasks.conversationOf(taskId)

    /**
     * Only while the app is in front, and only for a run the user started and sees: a scheduled task, an automation
     * or any other background run never pulls a screen up; the chat card opens it later.
     */
    override fun openScreen(route: String, taskId: String): Boolean {
        if (!graph.uiVisible) return false
        val source = graph.tasks.sourceOf(taskId) ?: return false
        if (source !in FOREGROUND_SOURCES) return false
        NavRequests.open(route)
        return true
    }

    private companion object {
        val FOREGROUND_SOURCES = setOf(RunSource.Chat, RunSource.Bubble, RunSource.Assistant, RunSource.Voice)
    }

    /**
     * Imports the image (grants on content URIs are short-lived) and hands it to the chat as a new message draft,
     * then brings the app forward.
     */
    override fun attachToComposer(context: Context, uri: Uri) {
        val app = context.applicationContext
        graph.appScope.launch {
            when (val outcome = Attachments.importImage(app, uri)) {
                is Attachments.Outcome.Added -> graph.postChatRequest(ChatRequest.Share("", listOf(outcome.attachment)))
                is Attachments.Outcome.Rejected -> graph.postChatRequest(ChatRequest.Share("", emptyList(), rejected = 1))
            }
            app.startActivity(MainActivity.conversationIntent(app, null))
        }
    }
}

/**
 * Root through `su` (only once root was granted to Atom; the device shell detection decides), and the Linux
 * environment through [LinuxEnv]. Root keeps bytes exact both ways. Linux output is text-only, so binary data
 * travels base64-encoded and is limited to what fits the Linux runner's output cap.
 */
class AppPrivilegedFiles(private val context: Context, private val graph: AppGraph) : PrivilegedFiles {
    override suspend fun available(scope: PrivilegedScope): Boolean = when (scope) {
        PrivilegedScope.Root -> RootAccess.granted || graph.shellTier.value == ShellTier.Root
        PrivilegedScope.Linux -> (LinuxEnv.get(context).state.value as? LinuxState.Ready)?.rootLost == false
    }

    override suspend fun exec(scope: PrivilegedScope, command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult = when (scope) {
        PrivilegedScope.Root -> root(command, stdin, timeoutMs)
        PrivilegedScope.Linux -> linux(command, stdin, timeoutMs)
    }

    private suspend fun root(command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult = withContext(Dispatchers.IO) {
        if (!available(PrivilegedScope.Root)) return@withContext ExecResult(126, ByteArray(0), "Root is not available.")
        val process = runCatching { ProcessBuilder("su", "-c", command).start() }.getOrElse {
            return@withContext ExecResult(126, ByteArray(0), "Could not start su: ${it.message}")
        }
        try {
            coroutineScope {
                val out = async(Dispatchers.IO) { readCapped(process.inputStream) }
                val err = async(Dispatchers.IO) { readCapped(process.errorStream).toString(Charsets.UTF_8) }
                process.outputStream.use { stream -> if (stdin != null) stream.write(stdin) }
                val finished = runInterruptible { process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
                if (!finished) {
                    process.destroyForcibly()
                    return@coroutineScope ExecResult(124, out.await(), err.await() + "\nTimed out after ${timeoutMs / 1000}s.")
                }
                ExecResult(process.exitValue(), out.await(), err.await())
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private suspend fun linux(command: String, stdin: ByteArray?, timeoutMs: Long): ExecResult {
        val env = LinuxEnv.get(context)
        if (stdin != null && stdin.size > MAX_LINUX_STDIN) {
            return ExecResult(1, ByteArray(0), "Too large to write through the Linux environment (${stdin.size / 1024} KB; limit ${MAX_LINUX_STDIN / 1024} KB).")
        }
        val input = stdin?.let { "printf '%s' '${Base64.encodeToString(it, Base64.NO_WRAP)}' | base64 -d | " }.orEmpty()
        val script = "out=\$(mktemp) || exit 125; $input( $command ) >\"\$out\"; rc=\$?; base64 \"\$out\" | tr -d '\\n'; rm -f \"\$out\"; exit \$rc"
        val r = env.run(script, timeoutMs)
        if (r.stdout.length >= LinuxEnv.OUTPUT_CAP) {
            return ExecResult(1, ByteArray(0), "The output is too large to read through the Linux environment.")
        }
        val bytes = runCatching { Base64.decode(r.stdout.trim(), Base64.DEFAULT) }.getOrElse { r.stdout.toByteArray() }
        return ExecResult(if (r.timedOut) 124 else r.exitCode, bytes, r.stderr)
    }

    private fun readCapped(stream: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        stream.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() < MAX_ROOT_OUTPUT) out.write(buf, 0, minOf(n, MAX_ROOT_OUTPUT - out.size()))
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_LINUX_STDIN = 96 * 1024
        const val MAX_ROOT_OUTPUT = 64 * 1024 * 1024
    }
}
