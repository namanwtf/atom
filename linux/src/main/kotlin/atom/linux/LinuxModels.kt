package atom.linux

/** The distributions Atom can install. Sizes are approximate and only used for display and space checks. */
enum class Distro(
    val displayName: String,
    val downloadBytes: Long,
    val installedBytes: Long,
) {
    /** musl + busybox + apk: tiny, starts instantly. */
    Alpine("Alpine Linux", 4_100_000L, 12_000_000L),

    /** glibc + apt: the widest package selection. */
    Debian("Debian", 91_000_000L, 420_000_000L),
    ;

    /** The one-word name used in the terminal's environment switch and toasts. */
    val shortName: String get() = when (this) {
        Alpine -> "Alpine"
        Debian -> "Debian"
    }

    companion object {
        /** The distribution to preselect: Debian, unless only Alpine is already installed. */
        fun preferred(installed: Collection<Distro>): Distro =
            if (Alpine in installed && Debian !in installed) Alpine else Debian
    }
}

/** How the distro's processes are run. */
enum class RunMode {
    /** Real chroot via su in a private mount namespace: native speed, needs root. */
    Chroot,

    /** proot (ptrace-based): works without root, costs roughly one extra context switch per syscall. */
    Proot,
}

sealed interface LinuxState {
    data object NotInstalled : LinuxState

    /** [progress] is 0..1 across the whole install; [step] is a short human-readable phase. */
    data class Installing(
        val distro: Distro,
        val step: String,
        val progress: Float,
        /** Which planned step is running, e.g. "Base tools" (2 of 3). */
        val stage: String = "",
        val stageIndex: Int = 0,
        val stageCount: Int = 1,
    ) : LinuxState

    data class Ready(
        val distro: Distro,
        val sizeBytes: Long,
        /** The mode sessions actually use now. */
        val mode: RunMode,
        val release: String = "",
        val toolsInstalled: Boolean = false,
        val profiles: Set<atom.linux.install.Profile> = emptySet(),
        /** Root mode was chosen but su no longer works; the chroot files are kept and PRoot is offered. */
        val rootLost: Boolean = false,
        /** Steps left from an interrupted install ("Continue installation"). */
        val pendingSteps: List<String> = emptyList(),
    ) : LinuxState

    data class Failed(val message: String) : LinuxState
}

/** Result of a non-interactive command. Output is capped (see [LinuxEnv.run]). */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

/** Where a terminal, command or job runs. */
enum class ShellEnv(val label: String) {
    /** /system/bin/sh as the app user. */
    Android("Android"),

    /** A root shell through su (Magisk, KernelSU or APatch). */
    Root("Root"),

    /** The installed Linux distro. */
    Linux("Linux"),
}

/** How far the Linux environment has come; decides the status card's one main action and which sections show. */
enum class LinuxStage {
    NotInstalled, Installing, RootLost, BaseReady, ToolsReady;

    companion object {
        fun of(state: LinuxState): LinuxStage = when (state) {
            is LinuxState.Installing -> Installing
            is LinuxState.Ready -> when {
                state.rootLost -> RootLost
                !state.toolsInstalled -> BaseReady
                else -> ToolsReady
            }
            else -> NotInstalled
        }
    }
}
