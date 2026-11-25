package app.immcloudscale.ui.files

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.nextcloud.CloudFile
import app.immcloudscale.graph
import app.immcloudscale.ui.components.formatBytes
import app.immcloudscale.ui.photos.VideoPage
import app.immcloudscale.ui.photos.ZoomableImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

object FilePreviewSession { var file: CloudFile? = null }

/** In-app preview for images, videos, PDFs and text; everything else opens in another app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(onBack: () -> Unit) {
    val f = FilePreviewSession.file ?: run { LaunchedEffect(Unit) { onBack() }; return }
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val kind = f.kind()
    val dark = kind in setOf(FileKind.Image, FileKind.Video)
    var downloading by remember { mutableStateOf<Float?>(null) }

    fun openWith() = scope.launch {
        val target = g.offline.fileFor(f.path)
        if (!(target.exists() && target.length() == f.size)) {
            downloading = 0f
            runCatching { g.nextcloud.download(f.path, target) { downloading = it } }
            downloading = null
        }
        if (target.exists()) openLocalFile(ctx, target, f.contentType)
    }

    Scaffold(
        containerColor = if (dark) Color.Black else MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { IconButton(onClick = { openWith() }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open with") } },
                colors = if (dark) TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                ) else TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                kind == FileKind.Image -> ZoomableImage(g.nextcloud.fileUrl(f.path), f.path) {}
                kind == FileKind.Video || kind == FileKind.Audio -> VideoPage(g.nextcloud.fileUrl(f.path), active = true) {}
                kind == FileKind.Pdf -> PdfPreview(f)
                f.isTextLike() -> TextPreview(f)
                else -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    FileIcon(f, 72.dp)
                    Text(f.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    Text(formatBytes(f.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No preview available", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { openWith() }, modifier = Modifier.padding(top = 24.dp)) { Text("Open with…") }
                }
            }
            downloading?.let { CircularProgressIndicator(progress = { it }, modifier = Modifier.align(Alignment.Center)) }
        }
    }
}

@Composable
private fun TextPreview(f: CloudFile) {
    val g = LocalContext.current.graph
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(f.path) { text = runCatching { g.nextcloud.readText(f.path) }.getOrElse { "Couldn't load: ${it.message}" } }
    val t = text
    if (t == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else SelectionContainer {
        Text(
            t, fontFamily = if (f.kind() == FileKind.Code) FontFamily.Monospace else FontFamily.Default,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .then(if (f.kind() == FileKind.Code) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(16.dp),
        )
    }
}

/**
 * Renders PDF pages with Android's PdfRenderer, one page at a time as they scroll into view (rendering
 * every page up front runs out of memory on long documents). The file is downloaded to the cache first.
 */
@Composable
private fun PdfPreview(f: CloudFile) {
    val ctx = LocalContext.current
    val g = ctx.graph
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val lock = remember { Mutex() }
    val closed = remember { Collections.newSetFromMap(WeakHashMap<PdfRenderer, Boolean>()) }
    LaunchedEffect(f.path, f.modified) {
        try {
            val name = "${f.fileId ?: f.path.hashCode()}-${f.modified}.pdf" // a new version gets a new cache file
            val file = g.offline.fileFor(f.path).takeIf { it.exists() && it.lastModified() >= f.modified }
                ?: File(ctx.cacheDir, "pdf/$name").also { if (!it.exists()) g.nextcloud.download(f.path, it) }
            renderer = withContext(Dispatchers.IO) {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open PDF"
        }
    }
    // Close only once no page is being rendered (closing with a page open throws).
    DisposableEffect(renderer) {
        val r = renderer
        onDispose {
            if (r != null) CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                lock.withLockCompat { closed.add(r); r.close() }
            }
        }
    }
    val r = renderer
    when {
        error != null -> Text(error!!, Modifier.padding(24.dp))
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { (maxWidth - 24.dp).roundToPx() }.coerceIn(320, 1600)
            LazyColumn(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(r.pageCount) { i ->
                    var bmp by remember(i) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i) {
                        bmp = withContext(Dispatchers.IO) {
                            lock.withLockCompat {
                                if (r in closed) return@withLockCompat null
                                r.openPage(i).use { p ->
                                    val scale = widthPx.toFloat() / p.width
                                    Bitmap.createBitmap(widthPx, (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(android.graphics.Color.WHITE)
                                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }
                        }
                    }
                    val b = bmp
                    if (b == null) Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White))
                    else Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height))
                }
            }
        }
    }
}

private suspend fun <T> Mutex.withLockCompat(block: () -> T): T {
    // A plain lock/unlock pair: block isn't suspending, so this is withLock without the inline lambda rules.
    lock()
    try { return block() } finally { unlock() }
}
