package app.immcloudscale.ui.photos

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.graph
import app.immcloudscale.ui.components.RemoteImage
import app.immcloudscale.ui.components.formatBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Full-screen photo/video viewer with swipe, pinch-zoom and the Google Photos action bar. */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(onBack: () -> Unit, onEdit: (AssetDto) -> Unit = {}) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val items = remember { mutableStateListOf<AssetDto>().apply { addAll(ViewerSession.assets) } }
    if (items.isEmpty()) { LaunchedEffect(Unit) { onBack() }; return }
    val pager = rememberPagerState(ViewerSession.start.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf<AssetDto?>(null) }
    var menu by remember { mutableStateOf(false) }
    var albumPicker by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf<AssetDto?>(null) }
    var confirmTrash by remember { mutableStateOf<AssetDto?>(null) }
    var confirmLock by remember { mutableStateOf<AssetDto?>(null) }
    var slideshow by remember { mutableStateOf(false) }
    val phoneTrash = rememberPhoneTrash()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val trash = ViewerSession.query == AssetQuery.Trash
    val current = items.getOrNull(pager.currentPage)

    // Slideshow: a new photo every 4 seconds, without the bars; any tap stops it.
    LaunchedEffect(slideshow) {
        if (!slideshow) return@LaunchedEffect
        chrome = false
        launch { snack.showSnackbar("Tap the photo to stop the slideshow") }
        while (slideshow) {
            delay(4_000)
            // Starts over after the last photo, like a photo frame.
            if (pager.currentPage >= items.lastIndex) pager.scrollToPage(0) else pager.animateScrollToPage(pager.currentPage + 1)
        }
    }
    // The screen stays on during a slideshow.
    val view = LocalView.current
    DisposableEffect(slideshow) {
        view.keepScreenOn = slideshow
        onDispose { view.keepScreenOn = false }
    }

    fun remove(a: AssetDto, msg: String) {
        ViewerSession.onEdit?.invoke(null, a.id)
        items.remove(a)
        scope.launch { snack.showSnackbar(msg) }
        if (items.isEmpty()) onBack()
    }

    fun run(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { snack.showSnackbar(it.message ?: "Something went wrong") }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, key = { items.getOrNull(it)?.id ?: it }, beyondViewportPageCount = 1) { page ->
            val a = items[page]
            if (a.isVideo) VideoPage(g.immich.videoUrl(a.id), active = page == pager.currentPage) { slideshow = false; chrome = !chrome }
            else ZoomableImage(g.immich.thumbnailUrl(a.id, preview = true), a.id) { slideshow = false; chrome = !chrome }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    current?.let {
                        Text(viewerDate(it), color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(viewerTime(it), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (current != null && !trash) {
                    IconButton(onClick = {
                        val updated = current.copy(isFavorite = !current.isFavorite)
                        run {
                            g.immich.setFavorite(listOf(current.id), updated.isFavorite)
                            items[items.indexOf(current)] = updated
                            ViewerSession.onEdit?.invoke(updated, null)
                        }
                    }) {
                        if (current.isFavorite) Icon(Icons.Filled.Favorite, "Unfavorite", tint = Color.White)
                        else Icon(Icons.Outlined.FavoriteBorder, "Favorite", tint = Color.White)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Slideshow") }, onClick = { menu = false; slideshow = true })
                            if (!current.isVideo) DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(current) })
                            DropdownMenuItem(text = { Text("Add to album") }, onClick = { menu = false; albumPicker = true })
                            DropdownMenuItem(text = { Text("Create link") }, onClick = {
                                menu = false; run { shareText(ctx, g.immich.createShareLink(listOf(current.id))) }
                            })
                            DropdownMenuItem(
                                text = { Text(if (ViewerSession.query == AssetQuery.Archive) "Unarchive" else "Archive") },
                                onClick = {
                                    menu = false
                                    val archive = ViewerSession.query != AssetQuery.Archive
                                    run { g.immich.setArchived(listOf(current.id), archive); remove(current, if (archive) "Archived" else "Unarchived") }
                                },
                            )
                            DropdownMenuItem(text = { Text("Move to Locked folder") }, onClick = { menu = false; confirmLock = current })
                        }
                    }
                }
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x66000000)).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (current != null && trash) {
                    BarAction(Icons.AutoMirrored.Outlined.Undo, "Restore") { run { g.immich.restore(listOf(current.id)); remove(current, "Restored") } }
                    BarAction(Icons.Outlined.DeleteOutline, "Delete") { confirmForever = current }
                } else if (current != null) {
                    BarAction(Icons.Outlined.Share, "Share") { run { shareAssets(ctx, listOf(current)) } }
                    BarAction(Icons.Outlined.Info, "Info") { info = current }
                    BarAction(Icons.Outlined.DeleteOutline, "Delete") { confirmTrash = current }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    confirmLock?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmLock = null },
            title = { Text("Move to Locked folder?") },
            text = {
                Text(
                    "It's hidden from your timeline, albums, search and other apps, and opens only with your PIN " +
                        "(Collections → Locked folder). This phone's copy moves to the phone's trash.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLock = null
                    run { g.immich.setVisibility(listOf(a.id), "locked"); remove(a, "Moved to Locked folder"); phoneTrash(listOf(a)) }
                }) { Text("Move to Locked folder") }
            },
            dismissButton = { TextButton(onClick = { confirmLock = null }) { Text("Cancel") } },
        )
    }

    confirmTrash?.let { a ->
        TrashConfirmDialog(1, onDismiss = { confirmTrash = null }, onConfirm = {
            confirmTrash = null
            run { g.immich.delete(listOf(a.id)); remove(a, "Moved to trash"); phoneTrash(listOf(a)) }
        })
    }

    confirmForever?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmForever = null },
            title = { Text("Delete forever?") },
            text = { Text("This photo will be permanently deleted from your server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForever = null
                    run { g.immich.delete(listOf(a.id), force = true); remove(a, "Deleted") }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmForever = null }) { Text("Cancel") } },
        )
    }

    info?.let { a ->
        var detail by remember(a.id) { mutableStateOf(a) }
        LaunchedEffect(a.id) { runCatching { g.immich.asset(a.id) }.onSuccess { detail = it } }
        ModalBottomSheet(onDismissRequest = { info = null }) { InfoSheet(detail) }
    }
    if (albumPicker && current != null) AlbumPickerDialog(
        onDismiss = { albumPicker = false },
        onPick = { albumPicker = false; run { g.immich.addToAlbum(it.id, listOf(current.id)); snack.showSnackbar("Added to ${it.albumName}") } },
        onCreate = { name -> albumPicker = false; run { g.immich.createAlbum(name, listOf(current.id)); snack.showSnackbar("Created $name") } },
    )
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Color.White)
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun parsed(a: AssetDto) = runCatching { OffsetDateTime.parse(a.localDateTime ?: a.fileCreatedAt) }.getOrNull()
private fun viewerDate(a: AssetDto) = parsed(a)?.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")) ?: ""
private fun viewerTime(a: AssetDto) = parsed(a)?.format(DateTimeFormatter.ofPattern("h:mm a")) ?: ""

