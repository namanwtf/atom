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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import atom.app.data.AppSettings
import atom.app.feature.Features
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
import atom.ui.feature.FeatureNav
import kotlinx.coroutines.launch

/** Built-in screens. Feature modules add their own routes ("<featureId>/<screen>"). */
object Routes {
    const val CHAT = "chat"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val LICENSES = "licenses"
    const val TERMINAL = "terminal"
    const val LINUX = "linux"
}

/** A tiny back stack of route strings; no navigation library needed. */
@Composable
fun AtomRoot(settings: AppSettings) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    var stack by rememberSaveable {
        mutableStateOf(if (settings.onboarded) listOf(Routes.CHAT) else listOf(Routes.CHAT, Routes.SETUP))
    }
    val current = stack.last()
    fun push(route: String) {
        if (stack.last() != route) stack = stack + route
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1 && !(current == Routes.SETUP && !settings.onboarded)) { pop() }

    val chatVm = graphViewModel { ChatViewModel(it) }
    val nav = remember(chatVm) {
        object : FeatureNav {
            override fun back() = pop()
            override fun open(route: String) = push(route)
            override fun openChat(conversationId: String?) {
                if (conversationId != null) chatVm.open(conversationId)
                stack = listOf(Routes.CHAT)
            }
            override fun openModels() = push(Routes.MODELS)
        }
    }
    val featureEntries = remember { Features.all.flatMap { it.entries } }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { route ->
            when (route) {
                Routes.CHAT -> ChatScreen(
                    vm = chatVm,
                    onOpenModels = { push(Routes.MODELS) },
                    onOpenSettings = { push(Routes.SETTINGS) },
                    onOpenSetup = { push(Routes.SETUP) },
                )
                Routes.MODELS -> ModelsScreen(
                    vm = graphViewModel { ModelsViewModel(context.applicationContext, it) },
                    onBack = ::pop,
                )
                Routes.SETTINGS -> SettingsScreen(
                    vm = graphViewModel { SettingsViewModel(it) },
                    onBack = ::pop,
                    onOpenModels = { push(Routes.MODELS) },
                    onOpenSetup = { push(Routes.SETUP) },
                    onOpenLicenses = { push(Routes.LICENSES) },
                    onOpenTerminal = { push(Routes.TERMINAL) },
                    onOpenLinux = { push(Routes.LINUX) },
                    features = featureEntries,
                    onOpenRoute = ::push,
                )
                Routes.SETUP -> SetupScreen(
                    firstRun = !settings.onboarded,
                    engineChosen = settings.selection != null,
                    onBack = ::pop,
                    onOpenModels = { push(Routes.MODELS) },
                    onDone = {
                        if (!settings.onboarded) scope.launch { graph.settings.setOnboarded() }
                        pop()
                    },
                )
                Routes.LICENSES -> LicensesScreen(onBack = ::pop)
                Routes.TERMINAL -> TerminalScreen(onBack = ::pop)
                Routes.LINUX -> LinuxScreen(onBack = ::pop, onOpenTerminal = { push(Routes.TERMINAL) })
                else -> Features.forRoute(route)?.Screen(route, nav) ?: pop()
            }
        }
    }
}
