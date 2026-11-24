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
    data class Installing(val distro: Distro, val step: String, val progress: Float) : LinuxState

    data class Ready(val distro: Distro, val sizeBytes: Long, val mode: RunMode) : LinuxState

    data class Failed(val message: String) : LinuxState
}

/** Result of a non-interactive command. Output is capped (see [LinuxEnv.run]). */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)
