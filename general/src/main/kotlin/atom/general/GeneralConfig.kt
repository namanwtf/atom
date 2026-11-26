package atom.general

import atom.core.legacy.LegacyCleanup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Project links. Empty values hide the matching About rows ("Updates: not configured" for the update check). The
 * app may override them once at startup (e.g. from BuildConfig) before any screen opens.
 */
object GeneralConfig {
    /**
     * GitHub "owner/repo" whose Releases are the update channel. Empty = updates not configured. It is the same
     * repository the earlier Atom app checks, so its installs find this app's releases too (docs/UPDATES.md).
     */
    const val DEFAULT_UPDATE_REPO = "namanwtf/atom"

    /** Public source code URL (https). Empty = row hidden. */
    const val DEFAULT_SOURCE_URL = ""

    /** Feedback target: an e-mail address or an https URL (issues page, form). Empty = row hidden. */
    const val DEFAULT_FEEDBACK = ""

    @Volatile var updateRepo: String = DEFAULT_UPDATE_REPO
    @Volatile var sourceUrl: String = DEFAULT_SOURCE_URL
    @Volatile var feedback: String = DEFAULT_FEEDBACK
}

/** Hooks the app injects so General screens can respect app state without depending on app code. */
object GeneralHooks {
    /** True while any agent task runs; imports are refused until it returns false. */
    @Volatile var tasksRunning: () -> Boolean = { false }

    /** True while conversations are still being written to disk; imports are refused until it returns false. */
    @Volatile var conversationsSaving: () -> Boolean = { false }

    /** Optional "stop all tasks" action offered when an import is blocked by running tasks. */
    @Volatile var stopTasks: (() -> Unit)? = null

    /** Called on the main thread after a successful import, so the app can reload caches / selections. */
    @Volatile var onImported: (() -> Unit)? = null

    /** Import from the previous Atom app; the backup screen offers it while that app's data is on the phone. */
    @Volatile var legacyImport: LegacyImportHook? = null

    interface LegacyImportHook {
        /** True when the previous app's data is on this phone (a cheap file check). */
        fun available(): Boolean

        /** Runs the import again (only what is not imported yet) and returns the result text. */
        suspend fun run(): String

        /** Whether and how the backup screen offers to remove the previous app's files (cheap file checks). */
        fun cleanupOffer(): LegacyCleanup.Offer = LegacyCleanup.Offer.Hidden

        /** Removes the previous app's files and Keystore keys. */
        suspend fun removeOldData(): LegacyCleanup.Removal = LegacyCleanup.Removal.Partial

        /** Changes after every import run and removal (including automatic ones), so the rows are checked again. */
        fun changes(): Flow<Int> = flowOf(0)
    }
}
