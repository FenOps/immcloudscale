package app.immcloudscale.ui.photos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.immcloudscale.data.immich.AlbumDto
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.graph
import app.immcloudscale.ui.components.RemoteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Hand-off between a grid and the full-screen viewer. */
object ViewerSession {
    var assets: List<AssetDto> = emptyList()
    var start: Int = 0
    var query: AssetQuery = AssetQuery.Timeline
    var onEdit: ((updated: AssetDto?, removedId: String?) -> Unit)? = null

    fun open(list: List<AssetDto>, asset: AssetDto, q: AssetQuery, edit: (AssetDto?, String?) -> Unit) {
        assets = list; start = list.indexOfFirst { it.id == asset.id }.coerceAtLeast(0); query = q; onEdit = edit
    }
}

fun shareText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

/** Downloads the originals to the cache and opens the system share sheet with them. */
suspend fun shareAssets(context: Context, assets: List<AssetDto>) {
    val g = context.graph
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val files = withContext(Dispatchers.IO) {
        assets.map { a ->
            // One folder per asset: two photos can share a name (IMG_0001.JPG), and must never be mixed up.
            val safeName = a.originalFileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_")
                .trim('.', ' ').ifBlank { a.id }
            val f = File(File(dir, a.id.replace(Regex("[^A-Za-z0-9-]"), "_")), safeName)
            check(f.canonicalPath.startsWith(dir.canonicalPath + File.separator))
            if (!f.exists()) {
                f.parentFile?.mkdirs()
                val part = File(f.path + ".part")
                g.http.newCall(Request.Builder().url(g.immich.originalUrl(a.id)).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Couldn't download ${a.originalFileName} (HTTP ${r.code})")
                    r.body!!.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                }
                if (!part.renameTo(f)) throw IOException("Couldn't save ${a.originalFileName}")
            }
            f
        }
    }
    val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = if (assets.all { it.isVideo }) "video/*" else if (assets.none { it.isVideo }) "image/*" else "*/*"
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}

/** Contextual top bar shown while photos are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    query: AssetQuery,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onLink: () -> Unit,
    onAddToAlbum: () -> Unit,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Clear selection") } },
        title = { Text("$count") },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        actions = {
            if (query == AssetQuery.Trash) {
                IconButton(onClick = onRestore) { Icon(Icons.AutoMirrored.Outlined.Undo, "Restore") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteForever, "Delete forever") }
            } else {
                IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, "Share") }
                IconButton(onClick = onLink) { Icon(Icons.Outlined.Link, "Create link") }
                IconButton(onClick = onAddToAlbum) { Icon(Icons.Outlined.Add, "Add to album") }
                IconButton(onClick = onFavorite) { Icon(Icons.Outlined.FavoriteBorder, "Favorite") }
                IconButton(onClick = onArchive) {
                    if (query == AssetQuery.Archive) Icon(Icons.Outlined.Unarchive, "Unarchive") else Icon(Icons.Outlined.Archive, "Archive")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Move to trash") }
            }
        },
    )
}

/** "Add to" dialog: pick an existing album or create a new one. */
@Composable
fun AlbumPickerDialog(onDismiss: () -> Unit, onPick: (AlbumDto) -> Unit, onCreate: (String) -> Unit) {
    val g = LocalContext.current.graph
    var albums by remember { mutableStateOf<List<AlbumDto>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { albums = runCatching { g.immich.albums() }.getOrDefault(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "New album" else "Add to album") },
        text = {
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("Album title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Text("New album", Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    items(albums.orEmpty(), key = { it.id }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            RemoteImage(
                                a.albumThumbnailAssetId?.let { g.immich.thumbnailUrl(it) }, a.id,
                                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
                            )
                            Column(Modifier.padding(start = 16.dp)) {
                                Text(a.albumName, style = MaterialTheme.typography.bodyLarge)
                                Text("${a.assetCount} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
