package atom.device.switches

import atom.core.tool.ApprovalRequest
import atom.core.tool.PerCallRisk
import atom.core.tool.Risk
import atom.core.tool.ShellRisk
import atom.core.tool.ShellScope
import atom.core.tool.Tool
import atom.core.tool.ToolResult
import atom.core.tool.optString
import atom.device.tools.SimpleTool
import atom.device.tools.UiTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A tool wrapped by the gate (or the screen wrapper) keeps everything the runtime asks it: above all its approval
 * prompt. The gate once forwarded only part of it, so a gated shell showed raw "shell({...})" text and never offered
 * "allow all commands for this task".
 */
class GatedToolForwardingTest {
    private val noArgs = JsonObject(emptyMap())
    private fun cmd(c: String) = JsonObject(mapOf("command" to JsonPrimitive(c)))

    /** Shaped like DeviceTools' shell: judges each call and builds its prompt with [ShellRisk.approval]. */
    private fun shell(): Tool {
        val scope = ShellScope.android(packageName = "io.github.namanwtf.atom", dataDir = "/data/user/0/io.github.namanwtf.atom", cacheDir = "/data/user/0/io.github.namanwtf.atom/cache")
        return object : SimpleTool("shell", "Run a shell command.", Risk.Sensitive, noArgs, body = { _, _ -> ToolResult.ok("exit 0") }), PerCallRisk {
            override fun riskFor(args: JsonObject): Risk = ShellRisk.approvalRisk(args.optString("command").orEmpty(), scope)
            override fun approvalRequest(args: JsonObject): ApprovalRequest =
                ShellRisk.approval(args.optString("command").orEmpty(), scope, "Android's shell")
        }
    }

    @Test
    fun aGatedShellKeepsItsApprovalPrompt() {
        val gated = shell().gated(ToolSwitch.TerminalFiles)
        val request = assertNotNull(gated.approvalRequest(cmd("ls /sdcard")))
        assertTrue(request.action.startsWith("Run in Android's shell:\nls /sdcard"), request.action)
        assertTrue(request.taskGrantable, "offers \"allow all commands for this task\"")
        assertNotNull(request.reason)
        // A destructive command keeps asking even under a task grant.
        assertTrue(assertNotNull(gated.approvalRequest(cmd("rm -rf /sdcard"))).alwaysAsk)
        assertEquals(shell().riskFor(cmd("rm -rf /sdcard")), gated.riskFor(cmd("rm -rf /sdcard")))
    }

    /** A tool that answers every member with something other than the interface default. */
    private class Everything : Tool {
        override val spec = atom.core.llm.ToolSpec("everything", "All members set.", JsonObject(emptyMap()))
        override val risk = Risk.Sensitive
        override val usesPhoneUi = true
        override val needsUnlockedScreen = true
        override val timeoutMs: Long = 123_456
        override suspend fun run(args: JsonObject, ctx: atom.core.tool.ToolContext) = ToolResult.ok("ran")
        override fun riskFor(args: JsonObject) = Risk.Act
        override fun approvalRequest(args: JsonObject) = ApprovalRequest("Do everything", reason = "Because.", taskGrantable = true, alwaysAsk = true)
        override suspend fun approvalText(args: JsonObject) = "Plain text"
    }

    @Test
    fun everyDeviceWrapperForwardsEverything() = runBlocking {
        val wrappers = mapOf<String, (Tool) -> Tool>(
            "GatedTool" to { GatedTool(it, emptySet()) },
            "gated twice" to { GatedTool(GatedTool(it, setOf(ToolSwitch.TerminalFiles)), emptySet()) },
            "switchChecked" to { it.switchChecked() },
            "UiTools.ScreenTool" to { UiTools.ScreenTool(it) },
        )
        val inner = Everything()
        for ((name, wrap) in wrappers) {
            val w = wrap(inner)
            assertEquals(inner.spec, w.spec, name)
            assertEquals(inner.risk, w.risk, name)
            assertEquals(inner.usesPhoneUi, w.usesPhoneUi, name)
            assertEquals(true, w.needsUnlockedScreen, name)
            assertEquals(inner.timeoutMs, w.timeoutMs, name)
            assertEquals(inner.riskFor(noArgs), w.riskFor(noArgs), name)
            assertEquals(inner.approvalRequest(noArgs), w.approvalRequest(noArgs), name)
            assertEquals(inner.approvalText(noArgs), w.approvalText(noArgs), name)
        }
    }
}
