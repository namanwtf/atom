package atom.app.e2e

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import atom.app.agent.LiveRun
import atom.app.data.EngineSelection
import atom.app.data.EntryKind
import atom.app.graph
import atom.app.service.TaskActionReceiver
import atom.core.llm.Role
import atom.llm.cloud.DetectedCapabilities
import atom.llm.cloud.ModelConfig
import atom.llm.cloud.ProviderConfig
import atom.llm.cloud.ReasoningMode
import atom.llm.cloud.Wire
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug builds only (src/debug). The end-to-end task tests (tools/e2e/run.py, docs/TESTING.md) drive the app through
 * this provider over adb, so no screen has to be typed into:
 *
 * - `content call --uri content://<pkg>.e2e --method configure --arg '{"base_url": "...", "model": "...",
 *   "context_window": n, "extra_body": {...}, "titles": false}'` adds the "Local test model" provider
 *   (OpenAI-compatible, no key), selects it and finishes onboarding.
 * - `content read --uri content://<pkg>.e2e/state` prints a JSON dump of the runs (phase, tool calls, answer, errors),
 *   scheduled tasks, Linux and accessibility state.
 * - methods `clear`, `stop`, `approve`, `deny`, `linux_install` (see [call]).
 *
 * Only the shell (adb) and root uids, and the app itself, may use it.
 */
class E2eProvider : ContentProvider() {
    /** Tool names each run had, captured while it was active (the runner forgets them when it ends). */
    private val toolsByRun = ConcurrentHashMap<String, List<String>>()

