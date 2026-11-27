package atom.app.agent

import atom.core.tool.Tool
import atom.device.switches.asksBeforeTaps
import atom.device.switches.switchChecked
import atom.device.switches.withoutSwitchedOff

/** How a run wraps the tools it gathered; kept apart from TaskRunner so the whole chain is unit tested. */
internal object RunTools {
    /**
     * Tools switched off on the Tool list are left out; with "Ask before taps and typing" ([confirmAct]) Act tools and
     * per-call-risk tools ask first ([ConfirmingTool]); long tools get their own time limit ([LongTools]); and each call
     * re-checks the tool's own switch, so turning it off stops it mid-run. Every wrapper delegates, so each tool keeps
     * its own approval prompt (the shell's "allow all commands for this task"), risk and limits.
     */
    fun wrap(base: List<Tool>, confirmAct: Boolean): List<Tool> = base.withoutSwitchedOff()
        .map { if (it.asksBeforeTaps(confirmAct)) ConfirmingTool(it) else it }
        .map(LongTools::apply)
        .map { it.switchChecked() }
}
