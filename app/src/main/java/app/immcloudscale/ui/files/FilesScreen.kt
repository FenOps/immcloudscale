package app.immcloudscale.ui.files

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.nextcloud.CloudFile
import app.immcloudscale.graph
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.ErrorState
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.Pullable
import app.immcloudscale.ui.components.RemoteImage
import app.immcloudscale.ui.components.formatBytes
import app.immcloudscale.ui.components.formatDate
import app.immcloudscale.ui.components.graphViewModel
import app.immcloudscale.ui.photos.SelectCircle
import app.immcloudscale.ui.photos.shareText
import kotlinx.coroutines.launch
import java.io.File

/** Callbacks a file list needs; grouped so the stateless content stays testable. */
class FilesActions(
    val onTab: (FilesTab) -> Unit = {},
    val onOpenFolder: (String) -> Unit = {},
    val onOpenFile: (CloudFile) -> Unit = {},
    val onMore: (CloudFile) -> Unit = {},
    val onSort: (SortBy) -> Unit = {},
    val onToggleGrid: () -> Unit = {},
    val onSearch: (String?) -> Unit = {},
    val onUp: () -> Unit = {},
    val onNew: () -> Unit = {},
    val onAccount: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelect: (CloudFile) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onMoveSelected: () -> Unit = {},
    val onCopySelected: () -> Unit = {},
    val onStarSelected: () -> Unit = {},
    val onOfflineSelected: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
)

fun openLocalFile(ctx: android.content.Context, file: File, mime: String?) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val type = mime ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(intent, "Open with")) }
}

