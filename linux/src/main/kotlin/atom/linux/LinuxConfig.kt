package atom.linux

import atom.linux.install.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the user chose and what has been installed beyond the base system. Stored as JSON next to the distro. */
@Serializable
data class LinuxConfig(
    /** The mode the user picked; Proot ("Standard") is the default. */
    val preferredMode: RunMode = RunMode.Proot,
    val toolsInstalled: Boolean = false,
    val profiles: Set<Profile> = emptySet(),
    /** Installed version per profile, as the tool reports it. */
    val versions: Map<String, String> = emptyMap(),
    /** Steps of an interrupted or failed plan, for "Continue installation". */
    val pendingSteps: List<String> = emptyList(),
    val sharedFolders: List<SharedFolder> = emptyList(),
    /** Cap for background commands in minutes; 0 means no cap. */
    val jobCapMinutes: Int = 10,
    val lastResult: String? = null,
    val lastResultOk: Boolean = true,
    val lastResultAtMs: Long = 0,
    /** Set when the rootfs was used in chroot mode, so its files may be root-owned. */
    val usedChroot: Boolean = false,
    /** Terminal text size in sp (pinch or the setting); compact by default. */
    val terminalFontSp: Float = TerminalText.DEFAULT_SP,
)

/** Bounds for the terminal text size. */
object TerminalText {
    const val DEFAULT_SP = 12f
    const val MIN_SP = 7f
    const val MAX_SP = 28f

    fun clamp(sp: Float): Float = if (sp.isNaN()) DEFAULT_SP else sp.coerceIn(MIN_SP, MAX_SP)
}

internal class LinuxConfigStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    @Volatile private var cached: LinuxConfig? = null

    fun get(): LinuxConfig = cached ?: synchronized(lock) {
        cached ?: (runCatching { json.decodeFromString(LinuxConfig.serializer(), file.readText()) }.getOrNull() ?: LinuxConfig())
            .also { cached = it }
    }

    fun update(block: (LinuxConfig) -> LinuxConfig): LinuxConfig = synchronized(lock) {
        val next = block(get())
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(LinuxConfig.serializer(), next))
        tmp.renameTo(file)
        cached = next
        next
    }
}
