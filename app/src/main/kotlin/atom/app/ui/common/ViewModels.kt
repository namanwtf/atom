package atom.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import atom.app.AppGraph
import atom.app.graph

/** ViewModel built from the app graph; no DI framework needed. */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.graph
    return viewModel { create(graph) }
}
