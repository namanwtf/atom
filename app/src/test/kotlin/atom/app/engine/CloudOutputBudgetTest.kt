package atom.app.engine

import atom.llm.cloud.ModelConfig
import atom.llm.cloud.PresetCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The output budget of a cloud run. 2048 tokens cut a page-sized `site_write` off mid call (more so on thinking
 * models, whose reasoning counts against it), so models known to allow more get more, never past their own cap.
 */
class CloudOutputBudgetTest {
    private fun budget(model: ModelConfig) = RoutedEngine.cloudMaxOutput(model.effectiveContextWindow, model.maxOutputTokens)

    @Test
    fun aKnownCapWinsUpToTheCeiling() {
        assertEquals(4_096, RoutedEngine.cloudMaxOutput(1_000_000, 4_096))
        assertEquals(12_000, RoutedEngine.cloudMaxOutput(null, 12_000))
        assertEquals(RoutedEngine.CLOUD_LARGE_OUTPUT, RoutedEngine.cloudMaxOutput(null, 128_000))
        assertEquals(1_000, RoutedEngine.cloudMaxOutput(8_192, 1_000))
    }

    @Test
    fun withoutACapTheWindowDecides() {
        assertEquals(RoutedEngine.CLOUD_WINDOW_OUTPUT, RoutedEngine.cloudMaxOutput(1_048_576, null))
        assertEquals(RoutedEngine.CLOUD_WINDOW_OUTPUT, RoutedEngine.cloudMaxOutput(128_000, null))
        assertEquals(RoutedEngine.CLOUD_MAX_OUTPUT, RoutedEngine.cloudMaxOutput(32_768, null))
        assertEquals(RoutedEngine.CLOUD_MAX_OUTPUT, RoutedEngine.cloudMaxOutput(null, null))
    }

    @Test
    fun theDefaultModelGetsRoomForAPageAndOldModelsKeepTheirCap() {
        val preset = PresetCatalog.providers.first { it.id == PresetCatalog.DEFAULT_PROVIDER }
            .models.first { it.id == PresetCatalog.DEFAULT_MODEL }.toConfig()
        assertTrue(budget(preset) > RoutedEngine.CLOUD_MAX_OUTPUT)
        assertEquals(4_096, budget(ModelConfig("claude-3-haiku-20240307")))
        assertEquals(RoutedEngine.CLOUD_WINDOW_OUTPUT, budget(ModelConfig("deepseek-chat")))
        assertEquals(RoutedEngine.CLOUD_MAX_OUTPUT, budget(ModelConfig("my-local-llama")))
    }
}
