package app.immcloudscale.ui.files

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.nextcloud.CloudFile
import app.immcloudscale.graph
import app.immcloudscale.ui.components.formatBytes
import app.immcloudscale.ui.photos.VideoPage
import app.immcloudscale.ui.photos.ZoomableImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

/** Renders PDF pages with Android's PdfRenderer after downloading the file to the cache. */
@Composable
private fun PdfPreview(f: CloudFile) {
    val ctx = LocalContext.current
    val g = ctx.graph
    var pages by remember { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(f.path) {
        try {
            val file = g.offline.fileFor(f.path).takeIf { it.exists() } ?: File(ctx.cacheDir, "pdf/${f.fileId ?: f.name.hashCode()}.pdf").also {
                if (!it.exists()) g.nextcloud.download(f.path, it)
            }
            pages = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        (0 until minOf(r.pageCount, 50)).map { i ->
                            r.openPage(i).use { p ->
                                val scale = 1440f / p.width
                                val bmp = Bitmap.createBitmap(1440, (p.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open PDF"
        }
    }
    val list = pages
    when {
        error != null -> Text(error!!, Modifier.padding(24.dp))
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(list) { i, bmp ->
                Image(bmp.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height))
            }
        }
    }
}
