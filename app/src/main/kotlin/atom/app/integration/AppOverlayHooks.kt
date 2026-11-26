package atom.app.integration

import android.content.Context
import android.content.Intent
import atom.app.AppGraph
import atom.app.R
import atom.app.data.EngineSelection
import atom.app.data.EntryKind
import atom.app.data.ModelRole
import atom.app.ui.MainActivity
import atom.app.ui.Routes
import atom.app.ui.chat.NotReady
import atom.app.ui.chat.Readiness
import atom.app.ui.chat.label
import atom.app.ui.chat.notReadyReason
import atom.core.llm.Role
import atom.overlay.ModelChipState
import atom.overlay.OverlayHooks
import atom.overlay.OverlayMessage
import atom.ui.feature.HostRun
import atom.ui.feature.RunSource
import atom.voice.ui.VoiceRoutes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/** The model role that answers runs from [source]. Chat runs follow the conversation / global model. */
internal fun roleFor(source: RunSource): ModelRole? = when (source) {
    RunSource.Chat -> null
    RunSource.Bubble, RunSource.Assistant, RunSource.Voice -> ModelRole.Assistant
    RunSource.Automation, RunSource.Schedule -> ModelRole.Scheduled
}

/** What the overlays need from the app: deep links into the chat, the model chip, the phone-control lease. */
class AppOverlayHooks(private val appContext: Context, private val graph: AppGraph) : OverlayHooks {
    /** Label per role (null key = global default), kept current by collecting settings and roles; no polling. */
    private val labels: StateFlow<Map<ModelRole?, String>> =
        combine(graph.settings.settings, graph.modelRoles.roles) { settings, roles ->
            val global = settings.selection
            buildMap<ModelRole?, String> {
                global?.let { put(null, label(it)) }
                for ((role, setting) in roles) (setting.selection ?: global)?.let { put(role, label(it)) }
            }
        }.stateIn(graph.appScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Chip state per role (null key = global default), with the home chat's readiness: a model without a key or
     * sign-in reads "Not selected" / "Sign in to …" and blocks sending. Follows the role's choice when it still
     * exists, else the primary model, as the run would.
     */
    private val chips: StateFlow<Map<ModelRole?, ModelChipState>> =
        combine(graph.settings.settings, graph.modelRoles.roles, Readiness.of(graph)) { settings, roles, ready ->
            val global = settings.selection
            buildMap<ModelRole?, ModelChipState> {
                put(null, chipOf(global, ready))
                for ((role, setting) in roles) {
                    val chosen = setting.selection?.takeIf { runCatching { graph.modelInfo.exists(it) }.getOrDefault(false) }
                    put(role, chipOf(chosen ?: global, ready))
                }
            }
        }.stateIn(graph.appScope, SharingStarted.Eagerly, emptyMap())

    private val chipBySource = ConcurrentHashMap<RunSource, StateFlow<ModelChipState>>()

    private fun chipOf(selection: EngineSelection?, ready: Readiness): ModelChipState {
        val reason = notReadyReason(selection, ready, graph.modelInfo)
        if (reason == null && selection != null) return ModelChipState(label(selection), ready = true)
        val provider = (selection as? EngineSelection.Cloud)?.let { runCatching { graph.modelInfo.provider(it) }.getOrNull() }?.displayName
        // The same label the home capsule, the drawer and Settings show.
        val chipLabel = atom.app.ui.chat.modelStatus(selection, ready, graph.modelInfo) { sel -> this.label(sel) }.label(appContext)
        val blocked = when {
            reason == NotReady.SignIn && provider != null -> appContext.getString(R.string.home_sign_in_needed, provider)
            reason == NotReady.KeyUnreadable && provider != null -> appContext.getString(R.string.mdl_route_key_unreadable, provider)
            reason == NotReady.NeedsPack -> appContext.getString(R.string.route_needs_pack)
            else -> appContext.getString(R.string.chat_configure_model)
        }
        return ModelChipState(chipLabel, ready = false, blocked = blocked)
    }

    override fun modelChip(source: RunSource): StateFlow<ModelChipState> = chipBySource.getOrPut(source) {
        val role = roleFor(source)
        fun pick(map: Map<ModelRole?, ModelChipState>) = map[role] ?: map[null]
        // Until settings and readiness have loaded the chip shows the plain label and lets sends through, as home does.
        val loading = ModelChipState(modelLabel(source) ?: modelLabel(RunSource.Chat).orEmpty(), ready = true)
        chips.map { pick(it) ?: loading }.stateIn(graph.appScope, SharingStarted.Eagerly, pick(chips.value) ?: loading)
    }

    private fun label(selection: EngineSelection): String = runCatching { graph.modelInfo.modelName(selection) }.getOrDefault(
        when (selection) {
            is EngineSelection.Cloud -> selection.model
            is EngineSelection.Local -> selection.installedModelId
        },
    )

    override fun appIntent(context: Context, conversationId: String?): Intent =
        MainActivity.conversationIntent(context, conversationId)

    override fun modelLabel(source: RunSource): String? = labels.value[roleFor(source)]

    override fun modelPickerIntent(context: Context, source: RunSource): Intent = MainActivity.routeIntent(context, Routes.MODELS)

    override fun voiceSettingsIntent(context: Context): Intent = MainActivity.routeIntent(context, VoiceRoutes.SETTINGS)

    /**
     * True only while [run] actually holds the phone-control lease (and the user has not taken over), or works on
     * the hidden background display: that never takes phone control, but the run capsule (with its viewfinder of
     * the hidden screen) should still show.
     */
    override fun drivesScreen(run: HostRun): Boolean {
        val runId = graph.tasks.liveRuns.value[run.conversationId]?.runId ?: return false
        if (!run.phase.active) return false
        return graph.tasks.lease.holds(runId) || atom.vdisplay.route.DisplayRouter.hiddenHeldBy(runId, run.conversationId)
    }

    override suspend fun transcript(conversationId: String): List<OverlayMessage>? {
        val conversation = graph.conversations.load(conversationId) ?: return null
        return conversation.messages
            .filter { !it.hidden && it.kind == EntryKind.Message }
            .mapNotNull { m ->
                when {
                    m.message.role == Role.User -> OverlayMessage(fromUser = true, text = m.message.text)
                    m.message.role == Role.Assistant && m.message.text.isNotBlank() -> OverlayMessage(fromUser = false, text = m.message.text)
                    else -> null
                }
            }
            .filter { it.text.isNotBlank() }
            .takeLast(MAX_TRANSCRIPT)
    }

    override val assistantModelRoute: String get() = Routes.MODELS

    private companion object {
        const val MAX_TRANSCRIPT = 30
    }
}
