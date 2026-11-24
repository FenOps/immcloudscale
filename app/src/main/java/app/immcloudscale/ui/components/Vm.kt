package app.immcloudscale.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.immcloudscale.AppGraph
import app.immcloudscale.graph

/** `viewModel()` with a constructor that gets the [AppGraph]. */
@Composable
inline fun <reified VM : ViewModel> graphViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.graph
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}
