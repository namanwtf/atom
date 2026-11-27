package atom.app.agent

import atom.core.llm.ToolSpec
import atom.core.tool.ApprovalRequest
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every tool wrapper keeps what the runtime asks the wrapped tool: its approval prompt above all (the shell's reason,
 * "allow all commands for this task", always-ask), its per-call risk, approval text, phone and screen needs and time
 * limit. The device module's own wrappers are checked in GatedToolForwardingTest.
 */
class ToolWrappersTest {
    private val noArgs = JsonObject(emptyMap())

    private class Everything(name: String, override val timeoutMs: Long? = 123_456) : Tool {
        override val spec = ToolSpec(name, "All members set.", JsonObject(emptyMap()))
        override val risk = Risk.Sensitive
        override val usesPhoneUi = true
        override val needsUnlockedScreen = true
        override suspend fun run(args: JsonObject, ctx: ToolContext) = ToolResult.ok("ran")
        override fun riskFor(args: JsonObject) = Risk.Act
        override fun approvalRequest(args: JsonObject) = ApprovalRequest("Do everything", reason = "Because.", taskGrantable = true, alwaysAsk = true)
        override suspend fun approvalText(args: JsonObject) = "Plain text"
    }

    private fun assertForwards(name: String, inner: Tool, w: Tool, timeout: Long? = inner.timeoutMs) = runBlocking {
        assertEquals(inner.spec, w.spec, name)
        assertEquals(inner.risk, w.risk, name)
        assertEquals(inner.usesPhoneUi, w.usesPhoneUi, name)
        assertEquals(inner.needsUnlockedScreen, w.needsUnlockedScreen, name)
        assertEquals(timeout, w.timeoutMs, name)
        assertEquals(inner.riskFor(noArgs), w.riskFor(noArgs), name)
        assertEquals(inner.approvalRequest(noArgs), w.approvalRequest(noArgs), name)
        assertEquals(inner.approvalText(noArgs), w.approvalText(noArgs), name)
    }

    @Test
    fun theAppsWrappersForwardEverything() {
        val inner = Everything("everything")
        assertForwards("ConfirmingTool", inner, ConfirmingTool(inner))
        val long = Everything("spawn_subagent", timeoutMs = null)
        val limited = LongTools.apply(long)
        assertTrue(limited !== long, "a long tool gets its own limit")
        assertForwards("LongTools.WithTimeout", long, limited, timeout = LongTools.LIMITS.getValue("spawn_subagent"))
    }

    /**
     * Any class in the code base that takes a Tool and is a Tool must delegate with `Tool by <inner>`, so members
     * added to Tool later (as approvalRequest once was) are forwarded without anyone remembering to.
     */
    @Test
    fun everyWrapperInTheCodeBaseDelegates() {
        val root = File("..").canonicalFile
        val sources = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") && it.name != "test" && it.name != "androidTest" }
            .filter { it.isFile && it.extension == "kt" && "${File.separator}src${File.separator}main${File.separator}" in it.path }
            .toList()
        assertTrue(sources.size > 100, "found ${sources.size} source files under $root")
        val decl = Regex("""class\s+(\w+)\s*(?:internal\s+constructor\s*)?\(([^)]*)\)\s*:\s*([^{\n]*)""")
        val wrappers = ArrayList<String>()
        val bad = ArrayList<String>()
        for (f in sources) {
            for (m in decl.findAll(f.readText())) {
                val (name, params, supers) = m.destructured
                if (!Regex("""\w+\s*:\s*Tool\b(?!\w)""").containsMatchIn(params)) continue
                if (!Regex("""(^|[\s,])Tool\b(?![\w.])""").containsMatchIn(supers)) continue
                wrappers += name
                if (!Regex("""\bTool\s+by\s+\w+""").containsMatchIn(supers)) bad += "$name (${f.relativeTo(root)})"
            }
        }
        assertTrue("GatedTool" in wrappers && "ConfirmingTool" in wrappers && "ScreenTool" in wrappers && "WithTimeout" in wrappers, "wrappers found: $wrappers")
        assertTrue(bad.isEmpty(), "tool wrappers that do not delegate with `Tool by`: $bad")
    }
}
