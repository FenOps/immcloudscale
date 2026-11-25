package app.immcloudscale.ui.photos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.data.immich.groupByDay
import app.immcloudscale.graph
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.ErrorState
import app.immcloudscale.ui.components.Pullable
import kotlinx.coroutines.launch

/**
 * A photo grid screen with everything around it: pull to refresh, selection mode and its actions,
 * "add to album" and confirmation dialogs, and snackbars. Used by the timeline and every collection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetBrowser(
    vm: AssetListViewModel,
    onOpenViewer: () -> Unit,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    emptyTitle: String = "No photos yet",
    emptyBody: String = "Photos and videos you back up will show up here",
    header: (LazyGridScope.() -> Unit)? = null,
    bottomPadding: PaddingValues = PaddingValues(0.dp),
) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val st by vm.state.collectAsStateWithLifecycle()
    val settings by g.settingsState.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var albumPicker by remember { mutableStateOf(false) }
    LifecycleResumeEffect(vm) {
        vm.refreshIfStale()
        onPauseOrDispose {}
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val sections = remember(st.assets) { groupByDay(st.assets) }

    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    BackHandler(st.selecting) { vm.clearSelection() }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (st.selecting) {
                SelectionTopBar(
                    count = st.selected.size, query = vm.query,
                    onClose = vm::clearSelection,
                    onShare = {
                        val chosen = st.assets.filter { it.id in st.selected }
                        scope.launch { runCatching { shareAssets(ctx, chosen) }.onFailure { snack.showSnackbar(it.message ?: "Couldn't share") } }
                        vm.clearSelection()
                    },
                    onLink = { vm.shareLinkForSelected { shareText(ctx, it) } },
                    onAddToAlbum = { albumPicker = true },
                    onFavorite = { vm.favoriteSelected(!st.assets.filter { it.id in st.selected }.all { it.isFavorite }) },
                    onArchive = { vm.archiveSelected(vm.query != AssetQuery.Archive) },
                    onDelete = { if (vm.query == AssetQuery.Trash) confirmDelete = true else vm.trashSelected() },
                    onRestore = vm::restoreSelected,
                )
            } else topBar()
        },
        snackbarHost = { SnackbarHost(snack, Modifier.padding(bottomPadding)) },
    ) { pad ->
        PullToRefreshBox(st.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            when {
                st.assets.isEmpty() && st.loading -> CenteredLoading()
                st.assets.isEmpty() && st.error != null -> Pullable { ErrorState(st.error!!, vm::refresh) }
                st.assets.isEmpty() && header == null ->
                    Pullable { EmptyState(Icons.Outlined.PhotoLibrary, emptyTitle, emptyBody) }
                else -> AssetGrid(
                    sections = sections,
                    columns = settings.photoColumns,
                    selected = st.selected,
                    thumbUrl = { g.immich.thumbnailUrl(it.id) },
                    onOpen = { a: AssetDto ->
                        ViewerSession.open(st.assets, a, vm.query) { upd, removed -> vm.applyEdit(upd, removed) }
                        onOpenViewer()
                    },
                    onToggle = { vm.toggle(it.id) },
                    onSelectDay = { day ->
                        val ids = day.assets.map { it.id }
                        if (ids.all { it in st.selected }) ids.forEach(vm::toggle) else vm.selectAll(ids)
                    },
                    onColumnsChange = { c -> scope.launch { g.settings.update { it.copy(photoColumns = c) } } },
                    loadingMore = st.loading && st.assets.isNotEmpty(),
                    onLoadMore = { vm.loadMore() },
                    contentPadding = bottomPadding,
                    header = header,
                )
            }
        }
    }

    if (albumPicker) AlbumPickerDialog(
        onDismiss = { albumPicker = false },
        onPick = { albumPicker = false; vm.addSelectedToAlbum(it.id) },
        onCreate = { albumPicker = false; vm.newAlbumFromSelected(it) },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete forever?") },
        text = { Text("${st.selected.size} items will be permanently deleted from your server.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteForeverSelected() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
