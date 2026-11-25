package atom.extend

import atom.extend.subagent.BuiltInSubagents
import atom.extend.subagent.SubagentRun
import atom.extend.subagent.SubagentRunLabels
import atom.extend.subagent.SubagentStatus
import atom.extend.subagent.SubagentStep
import atom.extend.subagent.SubagentStepState
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtendParityTest {
    private fun run(status: SubagentStatus, calls: Int = 0) = SubagentRun("r", "t", "explore", "find it", status, toolCalls = calls)

    @Test
    fun runStatusCopy() {
        assertEquals("Running…", SubagentRunLabels.status(run(SubagentStatus.Running)))
        assertEquals("Stopped", SubagentRunLabels.status(run(SubagentStatus.Cancelled)))
        assertEquals("Incomplete", SubagentRunLabels.status(run(SubagentStatus.Partial)))
        assertEquals("Completed · 3 tool calls", SubagentRunLabels.status(run(SubagentStatus.Done, 3)))
        assertEquals("Completed · 1 tool call", SubagentRunLabels.status(run(SubagentStatus.Done, 1)))
    }

    @Test
    fun earlierStepsFold() {
        val steps = (1..9).map { SubagentStep("$it", "tool_$it", "{}", SubagentStepState.Done) }
        val (folded, shown) = SubagentRunLabels.visibleSteps(steps)
        assertEquals(3, folded)
        assertEquals(listOf("tool_4", "tool_9"), listOf(shown.first().tool, shown.last().tool))
        assertEquals(0 to steps.take(2), SubagentRunLabels.visibleSteps(steps.take(2)))
    }

    @Test
    fun builtInNamesAreLowercase() {
        assertEquals(listOf("explore", "coder", "plan"), BuiltInSubagents.all.map { it.name })
    }
}
