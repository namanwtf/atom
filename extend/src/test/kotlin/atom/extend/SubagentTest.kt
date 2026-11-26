package atom.extend

import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.Role
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.schema
import atom.extend.subagent.BuiltInSubagents
import atom.extend.subagent.SubagentDef
import atom.extend.subagent.SubagentEnvironment
import atom.extend.subagent.SubagentRunner
import atom.extend.subagent.SubagentRuns
import atom.extend.subagent.SubagentSettings
import atom.extend.subagent.SubagentStatus
import atom.extend.subagent.SubagentStore
import atom.extend.subagent.SubagentTask
import atom.extend.subagent.SubagentTools
import atom.extend.subagent.ToolScoping
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files as NioFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeTool(name: String, override val risk: Risk, override val usesPhoneUi: Boolean = false, private val sensitive: Boolean = false) : Tool {
    val calls = AtomicInteger()
    override val spec = ToolSpec(name, "fake $name", schema())
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        calls.incrementAndGet()
        return ToolResult("result of ${spec.name}", sensitive = sensitive)
    }
}

/** First call asks for [toolName] when it is offered; the next call answers with a summary naming the task. */
private class ScriptedEngine(
    private val toolName: String?,
    override val capabilities: EngineCapabilities = CAPS,
    private val slowMs: Long = 0,
    /** Keep calling [toolName] for as long as it is offered (never finishes on its own). */
    private val alwaysCall: Boolean = false,
) : LlmEngine {
    override val id = "fake"
    val requests = mutableListOf<LlmRequest>()

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        synchronized(requests) { requests += request }
        if (slowMs > 0) delay(slowMs)
        val task = request.messages.first { it.role == Role.User }.text
        val usedTool = request.messages.any { it.role == Role.Tool }
        if ((alwaysCall || !usedTool) && toolName != null && request.tools.any { it.name == toolName }) {
            emit(LlmEvent.ToolCallReady(ToolCall("c-${task.hashCode()}-${requests.size}", toolName, """{"n":${requests.size}}""")))
            emit(LlmEvent.Usage(100, 10))
            emit(LlmEvent.Finished(FinishReason.ToolCalls))
        } else {
            emit(LlmEvent.TextDelta("Summary for: $task"))
            emit(LlmEvent.Usage(150, 20))
            emit(LlmEvent.Finished(FinishReason.Stop))
        }
    }

    companion object {
        val CAPS = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 32_000, onDevice = false)
    }
}

private class Provider(val engine: ScriptedEngine) : SubagentEngineProvider {
    val acquired = mutableListOf<Pair<String, String?>>()
    var released = 0
    override suspend fun acquire(role: String, modelId: String?, parentTaskId: String): SubagentEngine {
        acquired += role to modelId
        return SubagentEngine(engine, "fake-model", release = { released++ })
    }
}

private class Ctx : ToolContext {
    override val taskId = "parent-1"
    val asked = mutableListOf<String>()
    override suspend fun confirm(action: String): Boolean {
        asked += action
        return true
    }
}

class SubagentTest {
    private val read = FakeTool("read_file", Risk.Read)
    private val write = FakeTool("write_file", Risk.Act)
    private val shell = FakeTool("shell", Risk.Sensitive)
    private val screen = FakeTool("read_screen", Risk.Read, usesPhoneUi = true)
    private val tap = FakeTool("tap", Risk.Act, usesPhoneUi = true)
    private val mcp = FakeTool("mcp_srv_search", Risk.Read, sensitive = true)
    private val web = FakeTool("web_search", Risk.Read)
    private val spawn = FakeTool("spawn_subagent", Risk.Read)
    private val memClear = FakeTool("memory_clear", Risk.Sensitive)
    private val volume = FakeTool("set_volume", Risk.Act)
    private val parent = listOf(read, write, shell, screen, tap, mcp, web, spawn, memClear, volume)

    private fun names(tools: List<Tool>) = tools.map { it.spec.name }.toSet()

