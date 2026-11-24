package atom.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import atom.app.data.AppSettings
import atom.app.graph
import atom.app.ui.chat.ChatScreen
import atom.app.ui.chat.ChatViewModel
import atom.app.ui.common.graphViewModel
import atom.app.ui.models.ModelsScreen
import atom.app.ui.models.ModelsViewModel
import atom.app.ui.settings.LicensesScreen
import atom.app.ui.settings.SettingsScreen
import atom.app.ui.settings.SettingsViewModel
import atom.app.ui.setup.SetupScreen
import atom.app.ui.terminal.LinuxScreen
import atom.app.ui.terminal.TerminalScreen
import kotlinx.coroutines.launch

enum class Destination { Chat, Models, Settings, Setup, Licenses, Terminal, Linux }

/** A tiny back stack: five screens do not need a navigation library. */
@Composable
fun AtomRoot(settings: AppSettings) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var stack by rememberSaveable(stateSaver = stackSaver) {
        mutableStateOf(if (settings.onboarded) listOf(Destination.Chat) else listOf(Destination.Chat, Destination.Setup))
    }
    val current = stack.last()
    fun push(d: Destination) {
        if (stack.last() != d) stack = stack + d
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1 && !(current == Destination.Setup && !settings.onboarded)) { pop() }

    val chatVm = graphViewModel { ChatViewModel(it) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { screen ->
            when (screen) {
                Destination.Chat -> ChatScreen(
                    vm = chatVm,
                    onOpenModels = { push(Destination.Models) },
                    onOpenSettings = { push(Destination.Settings) },
                    onOpenSetup = { push(Destination.Setup) },
                )
                Destination.Models -> ModelsScreen(
                    vm = graphViewModel { ModelsViewModel(context.applicationContext, it) },
                    onBack = ::pop,
                )
                Destination.Settings -> SettingsScreen(
                    vm = graphViewModel { SettingsViewModel(it) },
                    onBack = ::pop,
                    onOpenModels = { push(Destination.Models) },
                    onOpenSetup = { push(Destination.Setup) },
                    onOpenLicenses = { push(Destination.Licenses) },
                    onOpenTerminal = { push(Destination.Terminal) },
                    onOpenLinux = { push(Destination.Linux) },
                )
                Destination.Setup -> SetupScreen(
                    firstRun = !settings.onboarded,
                    engineChosen = settings.selection != null,
                    onBack = ::pop,
                    onOpenModels = { push(Destination.Models) },
                    onDone = {
                        if (!settings.onboarded) scope.launch { graph.settings.setOnboarded() }
                        pop()
                    },
                )
                Destination.Licenses -> LicensesScreen(onBack = ::pop)
                Destination.Terminal -> TerminalScreen(onBack = ::pop)
                Destination.Linux -> LinuxScreen(onBack = ::pop, onOpenTerminal = { push(Destination.Terminal) })
            }
        }
    }
}

private val stackSaver = listSaver<List<Destination>, String>(
    save = { list -> list.map { it.name } },
    restore = { names -> names.mapNotNull { n -> Destination.entries.firstOrNull { it.name == n } } },
)
