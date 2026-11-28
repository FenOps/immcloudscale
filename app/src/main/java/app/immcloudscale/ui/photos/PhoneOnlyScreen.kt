package app.immcloudscale.ui.photos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.immcloudscale.AppGraph
import app.immcloudscale.data.backup.LocalMedia
import app.immcloudscale.data.backup.LocalMediaSource
import app.immcloudscale.data.backup.PhoneCopies
import app.immcloudscale.data.backup.PhoneUpload
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.groupByDay
import app.immcloudscale.graph
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.ErrorState
import app.immcloudscale.ui.components.graphViewModel
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Back up now" runs here, outside the screen, so leaving the screen doesn't stop it; the screen shows its progress
 * when it's open.
 */
object BackupNow {
    val progress = MutableStateFlow<Pair<Int, Int>?>(null) // done, total
    val finished = MutableStateFlow<Pair<Set<String>, String>?>(null) // backed-up keys, message
    var job: Job? = null

    fun stop() { job?.cancel() }
}

data class PhoneOnlyState(
    val loading: Boolean = true,
    val error: String? = null,
    val media: List<LocalMedia> = emptyList(), // every photo and video on the phone that isn't in the library
    val folder: String? = null,
    val selected: Set<String> = emptySet(),
    val uploading: Pair<Int, Int>? = null, // done, total
    val message: String? = null,
)

/** Photos and videos on this phone that aren't in the library yet: every folder, any date, backup on or off. */
class PhoneOnlyViewModel(private val g: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(PhoneOnlyState())
    val state: StateFlow<PhoneOnlyState> = _state

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.media.isEmpty(), error = null) }
            try {
                val media = withContext(Dispatchers.IO) {
                    val mine = PhoneCopies.deviceId(g.context)
                    val done = g.immich.assetsFrom(mine).mapTo(HashSet()) { it.deviceAssetId }
                    // Already in the library from elsewhere (same name and day), e.g. uploaded from a computer.
                    val s = g.settingsState.value
                    val saved = runCatching { g.timelineCache.load(s.immichUrl, s.immichEmail.ifBlank { s.immichUserName }) }.getOrNull()?.assets.orEmpty()
                    val named = saved.asSequence().flatMap { nameDayKeys(it) }.toHashSet()
                    LocalMediaSource.query(g.context, 0, true, emptySet())
                        .filter { m -> "${m.id}-${m.size}" !in done && nameDayKey(m.toAsset()) !in named }
                        .sortedByDescending { it.dateTaken.takeIf { t -> t > 0 } ?: (it.dateAdded * 1000) }
                }
                val gone = PhoneTrashed.ids.value
                val kept = media.filter { it.key() !in gone }
                _state.update { it.copy(media = kept, loading = false, selected = it.selected.filterTo(HashSet()) { k -> kept.any { m -> m.key() == k } }) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't compare with your library") }
            }
        }
    }

    fun folder(f: String?) = _state.update { it.copy(folder = f, selected = emptySet()) }
    fun message(m: String) = _state.update { it.copy(message = m) }
    fun toggle(key: String) = _state.update { s -> s.copy(selected = if (key in s.selected) s.selected - key else s.selected + key) }
    fun selectAll(keys: Collection<String>) = _state.update { s -> s.copy(selected = s.selected + keys) }
    fun clear() = _state.update { it.copy(selected = emptySet()) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    init {
        viewModelScope.launch { BackupNow.progress.collect { p -> _state.update { it.copy(uploading = p) } } }
        // Moved to the phone's trash, here or in the viewer: out of the list.
        viewModelScope.launch {
            PhoneTrashed.ids.collect { gone -> _state.update { s -> s.copy(media = s.media.filter { it.key() !in gone }, selected = s.selected - gone) } }
        }
        viewModelScope.launch {
            BackupNow.finished.collect { f ->
                if (f == null) return@collect
                BackupNow.finished.value = null
                _state.update { st -> st.copy(media = st.media.filter { it.key() !in f.first }, message = f.second) }
            }
        }
    }

    /** Backs up the picked photos (or all shown when none are picked) now, one by one. */
    fun backUp(all: List<LocalMedia>) {
        val s = _state.value
        val chosen = if (s.selected.isEmpty()) all else all.filter { it.key() in s.selected }
        if (chosen.isEmpty() || BackupNow.job?.isActive == true) return
        _state.update { it.copy(selected = emptySet()) }
        BackupNow.job = g.scope.launch {
            val device = PhoneCopies.deviceId(g.context)
            var failed = 0
            var lastError: String? = null
            val gone = HashSet<String>()
            BackupNow.progress.value = 0 to chosen.size
            try {
                for ((i, m) in chosen.withIndex()) {
                    try {
                        withContext(Dispatchers.IO) { PhoneUpload.upload(g.context, g.immich, m, device) }
                        gone += m.key()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        failed++
                        lastError = e.message
                        if (!PhoneUpload.permanent(e)) break // no connection or server trouble: the rest would fail too
                    }
                    BackupNow.progress.value = i + 1 to chosen.size
                }
            } finally {
                val ok = gone.size
                BackupNow.progress.value = null
                BackupNow.finished.value = gone to when {
                    ok == chosen.size -> "$ok backed up"
                    failed == 0 -> "Stopped: $ok backed up" // Stop was tapped
                    ok == 0 -> "Couldn't back up: ${lastError ?: "unknown error"}"
                    else -> "$ok backed up, ${chosen.size - ok} not: ${lastError ?: "unknown error"}"
                }
            }
        }
    }
}

