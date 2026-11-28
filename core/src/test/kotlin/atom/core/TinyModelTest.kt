package atom.core

import atom.core.agent.Agent
import atom.core.agent.AgentConfig
import atom.core.agent.AgentEvent
import atom.core.agent.ContextPacker
import atom.core.agent.ContextPolicy
import atom.core.agent.StopReason
import atom.core.agent.TinyModel
import atom.core.llm.ChatMessage
import atom.core.llm.EngineCapabilities
import atom.core.llm.FinishReason
import atom.core.llm.LlmEngine
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.PromptedToolEngine
import atom.core.llm.Role
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolRegistry
import atom.core.tool.ToolResult
import atom.core.tool.int
import atom.core.tool.schema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run plan for a tiny on-device model (Qwen3 0.6B): a short prompt and at most one group of one-step tools. */
class TinyModelTest {
    /** An on-device engine without native tool calls and Qwen3 0.6B's 4096-token window; it writes [turns] as text. */
    private class TinyEngine(private val turns: List<String>) : LlmEngine {
        val requests = mutableListOf<LlmRequest>()
        override val id = "litert:Qwen3-0.6B.litertlm"
        override val capabilities = EngineCapabilities(nativeToolCalls = false, vision = false, contextWindow = 4_096, onDevice = true)
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            requests += request
            emit(LlmEvent.TextDelta(turns[minOf(requests.size - 1, turns.lastIndex)]))
            emit(LlmEvent.Finished(FinishReason.Stop))
        }
    }

    private class FakeTool(name: String, override val risk: Risk = Risk.Read, build: atom.core.tool.SchemaBuilder.() -> Unit = {}) : Tool {
        val calls = mutableListOf<JsonObject>()
        override val spec = ToolSpec(name, "Does $name.", schema(build))
        override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
            calls += args
            return ToolResult.ok("Alarm %02d:%02d set (verified: it is now the next alarm).".format(args.int("hour"), args.int("minute")))
        }
    }

    private val setAlarm = FakeTool("set_alarm") {
        integer("hour", "Hour, 0-23.", required = true)
        integer("minute", "Minute, 0-59.", required = true)
        string("label", "Alarm label.")
    }

    /** What the app gathers: core tools in no group, the clock group, and others. */
    private val all: List<Tool> = listOf(FakeTool("read_screen"), FakeTool("tap"), FakeTool("web_search"), setAlarm, FakeTool("set_timer"), FakeTool("browser_open"))
    private val groups = mapOf("set_alarm" to setOf("clock"), "set_timer" to setOf("clock"), "browser_open" to setOf("browser"))
    private fun groupsOf(t: Tool) = groups[t.spec.name].orEmpty()

    private fun config(system: String) = AgentConfig(
        model = "qwen3-0.6b-litertlm",
        systemPrompt = system,
        maxSteps = TinyModel.MAX_STEPS,
        maxOutputTokens = 512,
        context = ContextPolicy(budgetTokens = 3_200, sharedWindow = 4_096),
    )

    @Test
    fun tinyMeansUnderOneAndAHalfBillion() {
        assertTrue(TinyModel.isTiny("Qwen3 0.6B", 613_722_264L))
        assertTrue(TinyModel.isTiny("Llama 3.2 1B Instruct", 800_000_000L))
        assertFalse(TinyModel.isTiny("Qwen2.5 1.5B Instruct", 1_117_320_736L))
        assertFalse(TinyModel.isTiny("Gemma 4 E2B", 2_588_147_712L))
        assertFalse(TinyModel.isTiny("Qwen3 4B Instruct 2507", 2_659_057_664L))
        // No size in the name: judged by the file.
        assertTrue(TinyModel.isTiny("my-model.gguf", 600_000_000L))
        assertFalse(TinyModel.isTiny("Phi-4 mini Instruct", 2_491_874_688L))
        assertFalse(TinyModel.isTiny("my-model.gguf", 0L))
    }

    @Test
    fun onlyOneStepGroupsGetTools() {
        assertEquals("clock", TinyModel.groupFor(listOf("clock")))
        // "Wake me at 8 tomorrow" routes to automation first, but the alarm is the one-step job.
        assertEquals("clock", TinyModel.groupFor(listOf("automation", "clock")))
        assertEquals("settings", TinyModel.groupFor(listOf("settings")))
        assertNull(TinyModel.groupFor(listOf("browser", "terminal")))
        assertNull(TinyModel.groupFor(emptyList()))
        assertEquals(listOf("set_alarm", "set_timer"), TinyModel.toolsFor(all, "clock", ::groupsOf).map { it.spec.name })
        assertTrue(TinyModel.toolsFor(all, null, ::groupsOf).isEmpty())
    }

    @Test
    fun setsTheAlarmWithAShortPromptAndOnlyTheClockTools() = runTest {
        val tools = TinyModel.toolsFor(all, TinyModel.groupFor(listOf("clock")), ::groupsOf)
        val system = TinyModel.systemPrompt(withTools = true, today = "Friday 27 November 2026")
        val inner = TinyEngine(
            listOf(
                "<tool_call>{\"name\": \"set_alarm\", \"arguments\": {\"hour\": 8, \"minute\": 0}}</tool_call>",
                "Your alarm is set for 8:00.",
            ),
        )
        val registry = ToolRegistry(tools)
        val events = Agent(PromptedToolEngine(inner), registry, config(system))
            .run(listOf(ChatMessage.user("hey can you set an alarm for 8:00\n\n[Current time: 18:42]")), FixedContext())
            .toList()

        val done = assertIs<AgentEvent.Done>(events.last())
        assertEquals(StopReason.Completed, done.reason)
        assertEquals("Your alarm is set for 8:00.", done.finalText)
        assertEquals(8, setAlarm.calls.single().int("hour"))
        assertEquals(0, setAlarm.calls.single().int("minute"))

        // The prompt the model sees: the tiny instructions, then the clock tools in the compact form, nothing else.
        val first = inner.requests.first()
        assertTrue(first.tools.isEmpty(), "tools go into the prompt as text")
        val prompt = first.messages.first()
        assertEquals(Role.System, prompt.role)
        assertTrue(prompt.text.startsWith("You are Atom, an assistant on the user's Android phone."), prompt.text)
        assertTrue("Today is Friday 27 November 2026." in prompt.text, prompt.text)
        assertTrue("## set_alarm" in prompt.text && "## set_timer" in prompt.text, prompt.text)
        assertTrue("Arguments: hour (integer, required), minute (integer, required), label (string)" in prompt.text, prompt.text)
        for (absent in listOf("enable_tools", "read_screen", "browser_open", "web_search", "Long-term memory", "More tools")) {
            assertFalse(absent in prompt.text, "$absent must not be in the tiny prompt")
        }
        // A small fraction of the 4096-token window, where 4.0.6 used about half of it before the request.
        assertTrue(ContextPacker.estimate(prompt.text) < 400, "${ContextPacker.estimate(prompt.text)} tokens")
        assertEquals(listOf(Role.System, Role.User), first.messages.map { it.role })

        // Step 2 has the tool's result as a user turn and answers in plain text.
        val second = inner.requests[1].messages.last()
        assertTrue(second.text.startsWith("<tool_result name=\"set_alarm\">"), second.text)
    }

    @Test
    fun plainChatGetsNoToolsAndIsToldItCannotOperateThePhone() = runTest {
        val system = TinyModel.systemPrompt(withTools = false, today = "Friday 27 November 2026")
        val inner = TinyEngine(listOf("Hi! I'm doing well. How can I help?"))
        val events = Agent(PromptedToolEngine(inner), ToolRegistry(emptyList()), config(system))
            .run(listOf(ChatMessage.user("hey, how are you?")), FixedContext())
            .toList()

        assertEquals("Hi! I'm doing well. How can I help?", assertIs<AgentEvent.Done>(events.last()).finalText)
        val prompt = inner.requests.single().messages.first().text
        assertFalse("# Tools" in prompt || "<tool_call>" in prompt, prompt)
        assertTrue("cannot open apps, change settings, browse the web or operate the phone" in prompt, prompt)
        assertTrue(inner.requests.single().tools.isEmpty())
    }
}
