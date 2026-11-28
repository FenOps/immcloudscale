package app.immcloudscale.ui.photos

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.AssetQuery
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
    onOpenLocal: (() -> Unit)? = null, // opens LocalViewerScreen; else the phone's gallery shows photos not backed up
) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val st by vm.state.collectAsStateWithLifecycle()
    val settings by g.settingsState.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var explainedLocal by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var albumPicker by remember { mutableStateOf(false) }
    LifecycleResumeEffect(vm) {
        vm.refreshIfStale()
        onPauseOrDispose {}
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var collage by remember { mutableStateOf(false) }
    var confirmLock by remember { mutableStateOf(false) }
    val phoneTrash = rememberPhoneTrash()
    // Grouping a large library into days takes a moment: done off the main thread.
    val sections by vm.sections.collectAsStateWithLifecycle()

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
                    onDelete = { if (vm.query == AssetQuery.Trash) confirmDelete = true else confirmTrash = true },
                    onRestore = vm::restoreSelected,
                    onCollage = { collage = true },
                    onLock = { confirmLock = true },
                )
            } else topBar()
        },
        snackbarHost = { SnackbarHost(snack, Modifier.padding(bottomPadding)) },
    ) { pad ->
        PullToRefreshBox(st.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            when {
                st.assets.isEmpty() && st.loading -> CenteredLoading()
                st.assets.isNotEmpty() && sections.isEmpty() -> CenteredLoading() // days not grouped yet (a moment)
                st.assets.isEmpty() && st.error != null -> Pullable { ErrorState(st.error!!, vm::refresh) }
                st.assets.isEmpty() && header == null ->
                    Pullable { EmptyState(Icons.Outlined.PhotoLibrary, emptyTitle, emptyBody) }
                else -> AssetGrid(
                    sections = sections,
                    columns = settings.photoColumns,
                    selected = st.selected,
                    thumbUrl = { it.localUri ?: g.immich.thumbnailUrl(it.id) },
                    onOpen = { a: AssetDto ->
                        if (a.isLocal && onOpenLocal != null) {
                            val local = st.assets.filter { it.isLocal }
                            LocalViewerSession.items = local
                            LocalViewerSession.start = local.indexOfFirst { it.id == a.id }.coerceAtLeast(0)
                            onOpenLocal()
                        } else if (a.isLocal) {
                            // Only on this phone so far: the phone's own viewer shows it.
                            if (!explainedLocal) {
                                explainedLocal = true
                                scope.launch { snack.showSnackbar("Not backed up yet, so it opens in your phone's gallery") }
                            }
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(a.localUri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }.onFailure { scope.launch { snack.showSnackbar("Couldn't open this photo") } }
                        } else {
                            ViewerSession.open(st.assets.filter { !it.isLocal }, a, vm.query) { upd, removed -> vm.applyEdit(upd, removed) }
                            onOpenViewer()
                        }
                    },
                    onToggle = { a ->
                        if (a.isLocal) scope.launch { snack.showSnackbar("This photo is only on your phone. You can select it once it's backed up.") }
                        else vm.toggle(a.id)
                    },
                    onSelectDay = { day ->
                        val ids = day.assets.filter { !it.isLocal }.map { it.id }
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
    if (collage) AlertDialog(
        onDismissRequest = { collage = false },
        title = { Text("Make a collage?") },
        text = { Text("The ${st.selected.size} photos are put together into one new square picture in your library. The originals stay as they are.") },
        confirmButton = {
            TextButton(onClick = {
                collage = false
                val chosen = st.assets.filter { it.id in st.selected }
                vm.clearSelection()
                scope.launch {
                    snack.showSnackbar(runCatching { makeCollage(g, chosen) }.fold({ vm.refresh(); "Collage added to your photos" }, { it.message ?: "Couldn't make the collage" }))
                }
            }) { Text("Make collage") }
        },
        dismissButton = { TextButton(onClick = { collage = false }) { Text("Cancel") } },
    )
    if (confirmLock) AlertDialog(
        onDismissRequest = { confirmLock = false },
        title = { Text("Move ${st.selected.size} to Locked folder?") },
        text = { Text("They're hidden from your timeline, albums, search and other apps, and open only with your PIN (Collections → Locked folder). This phone's copies move to the phone's trash.") },
        confirmButton = {
            TextButton(onClick = {
                confirmLock = false
                val chosen = st.assets.filter { it.id in st.selected }
                vm.lockSelected { phoneTrash(chosen) }
            }) { Text("Move to Locked folder") }
        },
        dismissButton = { TextButton(onClick = { confirmLock = false }) { Text("Cancel") } },
    )
    if (confirmTrash) TrashConfirmDialog(st.selected.size, onDismiss = { confirmTrash = false }, onConfirm = {
        confirmTrash = false
        val chosen = st.assets.filter { it.id in st.selected }
        vm.trashSelected { phoneTrash(chosen) }
    })
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete forever?") },
        text = { Text("${st.selected.size} items will be permanently deleted from your server.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteForeverSelected() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