@Composable
fun InfoSheet(a: AssetDto) {
    val e = a.exifInfo
    Column(Modifier.padding(bottom = 32.dp)) {
        Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        e?.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.Image, null) },
            headlineContent = { Text(a.originalFileName) },
            supportingContent = {
                Text(listOfNotNull(
                    if (e?.exifImageWidth != null && e.exifImageHeight != null) "%.1fMP".format(e.exifImageWidth * e.exifImageHeight / 1_000_000.0) else null,
                    if (e?.exifImageWidth != null) "${e.exifImageWidth} × ${e.exifImageHeight}" else null,
                    e?.fileSizeInByte?.let(::formatBytes),
                ).joinToString("   "))
            },
        )
        if (e?.make != null || e?.model != null) ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.CameraAlt, null) },
            headlineContent = { Text(listOfNotNull(e.make, e.model).joinToString(" ")) },
            supportingContent = {
                Text(listOfNotNull(
                    e.fNumber?.let { "ƒ/$it" }, e.exposureTime?.let { "${it}s" },
                    e.focalLength?.let { "${it}mm" }, e.iso?.let { "ISO $it" },
                ).joinToString("   "))
            },
        )
        if (e?.city != null) ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.LocationOn, null) },
            headlineContent = { Text(listOfNotNull(e.city, e.state, e.country).joinToString(", ")) },
            supportingContent = { if (e.latitude != null) Text("%.5f, %.5f".format(e.latitude, e.longitude)) },
        )
        if (a.people.isNotEmpty()) ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text("People") },
            supportingContent = { Text(a.people.mapNotNull { it.name.ifBlank { null } }.joinToString(", ").ifEmpty { "Unnamed" }) },
        )
    }
}

/** Image with pinch-to-zoom, pan when zoomed and double-tap to zoom; swipes pass to the pager at 1×. */
@Composable
fun ZoomableImage(url: String?, key: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val multi = ev.changes.count { it.pressed } >= 2
                        if (multi || scale > 1f) {
                            scale = (scale * ev.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + ev.calculatePan()
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        RemoteImage(
            url, key,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            contentScale = ContentScale.Fit,
            background = Color.Black,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPage(url: String, active: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(ctx.graph.http)))
            .build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() }
    }
    LaunchedEffect(active) { player.playWhenReady = active; if (!active) player.pause() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; setOnClickListener { onTap() } } },
        modifier = Modifier.fillMaxSize(),
    )
}
