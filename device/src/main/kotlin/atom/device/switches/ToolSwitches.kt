package atom.device.switches

import android.content.Context
import android.content.SharedPreferences
import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/** The Tool list master switches (Settings → Tool list). */
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
 * Persistent master switches. Read at every tool call through [isOn], so turning one off stops the next call of a
 * tool it covers, even mid-run. Losing a permission never changes these.
 */
object ToolSwitches {
    private const val PREFS = "atom4_tool_switches"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(ToolSwitch.entries.associateWith { it.default })
    val state: StateFlow<Map<ToolSwitch, Boolean>> = _state.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _state.value = ToolSwitch.entries.associateWith { p.getBoolean(it.key, it.default) }
    }

    fun isOn(switch: ToolSwitch): Boolean = _state.value[switch] ?: switch.default

    fun set(switch: ToolSwitch, on: Boolean) {
        _state.value = _state.value + (switch to on)
        prefs?.edit()?.putBoolean(switch.key, on)?.apply()
    }

    fun offMessage(off: List<ToolSwitch>): String =
        "This tool is turned off in Settings → Tool list (" + off.joinToString(", ") { "\"${it.title}\"" } + "). " +
            "Tell the user it is off; do not try to work around it."
}

/** Wraps [inner] so each call first checks [requires]; a switched-off tool explains itself instead of running. */
class GatedTool(val inner: Tool, val requires: Set<ToolSwitch>) : Tool {
    override val spec: ToolSpec get() = inner.spec
    override val risk: Risk get() = inner.risk
    override val usesPhoneUi: Boolean get() = inner.usesPhoneUi

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val off = requires.filterNot(ToolSwitches::isOn)
        if (off.isNotEmpty()) return ToolResult.error(ToolSwitches.offMessage(off))
        return inner.run(args, ctx)
    }
}

fun Tool.gated(vararg switches: ToolSwitch): Tool = GatedTool(this, switches.toSet())

/** True when [this] (or the tool a [GatedTool] wraps) asks the user itself with a plain-English preview. */
fun Tool.confirmsItself(): Boolean = this is atom.device.tools.ConfirmsItself || (this is GatedTool && inner.confirmsItself())