fun LocalMedia.key() = "local:$id"

fun LocalMedia.toAsset() = AssetDto(
    id = key(), type = if (mime.startsWith("video")) "VIDEO" else "IMAGE", originalFileName = name,
    fileCreatedAt = Instant.ofEpochMilli(if (dateTaken > 0) dateTaken else dateAdded * 1000).toString(),
    localUri = uri.toString(),
)

/** The phone's own photos to swipe through in [LocalViewerScreen] (set just before opening it). */
object LocalViewerSession {
    var items: List<AssetDto> = emptyList()
    var start: Int = 0
    var inTrash = false // opened from the phone's trash: nothing to delete, and they're shown although trashed
}

/** Phone-only photos moved to the phone's trash this session ("local:<id>"), for every screen showing them. */
object PhoneTrashed {
    val ids = MutableStateFlow<Set<String>>(emptySet())
    /** Goes up each time photos are restored from the phone's trash, so screens showing phone photos look again. */
    val restored = MutableStateFlow(0)
    fun add(keys: Collection<String>) = ids.update { it + keys }
    fun restore(keys: Collection<String>) {
        ids.update { it - keys.toSet() }
        restored.update { it + 1 }
    }
}

enum class TrashResult { Trashed, Cancelled, Failed }

/**
 * Moves photos that are only on this phone to the phone's trash (Android keeps them 30 days). Android asks to
 * confirm unless the app has "Media management" access. Returns a function that starts it for some photos.
 */
@Composable
fun rememberPhoneOnlyTrash(onDone: (TrashResult) -> Unit): (List<String>) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        onDone(if (r.resultCode == android.app.Activity.RESULT_OK) TrashResult.Trashed else TrashResult.Cancelled)
    }
    return { uris ->
        scope.launch {
            val sender = withContext(Dispatchers.IO) { runCatching { PhoneCopies.trashRequest(ctx, uris.map(Uri::parse)) }.getOrNull() }
            if (sender != null) launcher.launch(IntentSenderRequest.Builder(sender).build()) else onDone(TrashResult.Failed)
        }
    }
}

/** Android 11 and newer have a trash for photos; older phones can't move them there. */
val canTrashOnPhone get() = Build.VERSION.SDK_INT >= 30