@Composable
fun FilesScreen(onPreview: (CloudFile) -> Unit, onAccount: () -> Unit, onSetup: () -> Unit, bottomPadding: PaddingValues) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings by g.settingsState.collectAsStateWithLifecycle()
    if (!settings.nextcloudConfigured) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            EmptyState(
                Icons.Outlined.FolderOpen, "Connect your files",
                "Sign in to your Nextcloud server to browse, upload and share your files.",
                action = "Connect Nextcloud", onAction = onSetup,
            )
        }
        return
    }
    val vm = graphViewModel { FilesViewModel(it) }
    LifecycleResumeEffect(vm) {
        vm.loadIfStale()
        onPauseOrDispose {}
    }
    val st by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sheetFor by remember { mutableStateOf<CloudFile?>(null) }
    var newSheet by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Pair<String, CloudFile?>?>(null) } // "folder" | "rename"
    var movePicker by remember { mutableStateOf<Pair<CloudFile, Boolean>?>(null) }
    var details by remember { mutableStateOf<CloudFile?>(null) }
    var confirmRemove by remember { mutableStateOf<CloudFile?>(null) }
    var moveSelected by remember { mutableStateOf<Boolean?>(null) } // true: copy the picked items
    var confirmRemoveSelected by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) vm.upload(uris) }

    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    BackHandler(st.selected.isNotEmpty()) { vm.clearSelection() }
    BackHandler(st.selected.isEmpty() && (st.search != null || (st.tab == FilesTab.MyFiles && st.path != "/"))) { vm.up() }

    val actions = FilesActions(
        onTab = vm::selectTab, onOpenFolder = vm::open,
        onOpenFile = { f -> onPreview(f) },
        onMore = { sheetFor = it }, onSort = vm::sort,
        onToggleGrid = { scope.launch { g.settings.update { it.copy(filesGrid = !it.filesGrid) } } },
        onSearch = vm::search, onUp = { vm.up() }, onNew = { newSheet = true }, onAccount = onAccount, onRefresh = vm::load,
        onSelect = vm::toggleSelect, onSelectAll = vm::selectAll, onClearSelection = vm::clearSelection,
        onMoveSelected = { moveSelected = false }, onCopySelected = { moveSelected = true },
        onStarSelected = vm::starSelected, onOfflineSelected = vm::offlineSelected, onDeleteSelected = { confirmRemoveSelected = true },
    )
    FilesContent(
        st, settings.filesGrid, settings.immichUserName.ifBlank { settings.ncUser.ifBlank { "Me" } }, actions,
        previewUrl = { f -> g.nextcloud.previewUrl(f) },
        snack = snack, bottomPadding = bottomPadding,
    )

    sheetFor?.let { f ->
        FileActionsSheet(
            f, offline = f.path in st.offlinePaths,
            previewUrl = if (f.kind() == FileKind.Image || f.kind() == FileKind.Video) g.nextcloud.previewUrl(f) else null,
            onDismiss = { sheetFor = null },
            onShareLink = { vm.shareLink(f) { shareText(ctx, it) } },
            onStar = { vm.star(f) },
            onOffline = { vm.toggleOffline(f) },
            onOpenWith = { vm.download(f) { file -> openLocalFile(ctx, file, f.contentType) } },
            onSendCopy = {
                vm.download(f) { file ->
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(f.contentType ?: "*/*")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                }
            },
            onRename = { dialog = "rename" to f },
            onMove = { movePicker = f to false },
            onCopy = { movePicker = f to true },
            onDetails = { details = f },
            onDelete = { confirmRemove = f },
        )
    }
    if (newSheet) NewSheet(
        onDismiss = { newSheet = false },
        onFolder = { newSheet = false; dialog = "folder" to null },
        onUpload = { newSheet = false; picker.launch("*/*") },
    )
    dialog?.let { (kind, f) ->
        NameDialog(
            title = if (kind == "folder") "New folder" else "Rename",
            initial = f?.name ?: "Untitled folder",
            confirm = if (kind == "folder") "Create" else "OK",
            onDismiss = { dialog = null },
        ) { name -> dialog = null; if (kind == "folder") vm.newFolder(name) else vm.rename(f!!, name) }
    }
    movePicker?.let { (f, copy) ->
        FolderPickerDialog(
            title = if (copy) "Copy \"${f.name}\" to" else "Move \"${f.name}\" to",
            start = f.parent,
            exclude = if (f.isFolder) setOf(f.path) else emptySet(),
            onDismiss = { movePicker = null },
        ) { dest -> movePicker = null; if (copy) vm.copyTo(f, dest) else vm.moveTo(f, dest) }
    }
    moveSelected?.let { copy ->
        val n = st.selectedFiles.size
        FolderPickerDialog(
            title = "${if (copy) "Copy" else "Move"} $n item${if (n == 1) "" else "s"} to",
            start = st.selectedFiles.firstOrNull()?.parent ?: st.path,
            exclude = st.selectedFiles.filter { it.isFolder }.mapTo(HashSet()) { it.path }, // not into themselves
            onDismiss = { moveSelected = null },
        ) { dest -> moveSelected = null; vm.moveSelected(dest, copy) }
    }
    if (confirmRemoveSelected) {
        val n = st.selectedFiles.size
        AlertDialog(
            onDismissRequest = { confirmRemoveSelected = false },
            title = { Text(if (n == 1) "Remove 1 item?" else "Remove $n items?") },
            text = { Text("They move to the Nextcloud trash bin, where you can restore them from the web.") },
            confirmButton = { TextButton(onClick = { confirmRemoveSelected = false; vm.deleteSelected() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemoveSelected = false }) { Text("Cancel") } },
        )
    }
    confirmRemove?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove \"${f.name}\"?") },
            text = { Text("It moves to the Nextcloud trash bin, where you can restore it from the web.") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; vm.delete(f) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
    details?.let { f ->
        AlertDialog(
            onDismissRequest = { details = null },
            icon = { FileIcon(f, 32.dp) },
            title = { Text(f.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailRow("Type", if (f.isFolder) "Folder" else f.contentType ?: f.extension.uppercase())
                    DetailRow("Size", formatBytes(f.size))
                    DetailRow("Location", f.parent)
                    DetailRow("Modified", formatDate(f.modified))
                    DetailRow("Shared", if (f.shared) "Yes" else "No")
                    DetailRow("Offline", if (f.path in st.offlinePaths) "Available offline" else "Online only")
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Drive's main screen: search pill, chips, sort/view row, list or grid, and the "New" FAB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesContent(
    st: FilesState,
    grid: Boolean,
    accountName: String,
    a: FilesActions,
    previewUrl: (CloudFile) -> String?,
    snack: SnackbarHostState = remember { SnackbarHostState() },
    bottomPadding: PaddingValues = PaddingValues(0.dp),
) {
    Scaffold(
        floatingActionButton = {
            if (st.tab == FilesTab.MyFiles && st.search == null && st.selected.isEmpty()) ExtendedFloatingActionButton(
                onClick = a.onNew,
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("New") },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(bottom = bottomPadding.calculateBottomPadding()),
            )
        },
        snackbarHost = { SnackbarHost(snack, Modifier.padding(bottom = bottomPadding.calculateBottomPadding())) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            if (st.selected.isNotEmpty()) SelectionBar(st, a) else SearchPill(st.search, accountName, a.onSearch, a.onAccount)
            if (st.search == null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilesTab.entries.forEach { t ->
                        FilterChip(
                            selected = st.tab == t, onClick = { a.onTab(t) }, label = { Text(t.label) },
                            shape = RoundedCornerShape(8.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }
            if (st.tab == FilesTab.MyFiles && st.path != "/" && st.search == null) Breadcrumbs(st, a)
            st.transfer?.let { t ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(t.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (t.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    else LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            SortRow(st, grid, a)
            PullToRefreshBox(st.loading && st.items.isNotEmpty(), a.onRefresh, Modifier.fillMaxSize()) {
                val items = st.sorted
                val listPad = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 88.dp)
                when {
                    items.isEmpty() && st.loading -> CenteredLoading()
                    items.isEmpty() && st.error != null -> Pullable { ErrorState(st.error, a.onRefresh) }
                    items.isEmpty() -> Pullable { EmptyForTab(st) }
                    grid -> LazyVerticalGrid(
                        GridCells.Adaptive(160.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = listPad.calculateBottomPadding()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(items, key = { it.path }) { f -> FileCard(f, f.path in st.offlinePaths, previewUrl(f), a, st.selected.isNotEmpty(), f.path in st.selected) }
                    }
                    else -> LazyColumn(contentPadding = listPad) {
                        items(items, key = { it.path }) { f -> FileRow(f, f.path in st.offlinePaths, previewUrl(f), st.tab, a, st.selected.isNotEmpty(), f.path in st.selected) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyForTab(st: FilesState) {
    when {
        st.search != null -> EmptyState(Icons.Outlined.Search, "No results", "Nothing matches \"${st.search}\"")
        st.tab == FilesTab.Starred -> EmptyState(Icons.Outlined.StarBorder, "No starred files", "Add stars to things that you want to easily find later")
        st.tab == FilesTab.Shared -> EmptyState(Icons.Outlined.People, "No shared files", "Files shared with you, or that you shared, will appear here")
        st.tab == FilesTab.Offline -> EmptyState(Icons.Outlined.CloudOff, "No offline files", "Make files available offline to open them without a connection")
        else -> EmptyState(Icons.Outlined.FolderOpen, "This folder is empty", "Tap New to add a folder or upload files")
    }
}

@Composable
private fun SearchPill(query: String?, accountName: String, onSearch: (String?) -> Unit, onAccount: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(query ?: "") }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (editing || query != null) {
                IconButton(onClick = { editing = false; text = ""; onSearch(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                BasicTextField(
                    text, { text = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text("Search in Files", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        inner()
                    },
                )
                if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, "Clear") }
            } else {
                Row(
                    Modifier.weight(1f).clickable { editing = true }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, Modifier.padding(start = 12.dp, end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Search in Files", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = onAccount) { LetterAvatar(accountName, 32.dp) }
            }
        }
    }
}

@Composable
private fun Breadcrumbs(st: FilesState, a: FilesActions) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = a.onUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
        st.breadcrumbs.forEachIndexed { i, (name, path) ->
            if (i > 0) Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == st.breadcrumbs.lastIndex
            Text(
                name,
                style = if (last) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last) { a.onOpenFolder(path) }.padding(6.dp),
            )
        }
    }
}

@Composable
private fun SortRow(st: FilesState, grid: Boolean, a: FilesActions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { menu = true }) {
                Text(st.sort.label, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(4.dp))
                Icon(if (st.ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(menu, { menu = false }) {
                SortBy.entries.forEach { s ->
                    DropdownMenuItem(text = { Text(s.label) }, onClick = { menu = false; a.onSort(s) })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = a.onToggleGrid) {
            if (grid) Icon(Icons.AutoMirrored.Filled.ViewList, "List view") else Icon(Icons.Outlined.GridView, "Grid view")
        }
    }
}

private fun subtitle(f: CloudFile, tab: FilesTab): String = when {
    tab == FilesTab.Recent -> "Modified ${formatDate(f.modified)} · ${f.parent.substringAfterLast('/').ifEmpty { "My Files" }}"
    f.isFolder -> "Modified ${formatDate(f.modified)}"
    else -> "${formatBytes(f.size)} · Modified ${formatDate(f.modified)}"
}

/** Tap opens; a long press starts picking several, and then taps add or remove. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.fileClicks(f: CloudFile, a: FilesActions, selecting: Boolean) = combinedClickable(
    onClick = { if (selecting) a.onSelect(f) else if (f.isFolder) a.onOpenFolder(f.path) else a.onOpenFile(f) },
    onLongClick = { a.onSelect(f) },
    onLongClickLabel = "Select",
)

/** Shown instead of the search pill while items are picked: how many, and what can be done with them all. */
@Composable
private fun SelectionBar(st: FilesState, a: FilesActions) {
    val chosen = st.selectedFiles
    var more by remember { mutableStateOf(false) }
    // Same place and height as the search pill, so nothing moves under the finger.
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = a.onClearSelection) { Icon(Icons.Filled.Close, "Clear selection") }
        Text(
            "${chosen.size} selected", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        val all = chosen.size == st.items.size
        IconButton(onClick = if (all) a.onClearSelection else a.onSelectAll) {
            Icon(if (all) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, if (all) "Deselect all" else "Select all")
        }
        IconButton(onClick = a.onMoveSelected) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Move") }
        IconButton(onClick = a.onDeleteSelected) { Icon(Icons.Outlined.Delete, "Remove") }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "More actions") }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem(text = { Text("Copy to…") }, onClick = { more = false; a.onCopySelected() })
                DropdownMenuItem(
                    text = { Text(if (chosen.isNotEmpty() && chosen.all { it.favorite }) "Remove from Starred" else "Add to Starred") },
                    onClick = { more = false; a.onStarSelected() },
                )
                val files = chosen.filter { !it.isFolder }
                if (files.isNotEmpty()) DropdownMenuItem(
                    text = { Text(if (files.all { it.path in st.offlinePaths }) "Remove offline copies" else "Make available offline") },
                    onClick = { more = false; a.onOfflineSelected() },
                )
            }
        }
    }
}

@Composable
fun FileRow(f: CloudFile, offline: Boolean, preview: String?, tab: FilesTab, a: FilesActions, selecting: Boolean = false, selected: Boolean = false) {
    ListItem(
        modifier = Modifier.fileClicks(f, a, selecting).semantics { this.selected = selected },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        leadingContent = {
            if (selected) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { SelectCircle(true, Modifier.size(24.dp)) }
            } else if (preview != null && f.kind() == FileKind.Image) {
                RemoteImage(preview, f.path, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
            } else Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { FileIcon(f) }
        },
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (f.favorite) Icon(Icons.Filled.Star, "Starred", Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (offline) Icon(Icons.Filled.OfflinePin, "Available offline", Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (f.shared) Icon(Icons.Outlined.People, "Shared", Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(subtitle(f, tab), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = { if (!selecting) IconButton(onClick = { a.onMore(f) }) { Icon(Icons.Filled.MoreVert, "More actions") } },
    )
}

@Composable
fun FileCard(f: CloudFile, offline: Boolean, preview: String?, a: FilesActions, selecting: Boolean = false, selected: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fileClicks(f, a, selecting).semantics { this.selected = selected },
    ) {
        Column {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected) SelectCircle(true, Modifier.size(20.dp)) else FileIcon(f, 20.dp)
                Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
                if (selecting) Spacer(Modifier.size(48.dp)) else IconButton(onClick = { a.onMore(f) }) { Icon(Icons.Filled.MoreVert, "More actions") }
            }
            if (!f.isFolder) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1.25f).padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview != null) RemoteImage(preview, f.path, Modifier.fillMaxSize()) else FileIcon(f, 56.dp)
                    Row(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                        if (f.favorite) Icon(Icons.Filled.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (offline) Icon(Icons.Filled.OfflinePin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSheet(onDismiss: () -> Unit, onFolder: () -> Unit, onUpload: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Create new", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            NewTile(Icons.Outlined.CreateNewFolder, "Folder", onFolder)
            NewTile(Icons.Outlined.UploadFile, "Upload", onUpload)
        }
    }
}

@Composable
private fun NewTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(12.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && '/' !in name, onClick = { onOk(name.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
