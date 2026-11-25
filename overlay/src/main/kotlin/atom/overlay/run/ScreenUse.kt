package atom.overlay.run

import atom.ui.feature.HostRun

/** Guesses from tool names whether a run is using the phone's screen (observing it or acting on it). */
object ScreenUse {
    private val names = setOf(
        "read_screen", "observe_screen", "screenshot", "ui_tree",
        "tap", "long_press", "double_tap", "type_text", "input_text", "paste_text", "scroll", "swipe",
        "global_action", "press_key", "system_key", "open_app", "launch_app", "open_url", "open_link",
    )
    private val tokens = setOf("tap", "click", "swipe", "scroll", "gesture", "drag", "screen", "screenshot", "observe", "ui")

    fun isScreenTool(tool: String): Boolean {
        val lower = tool.lowercase()
        if (lower in names) return true
        return split(tool).any { it in tokens }
    }

    fun looksLikeScreenUse(run: HostRun): Boolean = run.phase.active && run.steps.any { isScreenTool(it.tool) }

    private fun split(name: String): List<String> =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
}