/** Asks first: these aren't backed up, so the phone has the only copy. */
@Composable
fun ConfirmPhoneTrash(n: Int, video: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val one = n == 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (one) "Delete this ${if (video) "video" else "photo"} from your phone?" else "Delete $n items from your phone?") },
        text = {
            Text(
                (if (one) "It isn't backed up, so this is the only copy. It goes" else "They aren't backed up, so these are the only copies. They go") +
                    " to your phone's trash, where Android keeps ${if (one) "it" else "them"} for 30 days, then deletes ${if (one) "it" else "them"} for good.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Move to trash") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneOnlyScreen(onBack: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings by g.settingsState.collectAsStateWithLifecycle()
    val vm = graphViewModel { PhoneOnlyViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val perms = mediaPermissions()
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(perms.any(::granted)) }
    var denied by rememberSaveable { mutableStateOf(false) } // refused for good: only Settings can change it
    var confirmAll by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf<List<LocalMedia>?>(null) }
    // Kept across rotation: the system's confirmation can outlive the screen.
    var trashing by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val trash = rememberPhoneOnlyTrash { result ->
        val keys = trashing
        trashing = emptyList()
        when {
            result == TrashResult.Failed -> vm.message("Couldn't move to your phone's trash")
            result != TrashResult.Trashed -> Unit
            keys.isEmpty() -> vm.load() // not known which: look again
            else -> {
                PhoneTrashed.add(keys)
                vm.message(if (keys.size == 1) "Moved to your phone's trash for 30 days" else "${keys.size} moved to your phone's trash for 30 days")
            }
        }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        allowed = r.values.any { it }
        // Android asks again after a first "Don't allow"; once it stops showing a reason, it won't ask any more.
        val activity = ctx.findActivity()
        denied = !allowed && activity != null && perms.none { activity.shouldShowRequestPermissionRationale(it) }
    }
    // Back from Settings with access given (or taken away).
    LifecycleResumeEffect(Unit) {
        allowed = perms.any(::granted)
        onPauseOrDispose {}
    }
    // Android 14's "selected photos only": the app sees just those, so "everything backed up" may not be true.
    val partial = Build.VERSION.SDK_INT >= 34 && !granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    LaunchedEffect(allowed) { if (allowed) vm.load() }
    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    BackHandler(st.selected.isNotEmpty()) { vm.clear() }

    val shown = remember(st.media, st.folder) { if (st.folder == null) st.media else st.media.filter { it.bucket == st.folder } }
    val assets = remember(shown) { shown.map { it.toAsset() } }
    val sections = remember(assets) { groupByDay(assets) }
    val folders = remember(st.media) { st.media.groupingBy { it.bucket }.eachCount().entries.sortedByDescending { it.value } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (st.selected.isEmpty()) "On this phone only" else "${st.selected.size} selected")
                        if (st.selected.isEmpty() && !st.loading) {
                            Text("${st.media.size} not backed up", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    if (st.selected.isEmpty()) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else IconButton(onClick = vm::clear) { Icon(Icons.Filled.Close, "Clear selection") }
                },
                actions = {
                    if (st.selected.isNotEmpty() && canTrashOnPhone) IconButton(onClick = { confirmTrash = st.media.filter { it.key() in st.selected } }) {
                        Icon(Icons.Outlined.Delete, "Delete from phone")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (shown.isNotEmpty() && st.uploading == null && settings.immichConfigured) ExtendedFloatingActionButton(
                onClick = { if (st.selected.isEmpty() && shown.size > 20) confirmAll = true else vm.backUp(shown) },
                icon = { Icon(Icons.Outlined.CloudUpload, null) },
                text = { Text(if (st.selected.isEmpty()) "Back up ${if (st.folder == null) "all" else "these"} (${shown.size})" else "Back up ${st.selected.size}") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            st.uploading?.let { (done, total) ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Backing up $done of $total… (you can leave this screen)", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                    TextButton(onClick = BackupNow::stop) { Text("Stop") }
                }
            }
            if (partial && allowed) {
                Text(
                    "The app can only see the photos you chose for it, so others on this phone aren't listed. To see them all, allow access to all photos in Settings.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                !allowed -> EmptyState(
                    Icons.Outlined.PhotoLibrary, "Allow access to your photos",
                    "To show what isn't backed up yet, the app needs to see the photos and videos on this phone." +
                        if (denied) " Android won't ask again: allow it in Settings, Permissions, Photos and videos." else "",
                    action = if (denied) "Open settings" else "Allow",
                    onAction = {
                        if (denied) runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }
                        else ask.launch(perms)
                    },
                )
                st.loading -> CenteredLoading()
                st.error != null && st.media.isEmpty() -> ErrorState(st.error!!, vm::load)
                st.media.isEmpty() -> EmptyState(Icons.Outlined.CloudDone, "Everything is backed up", "Every photo and video on this phone is in your library.")
                else -> {
                    if (folders.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer)
                            FilterChip(st.folder == null, { vm.folder(null) }, { Text("All (${st.media.size})") }, shape = RoundedCornerShape(8.dp), colors = chip)
                            folders.forEach { (f, n) ->
                                FilterChip(st.folder == f, { vm.folder(f) }, { Text("${f.ifBlank { "Other" }} ($n)") }, shape = RoundedCornerShape(8.dp), colors = chip)
                            }
                        }
                    }
                    AssetGrid(
                        sections = sections,
                        columns = settings.photoColumns,
                        selected = st.selected,
                        thumbUrl = { it.localUri },
                        onOpen = { a ->
                            if (st.selected.isNotEmpty()) vm.toggle(a.id)
                            else {
                                LocalViewerSession.items = assets
                                LocalViewerSession.start = assets.indexOfFirst { it.id == a.id }.coerceAtLeast(0)
                                LocalViewerSession.inTrash = false
                                onOpen()
                            }
                        },
                        onToggle = { vm.toggle(it.id) },
                        onSelectDay = { day ->
                            val ids = day.assets.map { it.id }
                            if (ids.all { it in st.selected }) ids.forEach(vm::toggle) else vm.selectAll(ids)
                        },
                        onColumnsChange = { n -> vm.viewModelScope.launch { g.settings.update { it.copy(photoColumns = n) } } },
                        selectLocal = true,
                    )
                }
            }
        }
    }
    confirmTrash?.let { chosen ->
        ConfirmPhoneTrash(chosen.size, onDismiss = { confirmTrash = null }) {
            confirmTrash = null
            trashing = chosen.map { it.key() }
            trash(chosen.map { it.uri.toString() })
        }
    }
    if (confirmAll) ConfirmBackUp(shown.size, onDismiss = { confirmAll = false }) { confirmAll = false; vm.backUp(shown) }
}

