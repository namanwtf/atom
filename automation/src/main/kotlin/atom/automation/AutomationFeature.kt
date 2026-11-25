package atom.automation

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.runtime.Composable
import atom.automation.system.AutomationRuntime
import atom.automation.ui.AutomationScreens
import atom.core.tool.Tool
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Scheduled tasks and natural-language automations: one event-driven engine for both. */
class AutomationFeature : AtomFeature {
    override val id = ID

    /** Loads state off the main thread and re-arms the one alarm and the listeners already in use. */
    override fun onAppCreate(context: Context) {
        Automations.attach(context)
        CoroutineScope(Dispatchers.IO).launch { AutomationRuntime.get(context).start() }
    }

    override fun tools(context: Context): List<Tool> {
        Automations.attachIfNeeded(context)
        return AutomationTools.all(AutomationRuntime.get(context))
    }

    override fun promptHint(context: Context): String =
        "For repeating work use scheduled_task_create; for \"when X happens, do Y\" use automation_create."

    override val entries: List<FeatureEntry> = listOf(
        FeatureEntry(FeatureSection.Context, R.string.automation_tasks_title, R.string.automation_tasks_subtitle, Icons.Filled.DateRange, ROUTE_TASKS),
        FeatureEntry(FeatureSection.Context, R.string.automation_rules_title, R.string.automation_rules_subtitle, Icons.Filled.Build, ROUTE_RULES),
    )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) = AutomationScreens(route, nav)

    companion object {
        const val ID = "automation"
        const val ROUTE_TASKS = "$ID/tasks"
        const val ROUTE_RULES = "$ID/rules"
    }
}
