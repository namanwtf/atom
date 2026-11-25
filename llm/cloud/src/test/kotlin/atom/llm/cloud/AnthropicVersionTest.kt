package atom.llm.cloud

import kotlin.test.Test
import kotlin.test.assertEquals

class AnthropicVersionTest {
    @Test
    fun blankOrMissingUsesTheDefault() {
        assertEquals(AnthropicEngine.ANTHROPIC_VERSION, AnthropicEngine.versionHeader(EngineOptions()))
        assertEquals(AnthropicEngine.ANTHROPIC_VERSION, AnthropicEngine.versionHeader(EngineOptions(anthropicVersion = "  ")))
    }

    @Test
    fun userValueIsTrimmedAndUsed() {
        assertEquals("2024-10-22", AnthropicEngine.versionHeader(EngineOptions(anthropicVersion = " 2024-10-22 ")))
    }

    @Test
    fun valuesThatCannotBeAHeaderFallBack() {
        assertEquals(AnthropicEngine.ANTHROPIC_VERSION, AnthropicEngine.versionHeader(EngineOptions(anthropicVersion = "2023 06\n01")))
    }
}
