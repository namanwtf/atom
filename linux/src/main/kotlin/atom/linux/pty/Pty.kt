package atom.linux.pty

/** Thin JNI layer over libatom_pty.so; see src/main/cpp/pty.c. */
internal object Pty {
    init {
        System.loadLibrary("atom_pty")
    }

    const val SIGHUP = 1
    const val SIGINT = 2
    const val SIGKILL = 9
    const val SIGTERM = 15

    /**
     * Forks [path] (searched in PATH when it has no slash) on a new pty. Returns the master fd (non-blocking);
     * result = [pid, wakeReadFd, wakeWriteFd].
     */
    @JvmStatic
    external fun spawn(
        path: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?,
        rows: Int,
        cols: Int,
        result: IntArray,
    ): Int

    /** Spawns without a terminal (stdin /dev/null) in its own session. result = [pid, stdoutFd, stderrFd]. */
    @JvmStatic
    external fun spawnPiped(path: String, argv: Array<String>, envp: Array<String>, cwd: String?, result: IntArray)

    @JvmStatic
    external fun setSize(fd: Int, rows: Int, cols: Int)

    /** Blocks; returns bytes read, or -1 when the terminal is gone or [wakeFd] was signalled. */
    @JvmStatic
    external fun read(fd: Int, wakeFd: Int, buf: ByteArray, len: Int): Int

    /** Returns 0 or -errno; gives up when [wakeFd] is signalled. */
    @JvmStatic
    external fun write(fd: Int, wakeFd: Int, buf: ByteArray, off: Int, len: Int): Int

    /** Releases every blocked [read] / [write] of the session owning [wakeWriteFd]. */
    @JvmStatic
    external fun wake(wakeWriteFd: Int)

    /** Blocks until [pid] exits; exit code or 128 + signal. */
    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun signal(pid: Int, sig: Int, group: Boolean)

    @JvmStatic
    external fun close(fd: Int)
}
