package atom.app.agent

import atom.core.agent.ModelLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Nobody answered an approval in time: by default the run pauses and the question stays open, nothing is declined. */
class ApprovalWaitTest {
    @Test
    fun aTimeoutPausesAndTheLaterAnswerStillCounts() = runTest {
        val answer = CompletableDeferred<Boolean>()
        var paused = false
        val result = async { ApprovalWait.await(answer, waitMs = ModelLimits.APPROVAL_WAIT_MS, pauses = true) { paused = true } }
        advanceTimeBy(ModelLimits.APPROVAL_WAIT_MS - 1)
        runCurrent()
        assertFalse(paused, "still within the wait")
        advanceTimeBy(2)
        runCurrent()
        assertTrue(paused, "the run paused instead of declining")
        assertFalse(result.isCompleted)
        // The user comes back an hour later and allows it.
        advanceTimeBy(60 * 60_000L)
        answer.complete(true)
        assertTrue(result.await())
    }

    @Test
    fun withPauseOffATimeoutDeclines() = runTest {
        var paused = false
        val result = async { ApprovalWait.await(CompletableDeferred(), waitMs = 60_000, pauses = false) { paused = true } }
        advanceTimeBy(60_001)
        assertFalse(result.await())
        assertFalse(paused)
    }

    @Test
    fun anAnswerInTimeIsUsedAsIs() = runTest {
        val answer = CompletableDeferred<Boolean>()
        val result = async { ApprovalWait.await(answer, waitMs = 60_000, pauses = true) { error("no pause") } }
        advanceTimeBy(10_000)
        answer.complete(false)
        assertFalse(result.await())
    }

    @Test
    fun aParkedQuestionIsDeclinedOnlyAfterADay() = runTest {
        var paused = false
        val result = async { ApprovalWait.await(CompletableDeferred(), waitMs = 1_000, pauses = true) { paused = true } }
        advanceTimeBy(1_001 + ApprovalWait.PARKED_MS)
        assertFalse(result.await())
        assertTrue(paused)
    }

    @Test
    fun longToolsMatchTheirClientsAndTheSubagentBudget() {
        assertEquals(340_000L, LongTools.LIMITS.getValue("generate_image"))
        assertEquals(340_000L, LongTools.LIMITS.getValue("edit_image"))
        // Never below the old 15 and 20 minutes, always a minute above the subagent budget.
        assertTrue(LongTools.limitFor("spawn_subagent")!! >= AdvancedLimits.subagentBudgetMs() + 60_000)
        assertEquals(20 * 60_000L, LongTools.limitFor("spawn_subagents_parallel"))
    }
}
