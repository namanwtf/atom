package atom.core

import atom.core.agent.SmallModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallModelTest {
    @Test
    fun readsTheSizeFromCatalogNames() {
        assertEquals(0.6, SmallModel.billionsFromName("Qwen3 0.6B"))
        assertEquals(2.0, SmallModel.billionsFromName("Gemma 4 E2B"))
        assertEquals(1.5, SmallModel.billionsFromName("Qwen2.5 1.5B Instruct"))
        assertEquals(3.0, SmallModel.billionsFromName("Llama 3.2 3B Instruct"))
        assertEquals(4.0, SmallModel.billionsFromName("Qwen3 4B Instruct 2507"))
        assertEquals(0.6, SmallModel.billionsFromName("qwen3-0.6b-litertlm"))
        assertEquals(7.0, SmallModel.billionsFromName("Qwen2.5-7B-Instruct-Q4_K_M.gguf"))
        assertNull(SmallModel.billionsFromName("Phi-4 mini Instruct"))
    }

    @Test
    fun smallMeansUnderThreeBillion() {
        assertTrue(SmallModel.isSmall("Qwen3 0.6B", 613_722_264L))
        assertTrue(SmallModel.isSmall("Qwen2.5 1.5B Instruct", 1_117_320_736L))
        assertFalse(SmallModel.isSmall("Llama 3.2 3B Instruct", 2_019_377_696L))
        assertFalse(SmallModel.isSmall("Qwen3 4B", 2_497_280_256L))
        // No size in the name: judged by the file.
        assertFalse(SmallModel.isSmall("Phi-4 mini Instruct", 2_491_874_688L))
        assertTrue(SmallModel.isSmall("my-model.gguf", 700_000_000L))
        assertFalse(SmallModel.isSmall("my-model.gguf", 0L))
    }

    @Test
    fun recognisesAgentTasks() {
        assertTrue(
            SmallModel.looksLikeAgentTask(
                "In Atom's built-in agent browser (not an external browser app), search for today's top technology news and summarize it",
            ),
        )
        assertTrue(SmallModel.looksLikeAgentTask("Turn on Do Not Disturb."))
        assertTrue(SmallModel.looksLikeAgentTask("Open Settings and enable dark mode"))
        assertTrue(SmallModel.looksLikeAgentTask("Summarize https://example.com"))
        assertFalse(SmallModel.looksLikeAgentTask("hi"))
        assertFalse(SmallModel.looksLikeAgentTask("What is the capital of France?"))
        assertFalse(SmallModel.looksLikeAgentTask("Rewrite this text so it sounds friendlier: thanks for the context"))
        assertFalse(SmallModel.looksLikeAgentTask("Write a haiku about autumn"))
    }

    @Test
    fun warnsOnceForSmallOnDeviceModelsOnly() {
        val task = "Open the agent browser and read the news"
        assertTrue(SmallModel.shouldWarn(onDevice = true, small = true, prompt = task, alreadyWarned = false))
        assertFalse(SmallModel.shouldWarn(onDevice = true, small = true, prompt = task, alreadyWarned = true))
        assertFalse(SmallModel.shouldWarn(onDevice = true, small = false, prompt = task, alreadyWarned = false))
        assertFalse(SmallModel.shouldWarn(onDevice = false, small = true, prompt = task, alreadyWarned = false))
        // Normal chat stays quiet.
        assertFalse(SmallModel.shouldWarn(onDevice = true, small = true, prompt = "Tell me a joke", alreadyWarned = false))
    }
}
