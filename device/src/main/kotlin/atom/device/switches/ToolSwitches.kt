package atom.device.switches

import android.content.Context
import android.content.SharedPreferences
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.device.caps.ToolGroupInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The older master switches. The Tool list now has one switch per tool instead; these stay as the gate their tools
 * check, and [ToolSwitches.foldMasters] turns any that were switched off into the matching per-tool switches once.
 */
enum class ToolSwitch(val key: String, val title: String, val summary: String, val default: Boolean) {
    Web("web", "Web search, reading and browser", "Search the web, read pages and drive the agent browser.", true),
    DeviceDirect("device", "Device direct tools", "Screen control, apps, system toggles, calendar, alarms and phone context.", true),
    SensitiveRead(
        "sensitive_read", "Read sensitive device information",
        "Messages, one-time codes, notifications, call log, logs, Wi-Fi passwords and other apps' data.", true,
    ),
    SensitiveOps(
        "sensitive_ops", "Sensitive device operations",
        "Freeze or stop apps, firewall, protected settings, charge limit, refresh rate and location spoofing.", true,
    ),
    TerminalFiles("terminal", "Terminal and file tools", "Shell commands, Linux and reading or writing files.", true),
    Memory("memory", "Memory", "Let Atom read and update its long-term memory.", true),
    HostedSearch("hosted_search", "Provider-hosted web search", "Let the model provider search the web itself (may cost extra).", false),
    ImageGen("image_gen", "AI image generation", "Generate and edit images with the configured image service.", true),
}

/**
 * Persistent tool switches (Settings → Tool list): one per tool, plus the older master switches. Read at every tool
 * call through [isOn] / [isToolOn], so turning one off stops the next call, even mid-run. Losing a permission never
 * changes these.
 */
object ToolSwitches {
    private const val PREFS = "atom4_tool_switches"
    private const val K_DISABLED = "disabled_tools"
    private const val K_FOLDED = "masters_folded"

    /**
     * Master switches that become per-tool switches. HostedSearch is not one: provider-hosted search is a setting
     * of each provider.
     */
    private val FOLDABLE = listOf(
        ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead, ToolSwitch.SensitiveOps, ToolSwitch.TerminalFiles,
        ToolSwitch.Web, ToolSwitch.Memory, ToolSwitch.ImageGen,
    )

    /**
     * What each master switch said it covered on the old Tool list, as catalog card ids. Web, Memory and image
     * generation never gated a tool, but a user who had them off expects those tools to stay off.
     */
    private val VISIBLE_SCOPE: Map<ToolSwitch, Set<String>> = mapOf(
        ToolSwitch.Web to setOf("web", "browser"),
        ToolSwitch.Memory to setOf("memory"),
        ToolSwitch.ImageGen to setOf("image"),
        ToolSwitch.TerminalFiles to setOf("shell", "terminal", "files"),
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(ToolSwitch.entries.associateWith { it.default })
    val state: StateFlow<Map<ToolSwitch, Boolean>> = _state.asStateFlow()

    private val _disabled = MutableStateFlow<Set<String>>(emptySet())

    /** Names of the tools switched off one by one. */
    val disabledTools: StateFlow<Set<String>> = _disabled.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _state.value = ToolSwitch.entries.associateWith { p.getBoolean(it.key, it.default) }
        _disabled.value = p.getStringSet(K_DISABLED, null)?.toSet().orEmpty()
    }

    fun isOn(switch: ToolSwitch): Boolean = _state.value[switch] ?: switch.default

    fun set(switch: ToolSwitch, on: Boolean) {
        _state.value = _state.value + (switch to on)
        prefs?.edit()?.putBoolean(switch.key, on)?.apply()
    }

    fun isToolOn(name: String): Boolean = name !in _disabled.value

    fun setTool(name: String, on: Boolean) = setTools(listOf(name), on)

    /** Switches every tool in [names] on or off (a group's "Enable all" / "Disable all"). */
    fun setTools(names: Collection<String>, on: Boolean) {
        val next = if (on) _disabled.value - names.toSet() else _disabled.value + names
        if (next == _disabled.value) return
        _disabled.value = next
        prefs?.edit()?.putStringSet(K_DISABLED, next)?.apply()
    }

    /**
     * Once: every tool a master switch that was off really gated (its [GatedTool.requires], from [realGates]), plus
     * the catalog tools of that switch for tools not built on this phone yet, becomes "off" on its own, and the
     * master goes back on. The Tool list's per-tool switches then show and control the same effect. Call after
     * the tools were built once (that records their gates) and every module registered its catalog cards.
     */
    /** True until [foldMasters] has run once on this install. */
    fun needsFold(): Boolean = prefs?.getBoolean(K_FOLDED, false) == false

