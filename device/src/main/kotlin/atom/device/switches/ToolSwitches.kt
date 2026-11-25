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

    /** Master switches whose tools are all listed in the catalog, so per-tool switches can take over. */
    private val FOLDABLE = listOf(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead, ToolSwitch.SensitiveOps, ToolSwitch.TerminalFiles)

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
     * Once: a master switch that was off becomes "off" on each catalog tool it covered, and the master goes back on,
     * so the per-tool switches on the Tool list show and control the same effect. Call after every module registered
     * its catalog cards.
     */
    fun foldMasters(groups: List<ToolGroupInfo>) {
        val p = prefs ?: return
        if (p.getBoolean(K_FOLDED, false)) return
        val offMasters = FOLDABLE.filterNot(::isOn)
        val tools = foldedTools(groups, offMasters)
        if (tools.isNotEmpty()) setTools(tools, on = false)
        offMasters.forEach { set(it, true) }
        p.edit().putBoolean(K_FOLDED, true).apply()
    }

    /** The catalog tools covered by any of [offMasters]. */
    fun foldedTools(groups: List<ToolGroupInfo>, offMasters: Collection<ToolSwitch>): Set<String> =
        groups.filter { g -> g.switches.any { it in offMasters } }.flatMap { it.tools }.toSet()

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

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!ToolSwitches.isToolOn(spec.name)) return ToolResult.error(ToolSwitches.toolOffMessage(spec.name))
        val off = requires.filterNot(ToolSwitches::isOn)
        if (off.isNotEmpty()) return ToolResult.error(ToolSwitches.offMessage(off))
        return inner.run(args, ctx)
    }
}

fun Tool.gated(vararg switches: ToolSwitch): Tool = GatedTool(this, switches.toSet())

/** True when [this] (or the tool a [GatedTool] wraps) asks the user itself with a plain-English preview. */
fun Tool.confirmsItself(): Boolean = this is atom.device.tools.ConfirmsItself || (this is GatedTool && inner.confirmsItself())

/** Leaves out the tools switched off one by one on the Tool list (the model never sees them). */
fun List<Tool>.withoutSwitchedOff(): List<Tool> = filter { ToolSwitches.isToolOn(it.spec.name) }
