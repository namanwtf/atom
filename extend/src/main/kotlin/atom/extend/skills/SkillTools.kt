package atom.extend.skills

import atom.core.llm.ToolSpec
import atom.core.tool.Risk
import atom.core.tool.Tool
import atom.core.tool.ToolContext
import atom.core.tool.ToolResult
import atom.core.tool.optInt
import atom.core.tool.optString
import atom.core.tool.schema
import atom.core.tool.string
import kotlinx.serialization.json.JsonObject
import java.io.File

object SkillTools {
    private const val PAGE_CHARS = 24_000

    fun create(store: SkillStore, installer: SkillInstaller): List<Tool> = listOf(read(store), installGitHub(installer), write(store))

    private fun tool(spec: ToolSpec, risk: Risk, body: suspend (JsonObject, ToolContext) -> ToolResult): Tool = object : Tool {
        override val spec = spec
        override val risk = risk
        override suspend fun run(args: JsonObject, ctx: ToolContext) = body(args, ctx)
    }

    private fun read(store: SkillStore) = tool(
        ToolSpec(
            name = "skill_read",
            description = "Load an installed skill: its SKILL.md instructions and the list of its files. Give 'path' to " +
                "read one of the skill's files when the instructions point to it. Never runs anything.",
            parameters = schema {
                string("name", "Skill name from the Skills list.", required = true)
                string("path", "File inside the skill, e.g. \"reference/forms.md\". Omit for SKILL.md.")
                integer("offset", "Character offset for long files.")
            },
        ),
        Risk.Read,
    ) { args, _ ->
        val name = args.string("name").trim()
        val info = store.get(name) ?: return@tool ToolResult.error("No skill named \"$name\". Installed: ${store.skills.value.joinToString { it.name }}.")
        if (!info.enabled) return@tool ToolResult.error("The skill \"$name\" is switched off in Settings → Skills.")
        val path = args.optString("path")?.trim()?.takeIf { it.isNotEmpty() } ?: SkillManifest.FILE
        val bytes = store.read(name, path) ?: return@tool ToolResult.error("\"$path\" is not a file of the skill \"$name\".")
        if (bytes.take(8_000).any { it == 0.toByte() }) {
            return@tool ToolResult.ok("\"$path\" is a binary file (${bytes.size} bytes).${location(store, info)}")
        }
        val text = bytes.toString(Charsets.UTF_8)
        val offset = (args.optInt("offset") ?: 0).coerceIn(0, text.length)
        val page = text.substring(offset, minOf(text.length, offset + PAGE_CHARS))
        ToolResult.ok(buildString {
            append("Skill \"$name\" · $path\n\n")
            append(page)
            if (offset + PAGE_CHARS < text.length) append("\n…[more: offset=${offset + PAGE_CHARS}]")
            if (path == SkillManifest.FILE) {
                val files = store.files(name).filter { it.first != SkillManifest.FILE }
                if (files.isNotEmpty()) {
                    append("\n\nFiles in this skill (read with path):\n")
                    files.take(100).forEach { (p, size) -> append("- $p (${size} B)\n") }
                    if (files.size > 100) append("- … ${files.size - 100} more\n")
                }
                append(location(store, info))
                append("\nScripts in a skill never run automatically; run one only with a terminal tool, which asks the user.")
            }
        })
    }

    private fun location(store: SkillStore, info: SkillInfo): String =
        if (info.origin == SkillOrigin.User) "\nOn disk: ${store.userSkillDir(info.name).absolutePath}" else ""

    private fun installGitHub(installer: SkillInstaller) = tool(
        ToolSpec(
            name = "skill_install_github",
            description = "Install skills from a public GitHub repository, tree (folder) or blob (SKILL.md) URL. Every " +
                "folder with a SKILL.md becomes a skill, up to ${SkillInstaller.MAX_PER_INSTALL} per install. They are " +
                "usable from the next message. Replacing an installed skill asks the user first.",
            parameters = schema {
                string("url", "e.g. https://github.com/owner/repo/tree/main/skills/pdf", required = true)
                string("names", "Comma-separated folder names to install when the location holds many skills.")
            },
        ),
        Risk.Act,
    ) { args, ctx ->
        val names = args.optString("names")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val result = installer.installGitHub(args.string("url"), names) { existing ->
            ctx.confirm("Replace installed skill${if (existing.size > 1) "s" else ""} ${existing.joinToString()} with the version from GitHub?")
        }
        report(result.report, "from ${result.source}", result.truncated)
    }

    private fun write(store: SkillStore) = tool(
        ToolSpec(
            name = "skill_write",
            description = "Create or update a user skill (SKILL.md with name, description and instructions), optionally " +
                "adding one resource file per call. Updating an existing skill asks the user. Built-in skills cannot be changed.",
            parameters = schema {
                string("name", "kebab-case name, e.g. \"weekly-report\".", required = true)
                string("description", "What the skill does and when to use it (one or two sentences).", required = true)
                string("instructions", "Markdown body of SKILL.md: steps, rules, examples.", required = true)
                string("resource_path", "Optional extra file inside the skill, e.g. \"templates/report.md\".")
                string("resource_content", "Text of that file.")
            },
        ),
        Risk.Act,
    ) { args, ctx ->
        val name = args.string("name").trim()
        SkillManifest.validateName(name)?.let { return@tool ToolResult.error(it) }
        if (store.isBuiltIn(name)) return@tool ToolResult.error("\"$name\" is a built-in skill and cannot be changed. Choose another name.")
        val manifest = SkillManifest(name, args.string("description").trim(), args.string("instructions"))
        SkillManifest.parse(manifest.render()).exceptionOrNull()?.let { return@tool ToolResult.error(it.message ?: "Invalid skill") }
        val staging = store.newStaging()
        val dir = File(staging, name).apply { mkdirs() }
        try {
            store.userSkillDir(name).takeIf { it.isDirectory }?.copyRecursively(dir, overwrite = true)
            File(dir, SkillManifest.FILE).writeText(manifest.render())
            val resPath = args.optString("resource_path")?.trim()?.takeIf { it.isNotEmpty() }
            if (resPath != null) {
                val rel = SkillZip.safeRelativePath(resPath)?.takeIf { it != SkillManifest.FILE }
                    ?: return@tool ToolResult.error("Unsafe resource_path \"$resPath\".").also { store.discard(staging) }
                File(dir, rel).apply { parentFile?.mkdirs() }.writeText(args.optString("resource_content").orEmpty())
            }
        } catch (e: Exception) {
            store.discard(staging)
            throw e
        }
        val report = store.install(listOf(StagedSkill(manifest, dir))) { ctx.confirm("Update the skill \"$name\"?") }
        report(report, "", truncated = false)
    }

    private fun report(r: InstallReport, from: String, truncated: Boolean): ToolResult {
        if (r.installed.isEmpty()) return ToolResult.error("Nothing was installed. ${r.skipped.joinToString("; ")}")
        return ToolResult.ok(buildString {
            append("Installed ${r.installed.joinToString()}${if (from.isNotEmpty()) " $from" else ""}. Usable from the next message.")
            if (r.skipped.isNotEmpty()) append(" Skipped: ${r.skipped.joinToString("; ")}.")
            if (truncated) append(" GitHub truncated the repository listing; some skills may be missing.")
        })
    }
}
