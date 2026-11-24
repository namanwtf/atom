package atom.app.ui.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import atom.device.ShellTier
import atom.device.Shells
import atom.linux.AndroidShell
import atom.linux.LinuxEnv
import atom.linux.LinuxState
import atom.linux.term.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which program a terminal tab runs. */
enum class TerminalKind { Android, Root, Linux }

/**
 * Owns the open terminal sessions (one per kind) across configuration changes. Sessions start lazily once
 * the screen knows its size, and every session is closed (its process tree killed) when the screen goes away.
 */
class TerminalViewModel(context: Context) : ViewModel() {
    private val app = context.applicationContext
    val linux: LinuxEnv = LinuxEnv.get(app)

    private val _rootAvailable = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable.asStateFlow()

    private val _kind = MutableStateFlow(
        if (linux.state.value is LinuxState.Ready) TerminalKind.Linux else TerminalKind.Android,
    )
    val kind: StateFlow<TerminalKind> = _kind.asStateFlow()

    private val _session = MutableStateFlow<TerminalSession?>(null)
    val session: StateFlow<TerminalSession?> = _session.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val sessions = HashMap<TerminalKind, TerminalSession>()
    private var size: Pair<Int, Int>? = null
    @Volatile private var linuxPrepared = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _rootAvailable.value = runCatching { Shells.detect(app).tier == ShellTier.Root }.getOrDefault(false)
        }
    }

    fun select(kind: TerminalKind) {
        if (_kind.value == kind) return
        _kind.value = kind
        _error.value = null
        _session.value = sessions[kind]
        if (sessions[kind] == null) size?.let { (c, r) -> open(kind, c, r) }
    }

    /** Called with the grid size that fits the screen; opens the session on first call, resizes after. */
    fun onSize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        size = cols to rows
        val current = sessions[_kind.value]
        if (current == null) open(_kind.value, cols, rows) else current.resize(cols, rows)
    }

    /** Starts a fresh session for the current kind (after the previous one exited). */
    fun restart() {
        val kind = _kind.value
        sessions.remove(kind)?.close()
        _session.value = null
        size?.let { (c, r) -> open(kind, c, r) }
    }

    private fun open(kind: TerminalKind, cols: Int, rows: Int) {
        viewModelScope.launch {
            try {
                if (kind == TerminalKind.Linux && !linuxPrepared) {
                    linux.refresh() // decides chroot vs proot once (may ask the root manager)
                    linuxPrepared = true
                }
                if (sessions[kind] != null || _kind.value != kind) return@launch
                val s = withContext(Dispatchers.IO) {
                    when (kind) {
                        TerminalKind.Android -> AndroidShell.openSession(app, cols, rows, root = false)
                        TerminalKind.Root -> AndroidShell.openSession(app, cols, rows, root = true)
                        TerminalKind.Linux -> linux.openSession(cols, rows)
                    }
                }
                sessions[kind] = s
                if (_kind.value == kind) _session.value = s
                size?.let { (c, r) -> s.resize(c, r) }
            } catch (e: Exception) {
                if (_kind.value == kind) _error.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    override fun onCleared() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
