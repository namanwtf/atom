package atom.linux.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import atom.core.tool.Tool
import atom.linux.LinuxTools
import atom.linux.R
import atom.ui.components.ScreenIcons
import atom.ui.feature.AtomFeature
import atom.ui.feature.FeatureEntry
import atom.ui.feature.FeatureNav
import atom.ui.feature.FeatureSection
import atom.ui.theme.AtomIcons

/** Terminal, Linux environment and background tasks as one feature: tools, settings rows and screens. */
object LinuxFeature : AtomFeature {
    override val id = "linux"

    const val ROUTE_HOME = "linux/home"
    const val ROUTE_TERMINAL = "linux/terminal"
    const val ROUTE_HEALTH = "linux/health"
    const val ROUTE_FOLDERS = "linux/folders"
    const val ROUTE_FILES = "linux/files"
    const val ROUTE_WORKSPACE = "linux/workspace"
    const val ROUTE_JOBS = "linux/jobs"
    private const val ROUTE_JOB_LOG = "linux/jobs/"

    override fun tools(context: Context): List<Tool> = LinuxTools.create(context)

    override fun promptHint(context: Context): String =
        "You have a terminal: terminal_run for one command (Linux distro, Android shell or root), terminal_session for " +
            "stateful/interactive shells, terminal_job_start for long work and daemons. Write code to /workspace, run it, " +
            "read the errors and fix them."

    override val entries: List<FeatureEntry> = listOf(
        FeatureEntry(FeatureSection.System, R.string.linux_entry_title, R.string.linux_entry_subtitle, ScreenIcons.Storage, ROUTE_HOME),
        FeatureEntry(FeatureSection.System, R.string.terminal_entry_title, R.string.terminal_entry_subtitle, AtomIcons.Terminal, ROUTE_TERMINAL),
    )

    @Composable
    override fun Screen(route: String, nav: FeatureNav) {
        when {
            route == ROUTE_HOME -> LinuxHomeScreen(onBack = nav::back, open = nav::open)
            route == ROUTE_TERMINAL -> TerminalScreen(onBack = nav::back)
            route == ROUTE_HEALTH -> HealthScreen(onBack = nav::back)
            route == ROUTE_FOLDERS -> FoldersScreen(onBack = nav::back)
            route == ROUTE_FILES -> FilesScreen(onBack = nav::back)
            route == ROUTE_WORKSPACE -> WorkspaceScreen(onBack = nav::back)
            route == ROUTE_JOBS -> JobsScreen(onBack = nav::back, openLog = { nav.open(ROUTE_JOB_LOG + it) })
            route.startsWith(ROUTE_JOB_LOG) -> JobLogScreen(route.removePrefix(ROUTE_JOB_LOG), onBack = nav::back)
            else -> LinuxHomeScreen(onBack = nav::back, open = nav::open)
        }
    }
}

/**
 * Hosts the feature's screens with their own back stack, for callers that route to a single screen (the app's
 * existing "terminal" and "linux" routes). Other routes the screens open are pushed here.
 */
@Composable
fun LinuxNavHost(start: String, onExit: () -> Unit, openChat: (String?) -> Unit = {}, openModels: () -> Unit = {}) {
    val stack = remember { mutableStateListOf(start) }
    val nav = remember {
        object : FeatureNav {
            override fun back() {
                if (stack.size > 1) stack.removeAt(stack.size - 1) else onExit()
            }
            override fun open(route: String) {
                if (LinuxFeature.ownsRoute(route)) stack.add(route)
            }
            override fun openChat(conversationId: String?) = openChat(conversationId)
            override fun openModels() = openModels()
        }
    }
    BackHandler(enabled = stack.size > 1) { nav.back() }
    LinuxFeature.Screen(stack.last(), nav)
}
