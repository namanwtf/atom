package atom.linux

import kotlinx.coroutines.flow.StateFlow

/**
 * The app's shared root status, so Linux never runs a superuser check of its own: Root mode and the root
 * terminal follow the same grant every other screen shows. Set by the app before Linux is first used
 * ([LinuxEnv.rootLink]); without it Linux probes su itself.
 */
interface RootLink {
    /** True while root is granted; changes as soon as the shared status does. */
    val granted: StateFlow<Boolean>

    /** True if root may be used now; may run the shared check when it never ran (never re-asks after a refusal). */
    suspend fun ensure(): Boolean

    /** The user's own request (choosing Root mode): asks again, like Check again on the Permissions page. */
    suspend fun authorize(): Boolean
}
