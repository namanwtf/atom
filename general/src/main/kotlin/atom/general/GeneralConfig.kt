package atom.general

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
}
