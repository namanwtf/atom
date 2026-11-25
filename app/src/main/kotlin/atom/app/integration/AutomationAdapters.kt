package atom.app.integration

import atom.device.switches.withoutSwitchedOff
import android.content.Context
import atom.app.AppGraph
import atom.app.data.Conversation
import atom.app.data.EngineSelection
import atom.app.data.ModelRole
import atom.app.data.ProviderStore
import atom.app.data.TitleSource
import atom.app.feature.Features
import atom.automation.Capability
import atom.automation.EngineProvider
import atom.automation.ModelChoice
import atom.automation.ModelChoiceProvider
import atom.automation.RunLauncher
import atom.automation.RunRequest
import atom.automation.ToolInvoker
import atom.automation.EngineSelection as CompileEngine
import atom.core.llm.ToolSpec
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.device.DeviceTools
import atom.linux.LinuxTools
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/** Model ids offered to scheduled tasks: "cloud:<provider>/<model>" or "local:<installed id>". */
internal object ModelIds {
    fun encode(selection: EngineSelection): String = when (selection) {
        is EngineSelection.Cloud -> "cloud:${selection.providerId}/${selection.model}"
        is EngineSelection.Local -> "local:${selection.installedModelId}"
    }

    fun decode(id: String): EngineSelection? = when {
        id.startsWith("cloud:") -> id.removePrefix("cloud:").let { rest ->
            val slash = rest.indexOf('/')
            if (slash <= 0 || slash == rest.lastIndex) null else EngineSelection.Cloud(rest.substring(0, slash), rest.substring(slash + 1))
        }
        id.startsWith("local:") -> id.removePrefix("local:").takeIf { it.isNotBlank() }?.let { EngineSelection.Local(it) }
        else -> null
    }
}

/**
 * Starts unattended runs through the task runner. What the AgentHost API allows is applied for real: the model
 * (the task's pin, else the "Scheduled tasks" role), the reasoning effort and the title are written into the
 * conversation before the run starts, so the runner picks them up. The capability set filters the run's tool set
 * for real ("Chat only" runs get no tools); [RunRequest.showProgress] cannot be honoured yet.
 */
class AppRunLauncher(private val graph: AppGraph) : RunLauncher {
    override fun start(request: RunRequest): String? {
        val id = request.conversationId ?: Conversation.newId()
        if (graph.tasks.isRunning(id)) return null
        runCatching { runBlocking(Dispatchers.IO) { prepare(id, request) } }
        return graph.tasks.start(header(request) + request.prompt, id, request.source, emptyList(), request.capabilities)
    }

    private suspend fun prepare(id: String, request: RunRequest) {
        val existing = if (request.conversationId != null) graph.conversations.load(id) else null
        val pinned = request.modelId?.let(ModelIds::decode)?.takeIf { graph.modelInfo.exists(it) }
        val role = roleFor(request.source) ?: ModelRole.Scheduled
        val roleChoice = graph.modelRoles.get(role).selection?.takeIf { graph.modelInfo.exists(it) }
        val base = existing ?: Conversation.empty(id).copy(title = request.title.trim(), titleSource = if (request.title.isBlank()) TitleSource.Placeholder else TitleSource.User)
        val selection = pinned ?: roleChoice ?: base.selection
        val effort = request.reasoning
            ?: selection?.let { runCatching { graph.modelRoles.resolveReasoning(role, it, base.reasoningEffort) }.getOrNull() }
        val next = base.copy(
            selection = selection,
            reasoningEnabled = if (effort != null) true else base.reasoningEnabled,
            reasoningEffort = effort ?: base.reasoningEffort,
        )
        if (next != existing) graph.conversations.save(next)
    }

    private fun header(request: RunRequest): String {
        val allowed = Capability.describe(request.capabilities)
        // The tool set itself is filtered by the runner; this line only tells the model why some tools are absent.
        return "[${request.title} · unattended run · allowed tools: $allowed.]\n"
    }
}

/** Models a scheduled task can pin: every model of an enabled provider, plus installed on-device models. */
class AppModelChoices(private val context: Context, private val graph: AppGraph) : ModelChoiceProvider {
    override fun choices(): List<ModelChoice> {
        val cloud = ProviderStore.get(context).providers.value.filter { it.enabled }.flatMap { p ->
            p.models.map { m ->
                val s = EngineSelection.Cloud(p.id, m.id)
                ModelChoice(ModelIds.encode(s), "${p.displayName} / ${m.displayName}")
            }
        }
        val local = graph.models.installed.value.map { m -> ModelChoice(ModelIds.encode(EngineSelection.Local(m.id)), m.name) }
        return local + cloud
    }

    override fun label(id: String?): String {
        if (id == null) return super.label(null)
        val selection = ModelIds.decode(id) ?: return id
        return runCatching { graph.modelInfo.label(selection) }.getOrDefault(id)
    }
}

/**
 * The model that compiles a sentence into an automation: the global default. The router lease is returned at
 * once; an idle on-device model stays loaded for the router's idle window, which covers one compile.
 */
class AppCompileEngine(private val graph: AppGraph) : EngineProvider {
    override suspend fun current(): CompileEngine? {
        val routed = runCatching { graph.router.acquire(null as EngineSelection?) }.getOrNull() ?: return null
        graph.router.release(routed)
        return CompileEngine(routed.engine, routed.model, reasoning = null)
    }
}

/**
 * Calls agent tools by name outside a chat run, with the same tool set a run gets. Phone-UI tools take the
 * phone-control lease like a run would; tools that ask for approval are declined (nobody is there to answer).
 */
class AppToolInvoker(private val context: Context, private val graph: AppGraph) : ToolInvoker {
    @Volatile
    private var lastFull: List<Tool>? = null

    override fun available(): List<ToolSpec> = (lastFull ?: quickTools()).map { it.spec }

    override suspend fun invoke(name: String, args: JsonObject): ToolResult {
        val tool = fullTools().firstOrNull { it.spec.name == name } ?: return ToolResult.error("Unknown tool: $name")
        val runId = "automation-" + UUID.randomUUID()
        val ctx = object : ToolContext {
            override val taskId: String = runId
            override suspend fun confirm(action: String): Boolean = false
        }
        if (tool.usesPhoneUi) graph.tasks.lease.acquire(runId)
        return try {
            withTimeout(tool.timeoutMs ?: DEFAULT_TIMEOUT_MS) { tool.run(args, ctx) }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("$name timed out.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("$name failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (tool.usesPhoneUi) graph.tasks.lease.release(runId)
        }
    }

    private fun featureTools(): List<Tool> =
        Features.all.flatMap { f -> runCatching { f.tools(context) }.getOrDefault(emptyList()) }.withoutSwitchedOff()

    /** Without probing the shell (that is not free): enough for listing in the rule editor. */
    private fun quickTools(): List<Tool> = (LinuxTools.create(context) + featureTools()).withoutSwitchedOff().distinctBy { it.spec.name }

    private suspend fun fullTools(): List<Tool> =
        (DeviceTools.create(context, graph.shell()) + LinuxTools.create(context) + featureTools())
            .withoutSwitchedOff()
            .distinctBy { it.spec.name }
            .also { lastFull = it }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
