package atom.extend

import android.content.Context
import android.content.res.AssetManager
import atom.core.tool.Tool
import atom.extend.mcp.McpManager
import atom.extend.memory.MemoryStore
import atom.extend.security.KeystoreSecretStore
import atom.extend.skills.BuiltInSkills
import atom.extend.skills.GitHubSkillSource
import atom.extend.skills.SkillInstaller
import atom.extend.skills.SkillStore
import atom.extend.subagent.SubagentEnvironment
import atom.extend.subagent.SubagentRunner
import atom.extend.subagent.SubagentStore
import java.io.File

/** Lazily opened stores. Opening one reads a small file; nothing starts, schedules or connects. */
internal object ExtendRuntime {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private val app: Context get() = checkNotNull(appContext) { "ExtendFeature used before onAppCreate" }

    val root: File by lazy { File(app.filesDir, "atom4_extend") }

    val memory: MemoryStore by lazy { MemoryStore(root) }

    val skills: SkillStore by lazy { SkillStore(File(root, "skills"), AssetSkills(app.assets)) }

    val installer: SkillInstaller by lazy { SkillInstaller(skills, GitHubSkillSource(ExtendHttp.get)) }

    private val defaultSecrets: SecretStore by lazy { KeystoreSecretStore(app) }

    val secrets: SecretStore get() = Extend.secrets ?: defaultSecrets

    val mcp: McpManager by lazy { McpManager(File(root, "mcp"), { secrets }) }

    val subagents: SubagentStore by lazy { SubagentStore(root) }

    fun runner(ownTools: () -> List<Tool>): SubagentRunner? {
        val engines = Extend.engines ?: return null
        val scope = Extend.toolScope
        return SubagentRunner(
            SubagentEnvironment(
                engines = engines,
                parentTools = { taskId -> scope?.toolsFor(taskId) ?: ownTools() },
                categoryOf = { scope?.categoryOf(it) },
                controlFor = { taskId -> scope?.controlFor(taskId) ?: atom.core.agent.RunControl.None },
                settings = { subagents.settings.value },
                timeBudgetMs = scope?.subagentTimeBudgetMs ?: atom.core.agent.ModelLimits.SUBAGENT_MS,
            ),
        )
    }
}

private class AssetSkills(private val assets: AssetManager) : BuiltInSkills {
    private val base = "extend/skills"

    override fun names(): List<String> = runCatching { assets.list(base)?.toList() }.getOrNull().orEmpty()

    override fun files(name: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(rel: String) {
            val children = runCatching { assets.list("$base/$name${if (rel.isEmpty()) "" else "/$rel"}") }.getOrNull().orEmpty()
            if (children.isEmpty()) {
                if (rel.isNotEmpty()) out += rel
                return
            }
            children.forEach { walk(if (rel.isEmpty()) it else "$rel/$it") }
        }
        walk("")
        return out.sorted()
    }

    override fun read(name: String, path: String): ByteArray? =
        runCatching { assets.open("$base/$name/$path").use { it.readBytes() } }.getOrNull()
}