@Composable
private fun ConfirmBackUp(n: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up $n photos and videos?") },
        text = { Text("They're uploaded one by one now, which can take a while and use a lot of data on mobile data. You can stop at any time.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Back up") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun android.content.Context.findActivity(): android.app.Activity? {
    var c = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

internal fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Swipes through photos that are only on the phone; videos play in the phone's own player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalViewerScreen(onBack: () -> Unit) {
    val items = LocalViewerSession.items
    if (items.isEmpty()) { // e.g. the app was restarted on this screen
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val ctx = LocalContext.current
    // Without what was trashed already (also after a rotation, when this is built again).
    val trashed by PhoneTrashed.ids.collectAsStateWithLifecycle()
    val inTrash = LocalViewerSession.inTrash
    var failed by remember { mutableStateOf<String?>(null) } // what couldn't be done
    // From the phone's trash: those restored or deleted for good here leave the pager.
    var done by rememberSaveable { mutableStateOf(setOf<String>()) }
    val list = if (inTrash) items.filter { it.id !in done } else items.filter { it.id !in trashed }
    var trashAction by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) } // "restore" | "delete", id
    var confirmForever by remember { mutableStateOf<AssetDto?>(null) }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val act = trashAction
        trashAction = null
        if (r.resultCode == android.app.Activity.RESULT_OK && act != null) {
            done = done + act.second
            if (act.first == "restore") PhoneTrashed.restore(listOf(act.second))
        }
    }
    val scope = rememberCoroutineScope()
    fun trashRequest(kind: String, a: AssetDto) {
        val uri = a.localUri ?: return
        if (trashAction != null) return
        trashAction = kind to a.id
        scope.launch {
            val sender = withContext(Dispatchers.IO) {
                runCatching { if (kind == "restore") PhoneCopies.restoreRequest(ctx, listOf(Uri.parse(uri))) else PhoneCopies.deleteRequest(ctx, listOf(Uri.parse(uri))) }.getOrNull()
            }
            if (sender == null) {
                trashAction = null
                failed = if (kind == "restore") "Couldn't restore it" else "Couldn't delete it"
            } else trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }
    val pager = rememberPagerState(LocalViewerSession.start.coerceIn(0, maxOf(items.lastIndex, 0))) { list.size }
    var confirm by remember { mutableStateOf<AssetDto?>(null) } // the photo whose bin was tapped
    var trashing by rememberSaveable { mutableStateOf<String?>(null) }
    val trash = rememberPhoneOnlyTrash { result ->
        val id = trashing
        trashing = null
        when (result) {
            TrashResult.Trashed -> if (id != null) PhoneTrashed.add(listOf(id))
            TrashResult.Failed -> failed = "Couldn't move to your phone's trash"
            TrashResult.Cancelled -> Unit
        }
    }
    LaunchedEffect(list.isEmpty()) { if (list.isEmpty()) onBack() }
    if (list.isEmpty()) return
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }) { i ->
            val a = list[i]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ZoomableImage(a.localUri, a.id, onTap = {}) // pinch or double-tap to zoom; one finger swipes between photos
                if (a.isVideo) {
                    IconButton(
                        onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(a.localUri), "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        },
                        modifier = Modifier.size(80.dp),
                    ) { Icon(Icons.Filled.PlayCircle, "Play video", tint = Color.White, modifier = Modifier.size(72.dp)) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .statusBarsPadding().padding(start = 4.dp, end = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                val a = list[pager.currentPage.coerceIn(0, list.lastIndex)]
                Text(a.originalFileName, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (inTrash) "In your phone's trash" else "Only on this phone · not backed up yet",
                    color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall,
                )
            }
            if (inTrash && canTrashOnPhone) {
                val a = list[pager.currentPage.coerceIn(0, list.lastIndex)]
                TextButton(onClick = { trashRequest("restore", a) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Restore") }
                TextButton(onClick = { confirmForever = a }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Delete") }
            }
            if (canTrashOnPhone && !inTrash) IconButton(onClick = { confirm = list[pager.currentPage.coerceIn(0, list.lastIndex)] }) {
                Icon(Icons.Outlined.Delete, "Delete from phone", tint = Color.White)
            }
        }
    }
    confirm?.let { a ->
        ConfirmPhoneTrash(1, video = a.isVideo, onDismiss = { confirm = null }) {
            confirm = null
            val uri = a.localUri
            if (uri == null) failed = "Couldn't move to your phone's trash" else { trashing = a.id; trash(listOf(uri)) }
        }
    }
    confirmForever?.let { a ->
        ConfirmDeleteForever(1, onDismiss = { confirmForever = null }) {
            confirmForever = null
            trashRequest("delete", a)
        }
    }
    failed?.let { what ->
        AlertDialog(
            onDismissRequest = { failed = null },
            title = { Text(what) },
            text = { Text("Nothing was changed. Try again, or do it in your phone's gallery app.") },
            confirmButton = { TextButton(onClick = { failed = null }) { Text("OK") } },
        )
    }
}
