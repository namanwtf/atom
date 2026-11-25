package atom.app.integration

import android.content.Context
import android.content.Intent
import atom.app.AppGraph
import atom.app.data.EngineSelection
import atom.app.data.EntryKind
import atom.app.data.ModelRole
import atom.app.ui.MainActivity
import atom.app.ui.Routes
import atom.core.llm.Role
import atom.overlay.OverlayHooks
import atom.overlay.OverlayMessage
import atom.ui.feature.HostRun
import atom.ui.feature.RunSource
import atom.voice.ui.VoiceRoutes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The model role that answers runs from [source]. Chat runs follow the conversation / global model. */
internal fun roleFor(source: RunSource): ModelRole? = when (source) {
    RunSource.Chat -> null
    RunSource.Bubble, RunSource.Assistant, RunSource.Voice -> ModelRole.Assistant
    RunSource.Automation, RunSource.Schedule -> ModelRole.Scheduled
}

/** What the overlays need from the app: deep links into the chat, the model chip, the phone-control lease. */
class AppOverlayHooks(private val graph: AppGraph) : OverlayHooks {
    /** Label per role (null key = global default), kept current by collecting settings and roles; no polling. */
    private val labels: StateFlow<Map<ModelRole?, String>> =
        combine(graph.settings.settings, graph.modelRoles.roles) { settings, roles ->
            val global = settings.selection
            buildMap<ModelRole?, String> {
                global?.let { put(null, label(it)) }
                for ((role, setting) in roles) (setting.selection ?: global)?.let { put(role, label(it)) }
            }
        }.stateIn(graph.appScope, SharingStarted.Eagerly, emptyMap())

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

    /** True only while [run] actually holds the phone-control lease (and the user has not taken over). */
    override fun drivesScreen(run: HostRun): Boolean {
        val runId = graph.tasks.liveRuns.value[run.conversationId]?.runId ?: return false
        return run.phase.active && graph.tasks.lease.holds(runId)
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
