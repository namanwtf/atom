package atom.app.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import atom.device.Accessibility
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import atom.app.data.AppSettings
import atom.app.feature.Features
import atom.app.graph
import atom.app.ui.chat.ChatScreen
import atom.app.ui.chat.ChatViewModel
import atom.app.ui.common.graphViewModel
import atom.app.ui.models.ModelsScreen
import atom.app.ui.models.ModelsStart
import atom.app.ui.models.ModelsViewModel
import atom.app.ui.settings.LicensesScreen
import atom.app.ui.settings.SettingsNav
import atom.app.ui.settings.SettingsScreen
import atom.app.ui.settings.SettingsViewModel
import atom.app.ui.setup.SetupScreen
import atom.app.integration.NavRequests
import atom.characters.ui.CharacterRoutes
import atom.device.feature.DeviceFeature
import atom.linux.ui.LinuxFeature
import atom.ui.feature.FeatureNav
import atom.ui.theme.LocalAppearance
import atom.ui.theme.swipeBackAnywhere
import atom.web.WebFeature
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Built-in screens. Feature modules add their own routes ("<featureId>/<screen>"); Terminal, Linux and
 * Workspace are LinuxFeature routes ("linux/...").
 */
object Routes {
    const val CHAT = "chat"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val LICENSES = "licenses"

    /** Model providers opened straight on the on-device models page. */
    const val ON_DEVICE = "models-on-device"

    /** The Auto-generate titles page (part of the Models flow). */
    const val TITLES = "settings-titles"
}

/** A tiny back stack of route strings; no navigation library needed. */
@Composable
fun AtomRoot(settings: AppSettings) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    // Every launch opens on the home chat. Setup stays reachable from the drawer's Permissions button and the
    // "Turn on Accessibility" nudge; the first launch is recorded so nothing that keys off it is lost.
    var stack by rememberSaveable { mutableStateOf(listOf(Routes.CHAT)) }
    // The four items the old sidebar badge watched: background running, floating window, accessibility, notifications.
    var permissionsNeedAttention by remember { mutableStateOf(false) }
    val notificationPrompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionsNeedAttention = PermissionAttention.needed(context)
    }
    LaunchedEffect(settings.onboarded) {
        if (settings.onboarded) return@LaunchedEffect
        // First launch only: ask once for notifications (running-task controls and results), then never again here.
        val permission = "android.permission.POST_NOTIFICATIONS"
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            runCatching { notificationPrompt.launch(permission) }
        }
        graph.settings.setOnboarded()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionsNeedAttention = PermissionAttention.needed(context)
    }
    val accessibilityOn by Accessibility.connected.collectAsStateWithLifecycle()
    LaunchedEffect(accessibilityOn) { permissionsNeedAttention = PermissionAttention.needed(context) }
    val current = stack.last()
    fun push(route: String) {
        if (stack.last() != route) stack = stack + route
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    val canPop = stack.size > 1
    val appearance = LocalAppearance.current
    // 0..1 while a predictive back gesture is in progress; the top screen follows it, then pops.
    var backProgress by remember { mutableFloatStateOf(0f) }
    if (appearance.predictiveBack) {
        PredictiveBackHandler(enabled = canPop) { events ->
            try {
                events.collect { backProgress = it.progress }
                pop()
                backProgress = 0f
            } catch (e: CancellationException) {
                backProgress = 0f
                throw e
            }
        }
    } else {
        BackHandler(enabled = canPop) { pop() }
    }

    // Deep links (overlay settings links, after an import) and chat requests (notifications, shares, the
    // overlay's "open in app") bring the right screen forward.
    LaunchedEffect(Unit) {
        NavRequests.pending.collect { request ->
            when (request) {
                null -> Unit
                NavRequests.Request.Reset -> stack = listOf(Routes.CHAT)
                is NavRequests.Request.Open -> if (request.route == Routes.CHAT) stack = listOf(Routes.CHAT) else push(request.route)
            }
            if (request != null) NavRequests.consume(request)
        }
    }
    LaunchedEffect(Unit) {
        graph.chatRequests.collect { requests -> if (requests.isNotEmpty()) stack = listOf(Routes.CHAT) }
    }

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

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .swipeBackAnywhere(enabled = canPop, rtl = rtl, onBack = ::pop),
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "screen",
        ) { route ->
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = if (route == current) backProgress else 0f
                    if (p > 0f) {
                        translationX = (if (rtl) -1f else 1f) * size.width * 0.08f * p
                        scaleX = 1f - 0.06f * p
                        scaleY = 1f - 0.06f * p
                    }
                },
            ) {
            when (route) {
                Routes.CHAT -> ChatScreen(
                    vm = chatVm,
                    onOpenModels = { push(Routes.MODELS) },
                    onOpenSettings = { push(Routes.SETTINGS) },
                    onOpenSetup = { push(Routes.SETUP) },
                    onOpenTerminal = { push(LinuxFeature.ROUTE_TERMINAL) },
                    onOpenBrowser = { push(WebFeature.ROUTE_BROWSER) },
                    onOpenCharacters = { push(CharacterRoutes.LIBRARY) },
                    onOpenPermissions = { push(DeviceFeature.ROUTE_PERMISSIONS) },
                    permissionsNeedAttention = permissionsNeedAttention,
                    onOpenRoute = ::push,
                )
                Routes.MODELS, Routes.ON_DEVICE, Routes.TITLES -> ModelsScreen(
                    vm = graphViewModel { ModelsViewModel(context.applicationContext, it) },
                    onBack = ::pop,
                    start = when (route) {
                        Routes.ON_DEVICE -> ModelsStart.OnDevice
                        Routes.TITLES -> ModelsStart.Titles
                        else -> ModelsStart.Providers
                    },
                )
                Routes.SETTINGS -> SettingsScreen(
                    vm = graphViewModel { SettingsViewModel(context.applicationContext, it) },
                    nav = remember {
                        SettingsNav(
                            back = ::pop,
                            openModels = { push(Routes.MODELS) },
                            openOnDevice = { push(Routes.ON_DEVICE) },
                            openTitles = { push(Routes.TITLES) },
                            openSetup = { push(Routes.SETUP) },
                            openLicenses = { push(Routes.LICENSES) },
                            openRoute = ::push,
                        )
                    },
                    features = featureEntries,
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
                else -> Features.forRoute(route)?.Screen(route, nav) ?: pop()
            }
            }
        }
    }
}
