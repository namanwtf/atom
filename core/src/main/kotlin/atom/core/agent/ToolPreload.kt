package atom.core.agent

import atom.core.llm.PromptedTools
import atom.core.tool.ToolLoadout

/**
 * Loads the tool groups a request likely needs before the first model call (the groups come from the app's intent
 * rules), within what the model's window allows. Cloud and roomy windows get every group asked for. A shared
 * on-device window keeps [MESSAGE_ROOM] for the task and its history after the answer and the tools, and a small
 * one (the compact tool list, 4096 tokens) gets at most [SMALL_MAX_GROUPS] groups.
 */
object ToolPreload {
    /** Tokens of a shared window kept for the messages when groups are preloaded. */
    const val MESSAGE_ROOM = 1_200

    /** Groups preloaded at most in a window small enough for the compact tool list. */
    const val SMALL_MAX_GROUPS = 2

    /** True when [config] is a small on-device window: preload only strong matches, and few of them. */
    fun small(config: AgentConfig): Boolean = config.context.sharedWindow?.let { PromptedTools.compact(it) } == true

    fun apply(loadout: ToolLoadout, groups: List<String>, config: AgentConfig): ToolLoadout.Preloaded {
        val shared = config.context.sharedWindow?.takeIf { it > 0 } ?: return loadout.preloadGroups(groups)
        val budget = shared - config.maxOutputTokens - ContextPolicy.SHARED_MARGIN - MESSAGE_ROOM
        val max = if (small(config)) SMALL_MAX_GROUPS else Int.MAX_VALUE
        return loadout.preloadGroups(groups, max) { specs -> config.toolTokens(specs) <= budget }
    }
}
