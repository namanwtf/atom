package atom.extend.subagent

import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolArgumentException
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object SubagentTools {

    fun create(store: SubagentStore, runner: SubagentRunner): List<Tool> =
        listOf(spawn(store, runner), parallel(store, runner), save(store), delete(store))

    private fun tool(spec: ToolSpec, risk: Risk, body: suspend (JsonObject, ToolContext) -> ToolResult): Tool = object : Tool {
        override val spec = spec
        override val risk = risk
        override suspend fun run(args: JsonObject, ctx: ToolContext) = body(args, ctx)
    }

    private fun agentNames(store: SubagentStore) = store.all().map { it.name } + SubagentDef.GENERAL

    private fun resolve(store: SubagentStore, agent: String?, instructions: String?, tools: String?): SubagentDef {
        val name = agent?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: SubagentDef.GENERAL
        if (name == SubagentDef.GENERAL) {
            return BuiltInSubagents.general(instructions, tools?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() })
        }
        return store.find(name) ?: throw ToolArgumentException("No subagent \"$name\". Available: ${agentNames(store).joinToString()}.")
    }

    private fun format(outcomes: List<SubagentOutcome>): ToolResult {
        val text = outcomes.joinToString("\n\n") { o ->
            val head = when (o.status) {
                SubagentStatus.Done -> "finished"
                SubagentStatus.Partial -> "stopped early (partial)"
                SubagentStatus.Failed -> "failed"
                SubagentStatus.Cancelled -> "was cancelled"
                SubagentStatus.Running -> "is running"
            }
            "Subagent ${o.agent} $head · ${o.toolCalls} tool calls · ${o.inputTokens} in / ${o.outputTokens} out tokens\n${o.summary}"
        }
        return ToolResult(text, isError = outcomes.all { it.status == SubagentStatus.Failed }, sensitive = outcomes.any { it.sensitive })
    }

    private fun spawn(store: SubagentStore, runner: SubagentRunner) = tool(
        ToolSpec(
            name = "spawn_subagent",
            description = "Hand a self-contained task to a subagent that works in its own fresh context with a limited " +
                "set of your tools, and get back its summary. It cannot see this conversation, so describe the task " +
                "completely. Use \"general\" with instructions for a one-off helper.",
            parameters = schema {
                string("agent", "Subagent to use.", enum = agentNames(store))
                string("task", "Complete description of the task and what to return.", required = true)
                string("instructions", "For \"general\" only: the helper's role and rules.")
                string("tools", "For \"general\" only: comma-separated categories (read, web, file, terminal, mcp).")
            },
        ),
        Risk.Read,
    ) { args, ctx ->
        val def = resolve(store, args.optString("agent"), args.optString("instructions"), args.optString("tools"))
        format(listOf(runner.run(SubagentTask(def, args.string("task")), ctx)))
    }

    private fun parallel(store: SubagentStore, runner: SubagentRunner) = tool(
        ToolSpec(
            name = "spawn_subagents_parallel",
            description = "Run up to ${SubagentRunner.MAX_PARALLEL} independent subagent tasks at the same time and get " +
                "all their summaries. Only for tasks that do not depend on each other.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("tasks") {
                        put("type", "array")
                        put("maxItems", SubagentRunner.MAX_PARALLEL)
                        put("description", "The tasks to run.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("agent") {
                                    put("type", "string")
                                    putJsonArray("enum") { agentNames(store).forEach { add(JsonPrimitive(it)) } }
                                }
                                putJsonObject("task") {
                                    put("type", "string")
                                    put("description", "Complete description of the task.")
                                }
                            }
                            putJsonArray("required") { add(JsonPrimitive("task")) }
                        }
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("tasks")) }
            },
        ),
        Risk.Read,
    ) { args, ctx ->
        val items = (args["tasks"] as? JsonArray) ?: throw ToolArgumentException("Missing required array 'tasks'")
        val tasks = items.mapNotNull { it as? JsonObject }.map { o ->
            val task = (o["task"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw ToolArgumentException("Every task needs a 'task' text.")
            SubagentTask(resolve(store, (o["agent"] as? JsonPrimitive)?.contentOrNull, null, null), task)
        }
        if (tasks.isEmpty() || tasks.size > SubagentRunner.MAX_PARALLEL) {
            throw ToolArgumentException("Give between 1 and ${SubagentRunner.MAX_PARALLEL} tasks.")
        }
        format(runner.runAll(tasks, ctx))
    }

    private fun save(store: SubagentStore) = tool(
        ToolSpec(
            name = "subagent_save",
            description = "Create or update a custom subagent the user asked for (saved as agents/<name>.md). Updating an " +
                "existing one asks the user.",
            parameters = schema {
                string("name", "kebab-case name, e.g. \"code-reviewer\".", required = true)
                string("description", "One line: what it does.", required = true)
                string("when_to_use", "When the main agent should hand work to it.", required = true)
                string("tools", "Comma-separated: read, web, file, terminal, mcp, memory, device (default read, file, terminal, mcp).")
                string("instructions", "Its system instructions.", required = true)
            },
        ),
        Risk.Act,
    ) { args, ctx ->
        val name = args.string("name").trim()
        SubagentDef.validateName(name)?.let { return@tool ToolResult.error(it) }
        val tools = args.optString("tools")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: SubagentDef.DEFAULT_TOOLS
        if (store.exists(name) && !ctx.confirm("Update the subagent \"$name\"?")) {
            return@tool ToolResult.error("The user declined updating \"$name\".")
        }
        store.save(SubagentDef(name, args.string("description").trim(), args.string("when_to_use").trim(), tools, args.string("instructions")))
        ToolResult.ok("Saved subagent \"$name\" (tools: ${tools.joinToString()}). Usable now with spawn_subagent.")
    }

    private fun delete(store: SubagentStore) = tool(
        ToolSpec(
            name = "subagent_delete",
            description = "Delete a custom subagent the user no longer wants.",
            parameters = schema { string("name", "Its name.", required = true) },
        ),
        Risk.Sensitive,
    ) { args, _ ->
        val name = args.string("name").trim()
        if (name in BuiltInSubagents.names) return@tool ToolResult.error("Built-in subagents cannot be deleted.")
        if (store.delete(name)) ToolResult.ok("Deleted subagent \"$name\".") else ToolResult.error("No custom subagent \"$name\".")
    }
}
