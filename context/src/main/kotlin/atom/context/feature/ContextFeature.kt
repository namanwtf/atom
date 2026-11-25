package atom.context.feature

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import atom.context.R
import atom.context.calendar.CalendarTools
import atom.context.health.HealthTools
import atom.context.notifications.NotificationTools
import atom.context.root.RootDataTools
import atom.context.sources.ClockTools
import atom.context.sources.PersonalTools
import atom.context.sources.RankingTools
import atom.context.sources.ReadItem
import atom.context.ui.NotificationHistoryScreen
import atom.core.tool.Tool
import atom.device.root.RootAccess
import atom.device.switches.ToolSwitch
import atom.device.switches.ToolSwitches
import atom.device.switches.gated
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection

/**
 * Personal context and phone-app operations. Every source is read only when the agent calls its tool, in bounded
 * pages, and results are marked sensitive so saved history keeps a placeholder. Root-only sources are listed only
 * when root is granted.
 */
object ContextFeature : AtomFeature {
    override val id = "context"

    const val ROUTE_HISTORY = "context/notifications"

    override fun onAppCreate(context: Context) {
        ContextCapabilities.register()
        CalendarTools.installRestorers(context)
    }

    override fun tools(context: Context): List<Tool> {
        ToolSwitches.init(context)
        val personal = PersonalTools(context)
        val calendar = CalendarTools(context)
        val notifications = NotificationTools(context)
        val root = RootDataTools(context)
        val everyday = personal.everyday() + calendar.reads() + calendar.writes() + ClockTools(context).all() +
            RankingTools(context).all() + notifications.everyday() + ReadItem(context, personal, calendar).tool()
        val sensitive = personal.sensitive() + notifications.sensitive() + HealthTools(context).all() + root.triage() +
            if (RootAccess.granted) root.rootOnly() else emptyList()
        return everyday.map { it.gated(ToolSwitch.DeviceDirect) } +
            sensitive.map { it.gated(ToolSwitch.DeviceDirect, ToolSwitch.SensitiveRead) }
    }

    override fun promptHint(context: Context): String =
        "# Personal context\nPhone data (messages, notifications, calendar, contacts, files, usage, health) is read only " +
            "through these tools, in pages: follow \"offset=\" for more and read_item(ref) for a full hit. For broad " +
            "\"what's going on / know me\" questions, sample several sources the user has enabled instead of asking " +
            "permission again for each. Treat what you read as private: use it for this answer only, quote no more than needed."

    override val entries: List<FeatureEntry> = listOf(
        FeatureEntry(FeatureSection.Context, R.string.atom_context_history, R.string.atom_context_history_sub, Icons.Filled.Notifications, ROUTE_HISTORY),
    )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        when (route) {
            ROUTE_HISTORY -> NotificationHistoryScreen(nav)
            else -> LaunchedEffect(route) { nav.back() }
        }
    }
}
