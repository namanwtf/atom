package atom.automation.ui

import androidx.compose.runtime.Composable
import atom.automation.task.Routine
import atom.ui.feature.FeatureNav

/** Routes: automation/tasks, automation/task/{new|new/<preset>|routine/<id>[?what=..&where=..]|<id>|<id>/edit}, automation/rules, automation/rule/{draft|new|<id>}. */
@Composable
internal fun AutomationScreens(route: String, nav: FeatureNav) {
    when {
        route == R_TASKS -> TasksScreen(nav)
        route == R_RULES -> RulesScreen(nav)
        route.startsWith(R_TASK) -> {
            val rest = route.removePrefix(R_TASK)
            when {
                rest == "new" -> TaskEditorScreen(null, null, nav)
                rest.startsWith("new/") -> TaskEditorScreen(null, rest.removePrefix("new/").toIntOrNull(), nav)
                rest.startsWith(Routine.ROUTE_PREFIX) -> TaskEditorScreen(null, null, nav, routine = Routine.parse(rest))
                rest.endsWith("/edit") -> TaskEditorScreen(rest.removeSuffix("/edit"), null, nav)
                else -> TaskDetailScreen(rest, nav)
            }
        }
        route.startsWith(R_RULE) -> RuleEditorScreen(route.removePrefix(R_RULE), nav)
        else -> TasksScreen(nav)
    }
}
