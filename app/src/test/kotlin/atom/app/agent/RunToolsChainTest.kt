package atom.app.agent

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ToolCall
import atom.core.llm.ToolSpec
import atom.core.tool.ApprovalRequest
import atom.core.tool.PerCallRisk
import atom.core.tool.Risk
import atom.core.tool.ShellRisk
import atom.core.tool.ShellScope
import atom.core.tool.TaskGrant
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import atom.core.tool.optString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tools a run really gets, wrapped the way TaskRunner wraps them ([RunTools.wrap]: switches, "ask before taps",
 * long-tool limits, the per-call switch check), keep the shell's own approval prompt. In 4.0.5 the last wrapper
 * dropped it, so every command showed raw "terminal_run({...})" and "allow all commands for this task" never came up.
 */
class RunToolsChainTest {
    private val scope = ShellScope.distro("io.github.namanwtf.atom", privileged = false)

    /** Shaped like LinuxTools' terminal_run: every command asks, its prompt comes from [ShellRisk.approval]. */
    private class TerminalRun(private val scope: ShellScope) : Tool, PerCallRisk {
        val ran = ArrayList<String>()
        override val spec = ToolSpec("terminal_run", "Run one shell command and wait for it.", JsonObject(emptyMap()))
        override val risk = Risk.Sensitive
        override val timeoutMs: Long = 615_000
        override fun riskFor(args: JsonObject): Risk = ShellRisk.approvalRisk(args.optString("command").orEmpty(), scope)
        override fun approvalRequest(args: JsonObject): ApprovalRequest =
            ShellRisk.approval(args.optString("command").orEmpty(), scope, "the Linux terminal")
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            ran += args.optString("command").orEmpty()
            return ToolResult.ok("exit 0")
        }
    }

    private fun cmd(c: String) = JsonObject(mapOf("command" to JsonPrimitive(c)))

    @Test
    fun theWrappedTerminalKeepsItsPromptAndTheAllowAllButton() {
        for (confirmAct in listOf(false, true)) {
            val tool = RunTools.wrap(listOf(TerminalRun(scope)), confirmAct).single()
            val request = assertNotNull(tool.approvalRequest(cmd("python3 /workspace/hello.py")), "confirmAct=$confirmAct")
            assertTrue(request.action.startsWith("Run in the Linux terminal:\npython3 /workspace/hello.py"), request.action)
            assertTrue(request.taskGrantable, "confirmAct=$confirmAct: offers allow all commands for this task")
            assertEquals(Risk.Sensitive, tool.riskFor(cmd("ls")), "every command still asks")
            assertEquals(615_000L, tool.timeoutMs)
        }
    }

    /** Asks for two commands in one run, answering the way the approval sheet does. */
    private class Engine : LlmEngine {
        private var n = 0
        override val id = "test"
        override val capabilities = EngineCapabilities(nativeToolCalls = true, vision = false, contextWindow = 0, onDevice = false)
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            when (n++) {
                0 -> emit(LlmEvent.ToolCallReady(ToolCall("c1", "terminal_run", """{"command":"apk add python3"}""")))
                1 -> emit(LlmEvent.ToolCallReady(ToolCall("c2", "terminal_run", """{"command":"python3 /workspace/hello.py"}""")))
                else -> emit(LlmEvent.TextDelta("It printed 5."))
            }
            emit(LlmEvent.Finished(if (n <= 2) FinishReason.ToolCalls else FinishReason.Stop))
        }
    }

    /** Like TaskRunner.requestApproval: the grant answers what it covers; the person taps "allow all" on the first. */
    private class Person(val grant: TaskGrant) : ToolContext {
        val prompts = ArrayList<ApprovalRequest>()
        override val taskId = "run-1"
        override suspend fun confirm(action: String) = approve(ApprovalRequest(action))
        override suspend fun approve(request: ApprovalRequest): Boolean {
            if (grant.covers(request)) return true
            prompts += request
            if (grant.grantable(request)) grant.grant()
            return true
        }
    }

    @Test
    fun allowAllCoversTheNextCommandOfTheSameRun() = runBlocking {
        val terminal = TerminalRun(scope)
        val tools = RunTools.wrap(listOf(terminal), confirmAct = false)
        val person = Person(TaskGrant(offered = true))
        val events = Agent(Engine(), ToolRegistry(tools), AgentConfig("m", "sys", contextWindow = 128_000))
            .run(listOf(atom.core.llm.ChatMessage.user("run hello.py")), person).toList()
        assertTrue(events.last() is AgentEvent.Done, events.last().toString())
        assertEquals(listOf("apk add python3", "python3 /workspace/hello.py"), terminal.ran, "both commands ran")
        assertEquals(1, person.prompts.size, "one prompt, then the grant covers the second command: ${person.prompts}")
        assertTrue(person.prompts.single().taskGrantable && person.prompts.single().action.startsWith("Run in"))
    }
}