    override fun onCreate(): Boolean = true

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID && uid != Process.myUid()) throw SecurityException("e2e hook: adb only")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        checkCaller()
        val ctx = context ?: error("no context")
        val graph = ctx.graph
        val result = Bundle()
        val message: String = when (method) {
            "configure" -> runBlocking(Dispatchers.IO) {
                // The options come as one JSON object in --arg: `content call --extra` cannot carry values with a
                // colon (URLs, JSON).
                val options = JSONObject(arg?.takeIf { it.isNotBlank() } ?: "{}")
                val baseUrl = options.optString("base_url").ifBlank { "http://10.0.2.2:8080/v1" }
                val model = options.optString("model").ifBlank { error("model is required") }
                val window = options.optInt("context_window", 0).takeIf { it > 0 }
                val name = options.optString("name").ifBlank { PROVIDER_NAME }
                // Merged into every request body (e.g. llama-server's {"return_progress":true}).
                val extraBody = options.optJSONObject("extra_body")?.toString().orEmpty()
                val models = listOf(
                    ModelConfig(
                        id = model,
                        displayName = model,
                        contextWindow = window,
                        reasoningMode = ReasoningMode.Off,
                        detected = DetectedCapabilities(contextWindow = window, vision = false),
                    ),
                )
                val existing = graph.providers.get(PROVIDER_ID)
                val id = if (existing == null) {
                    graph.providers.add(
                        ProviderConfig(
                            id = PROVIDER_ID,
                            displayName = name,
                            wire = Wire.OpenAiChat,
                            baseUrl = baseUrl.trim().trimEnd('/'),
                            models = models,
                            defaultModel = model,
                            extraBody = extraBody,
                        ),
                    ).id
                } else {
                    graph.providers.update(PROVIDER_ID) {
                        it.copy(
                            displayName = name,
                            baseUrl = baseUrl.trim().trimEnd('/'),
                            models = models,
                            defaultModel = model,
                            extraBody = extraBody,
                            enabled = true,
                        )
                    }
                    PROVIDER_ID
                }
                graph.vault.setApiKey(id, null)
                graph.settings.setSelection(EngineSelection.Cloud(id, model))
                graph.settings.setOnboarded()
                graph.settings.setReasoningByDefault(false)
                // Titles are a second model call per chat; on a CPU-only test server they only slow the run down.
                if (options.has("titles")) graph.settings.setAutoTitles(options.optBoolean("titles"))
                "configured provider=$id model=$model window=$window base=$baseUrl"
            }
            "clear" -> {
                graph.tasks.liveRuns.value.values.filter { !it.active }.forEach { graph.tasks.clearFinished(it.conversation.id) }
                "cleared"
            }
            "stop" -> {
                // The same broadcast the running-task notification's Stop button sends.
                val intent = Intent(ctx, TaskActionReceiver::class.java)
                    .setAction(if (arg.isNullOrBlank()) TaskActionReceiver.ACTION_STOP_ALL else TaskActionReceiver.ACTION_STOP)
                if (!arg.isNullOrBlank()) intent.putExtra(TaskActionReceiver.EXTRA_CONVERSATION_ID, arg)
                ctx.sendBroadcast(intent)
                "stop sent"
            }
            "approve", "deny" -> {
                val id = arg?.toLongOrNull() ?: graph.tasks.liveRuns.value.values.firstNotNullOfOrNull { it.pending?.id }
                if (id == null) {
                    "nothing pending"
                } else {
                    graph.tasks.answer(id, approved = method == "approve")
                    "$method $id"
                }
            }
            "linux_install" -> {
                val env = atom.linux.LinuxEnv.get(ctx)
                val distro = atom.linux.Distro.entries.firstOrNull { it.name.equals(arg ?: "Alpine", ignoreCase = true) } ?: atom.linux.Distro.Alpine
                env.install(distro)
                "linux install queued: $distro"
            }
            // Runs one of the app's tools directly, the way the agent would, without a model: for checks that must
            // not depend on what a small test model decides (e.g. creating a scheduled task for the battery checks).
            // --arg '{"name": "scheduled_task_create", "args": {...}}'
            "run_tool" -> runBlocking(Dispatchers.Default) {
                val spec = JSONObject(arg ?: "{}")
                val name = spec.optString("name")
                val tool = atom.app.feature.Features.all.flatMap { f -> runCatching { f.tools(ctx) }.getOrDefault(emptyList()) }
                    .firstOrNull { it.spec.name == name }
                if (tool == null) {
                    "no tool $name"
                } else {
                    val args = kotlinx.serialization.json.Json.parseToJsonElement(spec.optJSONObject("args")?.toString() ?: "{}")
                        as kotlinx.serialization.json.JsonObject
                    val toolCtx = object : atom.core.tool.ToolContext {
                        override val taskId: String = "e2e-" + System.currentTimeMillis()
                        override suspend fun confirm(action: String): Boolean = true
                    }
                    val r = tool.run(args, toolCtx)
                    (if (r.isError) "error: " else "ok: ") + r.text.take(300)
                }
            }
            // "Hey Atom" on or off, the way the Speech settings switch sets it (screen-off listening stays off,
            // listening while charging stays on: the defaults).
            "hey_atom" -> {
                val on = arg.equals("on", ignoreCase = true)
                runBlocking(Dispatchers.IO) { graph.settings.setHeyAtom(on) }
                atom.voice.Wake.setEnabled(ctx, on, atom.voice.WakePolicy(screenOffListening = false, listenWhileCharging = true))
                "hey atom ${if (on) "on" else "off"}"
            }
            "state" -> {
                result.putString("json", state().toString())
                "ok"
            }
            else -> "unknown method $method"
        }
        result.putString("result", message)
        return result
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        val ctx = context ?: error("no context")
        val dir = File(ctx.cacheDir, "e2e").apply { mkdirs() }
        val file = File(dir, "state.json")
        file.writeText(state().toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkCaller()
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(state().toString())) }
    }

    private fun state(): JSONObject {
        val ctx = context ?: error("no context")
        val graph = ctx.graph
        val runs = graph.tasks.liveRuns.value.values.sortedBy { it.startedAt }
        runs.filter { it.active }.forEach { run ->
            val names = graph.tasks.toolsOf(run.runId).map { it.spec.name }
            if (names.isNotEmpty()) toolsByRun[run.runId] = names
        }
        val settings = runBlocking(Dispatchers.IO) { graph.settings.current() }
        return JSONObject().apply {
            put("now", System.currentTimeMillis())
            put("selection", settings.selection?.toString() ?: JSONObject.NULL)
            put("onboarded", settings.onboarded)
            put("accessibility", runCatching { atom.device.Accessibility.connected.value }.getOrDefault(false))
            put("linux", runCatching { atom.linux.LinuxEnv.get(ctx).state.value.toString() }.getOrDefault("unknown"))
            put("runs", JSONArray().apply { runs.forEach { put(runJson(it)) } })
            put("scheduledTasks", scheduledTasks())
            put("wake", runCatching { atom.voice.Wake.state.value.toString() }.getOrDefault("unknown"))
            put("screenWait", atom.ui.feature.ScreenWait.waiting.value)
            put("packReady", runCatching { atom.app.ondevice.OnDevicePack.ready.value }.getOrDefault(false))
            put("systemEventReceiver", componentState("atom.automation.system.SystemEventReceiver"))
            put("notificationHistory", notificationHistory())
        }
    }

    private fun runJson(run: LiveRun): JSONObject {
        val graph = context!!.graph
        val messages = run.conversation.messages
        val calls = JSONArray()
        val transcript = JSONArray()
        messages.forEachIndexed { i, m ->
            val msg = m.message
            transcript.put(
                JSONObject().apply {
                    put("role", msg.role.name)
                    put("kind", m.kind.name)
                    put("hidden", m.hidden)
                    put("isError", m.isError)
                    put("text", msg.text.take(TEXT_CAP))
                    if (msg.toolCalls.isNotEmpty()) put("toolCalls", JSONArray(msg.toolCalls.map { it.name }))
                    if (msg.toolName != null) put("toolName", msg.toolName)
                },
            )
            if (msg.role != Role.Assistant) return@forEachIndexed
            msg.toolCalls.forEach { call ->
                val result = messages.drop(i + 1).firstOrNull { it.message.role == Role.Tool && it.message.toolCallId == call.id }
                calls.put(
                    JSONObject().apply {
                        put("name", call.name)
                        put("args", call.argumentsJson.take(ARGS_CAP))
                        put("result", result?.message?.text?.take(RESULT_CAP) ?: JSONObject.NULL)
                        put("isError", result?.isError ?: false)
                        put("durationMs", result?.durationMs ?: JSONObject.NULL)
                    },
                )
            }
        }
        val answer = messages.lastOrNull { it.message.role == Role.Assistant && it.message.toolCalls.isEmpty() && it.kind == EntryKind.Message }
        val saved = runCatching { runBlocking(Dispatchers.IO) { graph.conversations.load(run.conversation.id) } }.getOrNull()
        return JSONObject().apply {
            put("conversationId", run.conversation.id)
            put("runId", run.runId)
            put("title", run.conversation.title)
            put("source", run.source.name)
            put("phase", run.phase.name)
            put("active", run.active)
            put("outcome", run.outcome?.name ?: JSONObject.NULL)
            put("error", run.error ?: JSONObject.NULL)
            put("notice", run.notice ?: JSONObject.NULL)
            put("engine", run.engineLabel ?: JSONObject.NULL)
            put("startedAt", run.startedAt)
            put("currentTool", run.currentTool ?: JSONObject.NULL)
            put("compacting", run.compacting)
            put("inputTokens", run.inputTokens)
            put("outputTokens", run.outputTokens)
            put("streamText", run.stream.text.take(TEXT_CAP))
            put("pending", run.pending?.let { JSONObject().put("id", it.id).put("action", it.action.take(TEXT_CAP)) } ?: JSONObject.NULL)
            put("finalText", run.finalText ?: answer?.message?.text ?: JSONObject.NULL)
            put("toolCalls", calls)
            put("messages", transcript)
            put("availableTools", JSONArray(toolsByRun[run.runId].orEmpty()))
            put("savedMessages", saved?.messages?.size ?: JSONObject.NULL)
            put("savedNotices", JSONArray(saved?.messages.orEmpty().filter { it.kind == EntryKind.Notice }.map { it.message.text.take(200) }))
        }
    }

    /** The automation engine's own saved state, as written (it lives in the automation module, internal there). */
    private fun scheduledTasks(): JSONArray {
        val file = File(context!!.filesDir, "atom4_automation/state.json")
        val raw = runCatching { file.readText() }.getOrNull() ?: return JSONArray()
        val tasks = runCatching { JSONObject(raw).optJSONArray("tasks") }.getOrNull() ?: return JSONArray()
        return JSONArray().apply {
            for (i in 0 until tasks.length()) {
                val t = tasks.optJSONObject(i) ?: continue
                put(
                    JSONObject().apply {
                        put("id", t.opt("id"))
                        put("name", t.opt("name"))
                        put("prompt", t.opt("prompt"))
                        put("enabled", t.opt("enabled"))
                        put("schedule", t.opt("schedule")?.toString()?.take(300))
                    },
                )
            }
        }
    }

    /** "enabled", "disabled" or "default" (the manifest's state) for one of the app's components. */
    private fun componentState(cls: String): String {
        val ctx = context!!
        return when (ctx.packageManager.getComponentEnabledSetting(android.content.ComponentName(ctx, cls))) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enabled"
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disabled"
            else -> "default"
        }
    }

    /** The newest entries of the searchable notification history (what notification_history reads). */
    private fun notificationHistory(): JSONArray = JSONArray().apply {
        val records: List<atom.context.notifications.NotificationRecord> = runCatching {
            atom.context.notifications.Notifications.history(context!!).search(null, null, null, 0, 10).first
        }.getOrDefault(emptyList())
        records.forEach { r ->
            put(JSONObject().put("package", r.packageName).put("title", r.title).put("text", r.text.take(200)).put("postedAt", r.postedAt))
        }
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val PROVIDER_ID = "local-test-model"
        const val PROVIDER_NAME = "Local test model"
        const val TEXT_CAP = 2_000
        const val ARGS_CAP = 400
        const val RESULT_CAP = 800
    }
}
