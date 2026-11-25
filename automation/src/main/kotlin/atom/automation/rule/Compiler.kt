package atom.automation.rule

import atom.automation.EngineProvider
import atom.core.llm.ChatMessage
import atom.core.llm.LlmEvent
import atom.core.llm.LlmRequest
import atom.core.llm.ToolSpec
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject

data class CompileResult(val rule: Rule?, val warnings: List<String> = emptyList(), val errors: List<String> = emptyList()) {
    val ok: Boolean get() = rule != null && errors.isEmpty()
}

/** Turns a sentence into an editable rule. Called once, when the user creates the automation. */
fun interface RuleCompiler {
    suspend fun compile(sentence: String): CompileResult
}

/**
 * Asks the configured model for strict rule JSON, then validates and repairs it locally. When the repaired rule is
 * still invalid it sends the problems back once for a corrected answer.
 */
class LlmRuleCompiler(
    private val engines: EngineProvider,
    private val tools: () -> List<ToolSpec>,
) : RuleCompiler {

    override suspend fun compile(sentence: String): CompileResult {
        val selection = engines.current() ?: return CompileResult(null, errors = listOf("Set up a model first: compiling an automation uses it once"))
        val catalog = tools()
        val messages = mutableListOf(ChatMessage.system(systemPrompt(catalog)), ChatMessage.user(sentence.trim()))
        var last: ParseResult? = null
        repeat(2) { attempt ->
            val reply = runCatching { ask(selection, messages) }.getOrElse {
                return CompileResult(null, errors = listOf("The model could not be reached: ${it.message ?: it::class.simpleName}"))
            }
            val parsed = RuleJson.parse(reply, catalog.takeIf { it.isNotEmpty() }, sentence.trim())
            last = parsed
            if (parsed.ok) return CompileResult(parsed.rule, parsed.warnings)
            if (attempt == 0) {
                messages += ChatMessage.assistant(reply)
                messages += ChatMessage.user(
                    "That rule has problems:\n" + parsed.errors.joinToString("\n") { "- $it" } +
                        "\nReply with the corrected JSON object only.",
                )
            }
        }
        val p = last!!
        return CompileResult(p.rule, p.warnings, p.errors)
    }

    private suspend fun ask(selection: atom.automation.EngineSelection, messages: List<ChatMessage>): String {
        val out = StringBuilder()
        selection.engine.stream(
            LlmRequest(
                model = selection.model,
                messages = messages,
                maxOutputTokens = 1500,
                temperature = 0.0,
                reasoning = selection.reasoning,
            ),
        ).collect { if (it is LlmEvent.TextDelta) out.append(it.text) }
        return out.toString()
    }

    companion object {
        private const val MAX_TOOLS = 150

        fun systemPrompt(tools: List<ToolSpec>): String = buildString {
            appendLine("You convert one sentence describing a phone automation into a single JSON object. Reply with JSON only, no prose, no code fence.")
            appendLine()
            appendLine("Shape:")
            appendLine("""{"name": short title, "trigger": {...}, "conditions": [...], "actions": [...]}""")
            appendLine()
            appendLine("trigger (exactly one), by \"type\":")
            appendLine("""- schedule {"schedule": "daily 23:00" | "weekdays 08:30" | "weekly mon,fri 09:00" | "every 30m" | "cron 0 9 * * 1-5"}""")
            appendLine("- charger_connected | charger_disconnected | screen_on | screen_off | unlocked | headset_plugged | headset_unplugged | boot_completed")
            appendLine("""- battery_level {"percent": 1-100, "direction": "below"|"above"}""")
            appendLine("""- wifi_connected | wifi_disconnected {"ssid": optional}""")
            appendLine("""- bluetooth_connected | bluetooth_disconnected {"device": optional name}""")
            appendLine("""- airplane_mode | dnd_changed {"on": true|false|omit for any change}""")
            appendLine("""- location {"latitude", "longitude", "radius_m", "transition": "enter"|"leave", "label"} (use 0,0 if the place is unknown; the user will pick it)""")
            appendLine("""- notification {"app": optional app name or package, "contains": optional text}""")
            appendLine("""- app_opened {"app": app name or package}""")
            appendLine()
            appendLine("conditions (all must hold when the trigger fires), by \"type\":")
            appendLine("""- time_window {"start": "HH:mm", "end": "HH:mm"} (may cross midnight; "after 11 PM" = start 23:00, end 06:00)""")
            appendLine("""- days {"days": ["mon","tue",...]}""")
            appendLine("""- battery {"min": %, "max": %}""")
            appendLine("""- charging {"charging": true|false}""")
            appendLine("""- wifi_ssid {"ssid": name, "connected": true|false}""")
            appendLine("""- rooted {"rooted": true|false}""")
            appendLine()
            appendLine("actions (run in order), by \"type\":")
            appendLine("""- tool {"tool": exact tool name from the list below, "args": {...matching its parameters}} for anything a tool can do directly (settings, toggles, device controls)""")
            appendLine("""- agent {"prompt": what Atom should do, "wait_for": "none"|"charging"|"idle", "capabilities": subset of ["web","device_read","device_control","terminal","mcp","app_ui"]} only for work that needs reasoning (summaries, research, reading messages). Use wait_for "charging" for heavy work that is not urgent.""")
            appendLine("""- notify {"title", "text"}""")
            appendLine("""- wait {"seconds": 1-600}""")
            appendLine("""- stop_unless {"condition": {a condition object}}""")
            appendLine("Strings may contain {{event.app}}, {{event.title}}, {{event.text}}, {{time}}, {{battery}}.")
            appendLine("Prefer tool actions over agent actions whenever a listed tool fits. Never invent tool names.")
            if (tools.isNotEmpty()) {
                appendLine()
                appendLine("Tools:")
                tools.take(MAX_TOOLS).forEach { t ->
                    val params = (t.parameters["properties"] as? JsonObject)?.keys?.joinToString(", ").orEmpty()
                    append("- ").append(t.name).append("(").append(params).append("): ").appendLine(t.description.lineSequence().first().take(120))
                }
            }
        }
    }
}
