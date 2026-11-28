package app.immcloudscale.ui.photos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

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
                _state.update { it.copy(media = media, loading = false, selected = it.selected.filterTo(HashSet()) { k -> media.any { m -> m.key() == k } }) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't compare with your library") }
            }
        }
    }

    fun folder(f: String?) = _state.update { it.copy(folder = f, selected = emptySet()) }
    fun toggle(key: String) = _state.update { s -> s.copy(selected = if (key in s.selected) s.selected - key else s.selected + key) }
    fun selectAll(keys: Collection<String>) = _state.update { s -> s.copy(selected = s.selected + keys) }
    fun clear() = _state.update { it.copy(selected = emptySet()) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Backs up the picked photos (or all shown when none are picked) now, one by one. */
    fun backUp(all: List<LocalMedia>) {
        val s = _state.value
        val chosen = if (s.selected.isEmpty()) all else all.filter { it.key() in s.selected }
        if (chosen.isEmpty() || s.uploading != null) return
        viewModelScope.launch {
            val device = PhoneCopies.deviceId(g.context)
            var done = 0
            var failed = 0
            var lastError: String? = null
            val gone = HashSet<String>()
            _state.update { it.copy(uploading = 0 to chosen.size, selected = emptySet()) }
            for (m in chosen) {
                try {
                    withContext(Dispatchers.IO) { PhoneUpload.upload(g.context, g.immich, m, device) }
                    gone += m.key()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed++
                    lastError = e.message
                    if (!PhoneUpload.permanent(e)) break // no connection or server trouble: the rest would fail too
                }
                done++
                _state.update { it.copy(uploading = done to chosen.size) }
            }
            val ok = gone.size
            _state.update { st ->
                st.copy(
                    media = st.media.filter { it.key() !in gone }, uploading = null,
                    message = when {
                        failed == 0 && ok == chosen.size -> "$ok backed up"
                        ok == 0 -> "Couldn't back up: ${lastError ?: "unknown error"}"
                        else -> "$ok backed up, ${chosen.size - ok} not: ${lastError ?: "unknown error"}"
                    },
                )
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
    var allowed by remember { mutableStateOf(perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> allowed = r.values.any { it } }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            if (shown.isNotEmpty() && st.uploading == null && settings.immichConfigured) ExtendedFloatingActionButton(
                onClick = { vm.backUp(shown) },
                icon = { Icon(Icons.Outlined.CloudUpload, null) },
                text = { Text(if (st.selected.isEmpty()) "Back up ${if (st.folder == null) "all" else "these"} (${shown.size})" else "Back up ${st.selected.size}") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            st.uploading?.let { (done, total) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Backing up $done of $total…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            when {
                !allowed -> EmptyState(
                    Icons.Outlined.PhotoLibrary, "Allow access to your photos",
                    "To show what isn't backed up yet, the app needs to see the photos and videos on this phone.",
                    action = "Allow", onAction = { ask.launch(perms) },
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
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Swipes through photos that are only on the phone; videos play in the phone's own player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalViewerScreen(onBack: () -> Unit) {
    val items = LocalViewerSession.items
    if (items.isEmpty()) return onBack()
    val ctx = LocalContext.current
    val pager = rememberPagerState(LocalViewerSession.start.coerceIn(0, items.lastIndex)) { items.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }) { i ->
            val a = items[i]
            var scale by remember { mutableFloatStateOf(1f) }
            var dx by remember { mutableFloatStateOf(0f) }
            var dy by remember { mutableFloatStateOf(0f) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = a.localUri, contentDescription = a.originalFileName, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(a.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) { dx += pan.x; dy += pan.y } else { dx = 0f; dy = 0f }
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = dx; translationY = dy },
                )
                if (a.isVideo) {
                    IconButton(
                        onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.localUri)).setDataAndType(Uri.parse(a.localUri), "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        },
                        modifier = Modifier.size(80.dp),
                    ) { Icon(Icons.Filled.PlayCircle, "Play video", tint = Color.White, modifier = Modifier.size(72.dp)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 32.dp, start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                val a = items[pager.currentPage]
                Text(a.originalFileName, color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text("Only on this phone · not backed up yet", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