    @Test
    fun defaultCustomScopeIsReadFileTerminalMcpWithoutPhone() {
        val got = names(ToolScoping.select(parent, SubagentDef.DEFAULT_TOOLS, readOnly = false))
        assertEquals(setOf("read_file", "write_file", "shell", "mcp_srv_search", "web_search"), got)
    }

    @Test
    fun readOnlyBuiltInsGetOnlyObservingTools() {
        val explore = names(ToolScoping.select(parent, BuiltInSubagents.explore.tools, BuiltInSubagents.explore.readOnly))
        assertEquals(setOf("read_file", "mcp_srv_search", "web_search"), explore)
        val coder = names(ToolScoping.select(parent, BuiltInSubagents.coder.tools, BuiltInSubagents.coder.readOnly))
        assertTrue("write_file" in coder && "shell" in coder)
        assertFalse("tap" in coder || "read_screen" in coder || "set_volume" in coder)
    }

    @Test
    fun phoneToolsOnlyWhenExplicitAndInternalNever() {
        assertTrue(names(ToolScoping.select(parent, listOf("phone"), false)).containsAll(setOf("read_screen", "tap")))
        assertTrue("tap" in names(ToolScoping.select(parent, listOf("tap"), false)))
        assertFalse("read_screen" in names(ToolScoping.select(parent, listOf("read"), false)))
        val all = names(ToolScoping.select(parent, listOf("read", "web", "file", "terminal", "mcp", "memory", "device", "phone", "spawn_subagent"), false))
        assertFalse("spawn_subagent" in all || "memory_clear" in all)
        assertTrue("set_volume" in all)
    }

    @Test
    fun appCategoryOverrideWins() {
        val got = names(ToolScoping.select(parent, listOf("terminal"), false) { if (it.spec.name == "write_file") ToolCategory.Terminal else null })
        assertEquals(setOf("write_file", "shell"), got)
    }

    private fun runner(engine: ScriptedEngine, settings: SubagentSettings = SubagentSettings(), provider: Provider = Provider(engine), budget: Long = 30_000) =
        SubagentRunner(SubagentEnvironment(engines = provider, parentTools = { parent }, settings = { settings }, timeBudgetMs = budget))

    @Test
    fun runsInIsolationAndRecordsDetails(): Unit = runBlocking {
        val engine = ScriptedEngine("mcp_srv_search")
        val provider = Provider(engine)
        val out = runner(engine, SubagentSettings(modelId = "m-1"), provider).run(SubagentTask(BuiltInSubagents.explore, "find the invoice"), Ctx())
        assertEquals(SubagentStatus.Done, out.status)
        assertEquals("Summary for: find the invoice", out.summary)
        assertEquals(1, out.toolCalls)
        assertEquals(250, out.inputTokens)
        assertTrue(out.sensitive)
        assertEquals(listOf<Pair<String, String?>>("explore" to "m-1"), provider.acquired.toList())
        assertEquals(1, provider.released)

        val first = engine.requests.first()
        assertEquals(Role.System, first.messages.first().role)
        assertTrue("explore" in first.messages.first().text)
        assertEquals(listOf("find the invoice"), first.messages.filter { it.role == Role.User }.map { it.text })
        assertEquals(setOf("read_file", "mcp_srv_search", "web_search"), first.tools.map { it.name }.toSet())

        val record = assertNotNull(SubagentRuns.get(out.runId))
        assertEquals("parent-1", record.parentTaskId)
        assertEquals("find the invoice", record.prompt)
        assertEquals(SubagentStatus.Done, record.status)
        assertEquals(listOf("mcp_srv_search"), record.steps.map { it.tool })
        assertEquals("Summary for: find the invoice", record.summary)
        assertEquals(250 to 30, record.inputTokens to record.outputTokens)
    }

    @Test
    fun sensitiveChildActionsAskThroughTheParent(): Unit = runBlocking {
        val engine = ScriptedEngine("shell")
        val ctx = Ctx()
        runner(engine).run(SubagentTask(BuiltInSubagents.coder, "build it"), ctx)
        assertEquals(1, shell.calls.get())
        assertTrue(ctx.asked.single().startsWith("Subagent coder:"))
    }

