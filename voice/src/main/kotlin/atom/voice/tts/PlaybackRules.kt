package atom.voice.tts

/** Who asked for speech; decides background behaviour and which page "owns" the audio. */
enum class ReadAloudSource {
    /** Chat answers: keeps playing in the background / on the lock screen with a media notification. */
    Chat,
    /** Assistant overlay: ends when the overlay hides. */
    Overlay,
    /** Hands-free audio chat: ends when the screen leaves. */
    AudioChat,
    /** Settings "Preview voice". */
    Preview,
}

/** Things that can happen to a playing answer. */
sealed interface PlaybackEvent {
    data object RecordingStarted : PlaybackEvent
    data class FocusLost(val transient: Boolean, val canDuck: Boolean) : PlaybackEvent
    data object FocusGained : PlaybackEvent
    /** Headphones unplugged / Bluetooth audio disconnected (ACTION_AUDIO_BECOMING_NOISY). */
    data object BecomingNoisy : PlaybackEvent
    data class OwnerHidden(val source: ReadAloudSource) : PlaybackEvent
    data object StopPressed : PlaybackEvent
}

enum class PlaybackAction { Continue, Duck, Unduck, Stop }

/**
 * The interruption rules, pure so they are unit tested:
 * - Starting a recording always stops playback (never talk over the user).
 * - Losing audio focus stops (a call, another player, a navigation prompt that is not duckable); a duckable loss
 *   (a notification ding) only lowers the volume.
 * - Unplugging headphones stops: the answer must never jump to the loudspeaker.
 * - Closing or hiding the owner page stops its audio, except Chat answers, which are allowed to keep playing in
 *   the background with a notification.
 */
object PlaybackRules {
    fun decide(playing: ReadAloudSource, event: PlaybackEvent): PlaybackAction = when (event) {
        PlaybackEvent.RecordingStarted -> PlaybackAction.Stop
        is PlaybackEvent.FocusLost -> if (event.transient && event.canDuck) PlaybackAction.Duck else PlaybackAction.Stop
        PlaybackEvent.FocusGained -> PlaybackAction.Unduck
        PlaybackEvent.BecomingNoisy -> PlaybackAction.Stop
        is PlaybackEvent.OwnerHidden ->
            if (event.source == playing && playing != ReadAloudSource.Chat) PlaybackAction.Stop else PlaybackAction.Continue
        PlaybackEvent.StopPressed -> PlaybackAction.Stop
    }

    /** Requests older than this when their audio becomes ready are dropped: nothing is spoken late. */
    const val STALE_REQUEST_MS = 15_000L

    /** True when an answer whose request was made at [requestedAtMs] may still start at [nowMs]. */
    fun mayStart(requestedAtMs: Long, nowMs: Long, generationAtRequest: Long, currentGeneration: Long): Boolean =
        generationAtRequest == currentGeneration && nowMs - requestedAtMs <= STALE_REQUEST_MS

    /** Background playback (media FGS + notification) is only for chat answers. */
    fun needsForegroundService(source: ReadAloudSource): Boolean = source == ReadAloudSource.Chat
}
