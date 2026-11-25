package atom.app.ui.terminal

import androidx.compose.runtime.Composable
import atom.linux.ui.LinuxFeature
import atom.linux.ui.LinuxNavHost

/** The terminal (Console and Simple modes, up to six sessions each); lives in the :linux module. */
@Composable
fun TerminalScreen(onBack: () -> Unit) = LinuxNavHost(start = LinuxFeature.ROUTE_TERMINAL, onExit = onBack)

/** Linux environment settings and its sub-pages; lives in the :linux module. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun LinuxScreen(onBack: () -> Unit, onOpenTerminal: () -> Unit) = LinuxNavHost(start = LinuxFeature.ROUTE_HOME, onExit = onBack)