    @Test
    fun parallelTasksShareOneEngineAndReturnAllSummaries(): Unit = runBlocking {
        val engine = ScriptedEngine(null, slowMs = 50)
        val provider = Provider(engine)
        val tasks = listOf(SubagentTask(BuiltInSubagents.plan, "plan A"), SubagentTask(BuiltInSubagents.explore, "look B"))
        val outs = runner(engine, provider = provider).runAll(tasks, Ctx())
        assertEquals(listOf("Summary for: plan A", "Summary for: look B"), outs.map { it.summary })
        assertEquals(1, provider.acquired.size)
        assertEquals(1, provider.released)
    }

    @Test
    fun stepLimitStillEndsWithTheSubagentsSummary(): Unit = runBlocking {
        val engine = ScriptedEngine("read_file", alwaysCall = true)
        val out = runner(engine).run(SubagentTask(BuiltInSubagents.explore, "map the repo"), Ctx())
        assertEquals(SubagentStatus.Partial, out.status)
        assertTrue(out.summary.startsWith("Summary for: map the repo"), out.summary)
        assertTrue("step limit" in out.summary)
        assertEquals(60, out.toolCalls)
        val last = engine.requests.last()
        assertTrue(last.tools.isEmpty(), "the wrap-up call offers no tools")
        assertEquals(atom.core.agent.Agent.STEP_LIMIT_REASON + " " + SubagentRunner.WRAP_UP, last.messages.last().text)
    }

    @Test
    fun timeBudgetReturnsPartial(): Unit = runBlocking {
        val engine = ScriptedEngine(null, slowMs = 10_000)
        val out = runner(engine, budget = 100).run(SubagentTask(BuiltInSubagents.plan, "slow"), Ctx())
        assertEquals(SubagentStatus.Partial, out.status)
        assertTrue("partial" in out.summary)
    }

    @Test
    fun spawnToolResolvesAgentsAndFormatsSummary(): Unit = runBlocking {
        val dir = NioFiles.createTempDirectory("agents").toFile()
        val store = SubagentStore(dir)
        store.save(SubagentDef("code-reviewer", "Reviews code", "after edits", listOf("read"), "Review carefully."))
        val engine = ScriptedEngine("read_file")
        val tools = SubagentTools.create(store, runner(engine))
        val spawnTool = tools.first { it.spec.name == "spawn_subagent" }
        val result = spawnTool.run(JsonObject(mapOf("agent" to JsonPrimitive("code-reviewer"), "task" to JsonPrimitive("review x"))), Ctx())
        assertTrue(result.text.startsWith("Subagent code-reviewer finished · 1 tool calls"))
        assertTrue("Summary for: review x" in result.text)
        assertTrue("Review carefully." in engine.requests.first().messages.first().text)

        val parallelTool = tools.first { it.spec.name == "spawn_subagents_parallel" }
        val tasks = JsonArray(listOf("a", "b").map { JsonObject(mapOf("agent" to JsonPrimitive("plan"), "task" to JsonPrimitive(it))) })
        val both = parallelTool.run(JsonObject(mapOf("tasks" to tasks)), Ctx())
        assertTrue("Summary for: a" in both.text && "Summary for: b" in both.text)
    }

    @Test
    fun customSubagentsRoundTripAsMarkdown() {
        val dir = NioFiles.createTempDirectory("agents").toFile()
        val store = SubagentStore(dir)
        val def = SubagentDef("trip-planner", "Plans: trips", "When travelling", listOf("read", "web"), "Plan well.\n\nTwo paragraphs.")
        store.save(def)
        assertTrue(java.io.File(dir, "agents/trip-planner.md").isFile)
        assertEquals(def, SubagentStore(dir).find("trip-planner"))
        store.save(def.copy(name = "trip-helper"), previousName = "trip-planner")
        assertEquals(listOf("trip-helper"), SubagentStore(dir).custom.value.map { it.name })
        assertTrue(SubagentDef.validateName("Bad Name") != null)
        assertTrue(SubagentDef.validateName("explore") != null)
        assertEquals(null, SubagentDef.validateName("ok-name-2"))
    }
}