    fun foldMasters(groups: List<ToolGroupInfo>, realGates: Map<String, Set<ToolSwitch>>) {
        val p = prefs ?: return
        if (p.getBoolean(K_FOLDED, false)) return
        val offMasters = FOLDABLE.filterNot(::isOn)
        val tools = foldedTools(groups, realGates, offMasters)
        if (tools.isNotEmpty()) setTools(tools, on = false)
        offMasters.forEach { set(it, true) }
        p.edit().putBoolean(K_FOLDED, true).apply()
    }

    /** The tools covered by any of [offMasters]: by their real gate, by their catalog card, or by what the switch said. */
    fun foldedTools(groups: List<ToolGroupInfo>, realGates: Map<String, Set<ToolSwitch>>, offMasters: Collection<ToolSwitch>): Set<String> {
        if (offMasters.isEmpty()) return emptySet()
        val byGate = realGates.filterValues { gate -> gate.any { it in offMasters } }.keys
        val byCard = groups.filter { g -> g.switches.any { it in offMasters } }.flatMap { it.tools }
        val scopeIds = offMasters.flatMap { VISIBLE_SCOPE[it].orEmpty() }.toSet()
        val byScope = groups.filter { it.id in scopeIds }.flatMap { it.tools }
        return byGate + byCard + byScope
    }

    fun offMessage(off: List<ToolSwitch>): String =
        "This tool is turned off in Settings → Tool list (" + off.joinToString(", ") { "\"${it.title}\"" } + "). " +
            "Tell the user it is off; do not try to work around it."

    fun toolOffMessage(name: String): String =
        "The tool \"$name\" is turned off in Settings → Tool list. Tell the user it is off; do not try to work around it."
}

/** Wraps [inner] so each call first checks [requires] and the tool's own switch; a switched-off tool explains itself. */
class GatedTool(val inner: Tool, val requires: Set<ToolSwitch>) : Tool {
    override val spec: ToolSpec get() = inner.spec
    override val risk: Risk get() = inner.risk
    override val usesPhoneUi: Boolean get() = inner.usesPhoneUi
    override val timeoutMs: Long? get() = inner.timeoutMs

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ToolSwitches.isToolOn(spec.name)) return ToolResult.error(ToolSwitches.toolOffMessage(spec.name))
        val off = requires.filterNot(ToolSwitches::isOn)
        if (off.isNotEmpty()) return ToolResult.error(ToolSwitches.offMessage(off))
        return inner.run(args, ctx)
    }

    override suspend fun approvalText(args: JsonObject): String? = inner.approvalText(args)
}

fun Tool.gated(vararg switches: ToolSwitch): Tool {
    val gate = switches.toSet()
    ToolGates.record(spec.name, gate)
    return GatedTool(this, gate)
}

/**
 * The gate each gated tool really checks, recorded as tools are built. The one-time master conversion and the
 * catalog consistency check read it.
 */
object ToolGates {
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Set<ToolSwitch>>()

    fun record(name: String, gate: Set<ToolSwitch>) {
        seen[name] = gate
    }

    fun snapshot(): Map<String, Set<ToolSwitch>> = HashMap(seen)
}

/**
 * Every tool of a run, re-checked against its own Tool list switch on each call, so switching a tool off stops it
 * mid-run (subagents included: they share the run's tools). Gated tools already check.
 */
fun Tool.switchChecked(): Tool = if (this is GatedTool) this else GatedTool(this, emptySet())

/** True when [this] (or the tool a [GatedTool] wraps) asks the user itself with a plain-English preview. */
fun Tool.confirmsItself(): Boolean = this is atom.device.tools.ConfirmsItself || (this is GatedTool && inner.confirmsItself())

/**
 * True when "Ask before taps and typing" ([confirmAct]) should wrap [this] in an extra confirmation: an Act tool
 * that does not already ask with its own preview. Looks through [GatedTool], so a gated self-confirming tool
 * (freeze, force stop, write setting...) is asked about once, not twice.
 */
fun Tool.asksBeforeTaps(confirmAct: Boolean): Boolean = confirmAct && risk == Risk.Act && !confirmsItself()

/** Leaves out the tools switched off one by one on the Tool list (the model never sees them). */
fun List<Tool>.withoutSwitchedOff(): List<Tool> = filter { ToolSwitches.isToolOn(it.spec.name) }
