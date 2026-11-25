package app.immcloudscale.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.nextcloud.CloudFile
import app.immcloudscale.graph
import app.immcloudscale.ui.components.RemoteImage

/** Drive's item bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionsSheet(
    f: CloudFile,
    offline: Boolean,
    previewUrl: String?,
    onDismiss: () -> Unit,
    onShareLink: () -> Unit,
    onStar: () -> Unit,
    onOffline: () -> Unit,
    onOpenWith: () -> Unit,
    onSendCopy: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (previewUrl != null) RemoteImage(previewUrl, f.path, Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)))
                else FileIcon(f, 32.dp)
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            fun go(block: () -> Unit) = { onDismiss(); block() }
            SheetRow(Icons.Outlined.Link, "Copy link", go(onShareLink))
            SheetRow(if (f.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, if (f.favorite) "Remove from Starred" else "Add to Starred", go(onStar))
            if (!f.isFolder) {
                ListItem(
                    modifier = Modifier.clickable(onClick = go(onOffline)),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.OfflinePin, null) },
                    headlineContent = { Text("Available offline") },
                    trailingContent = { Switch(offline, { onDismiss(); onOffline() }) },
                )
                SheetRow(Icons.AutoMirrored.Outlined.OpenInNew, "Open with", go(onOpenWith))
                SheetRow(Icons.AutoMirrored.Outlined.Send, "Send a copy", go(onSendCopy))
            }
            SheetRow(Icons.Outlined.DriveFileRenameOutline, "Rename", go(onRename))
            SheetRow(Icons.AutoMirrored.Outlined.DriveFileMove, "Move", go(onMove))
            if (!f.isFolder) SheetRow(Icons.Outlined.ContentCopy, "Make a copy", go(onCopy))
            SheetRow(Icons.Outlined.Info, "Details", go(onDetails))
            SheetRow(Icons.Outlined.DeleteOutline, "Remove", go(onDelete), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Color.Unspecified) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint) },
        headlineContent = { Text(label, color = tint) },
    )
}

/** Browse folders to pick a destination for move / copy. */
@Composable
fun FolderPickerDialog(title: String, start: String, exclude: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val g = LocalContext.current.graph
    var path by remember { mutableStateOf(start) }
    var folders by remember { mutableStateOf<List<CloudFile>?>(null) }
    LaunchedEffect(path) {
        folders = null
        folders = runCatching { g.nextcloud.list(path).filter { it.isFolder && it.path != exclude } }.getOrDefault(emptyList())
            .sortedBy { it.name.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (path != "/") IconButton(onClick = { path = path.substringBeforeLast('/', "").ifEmpty { "/" } }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                    }
                    Text(path.substringAfterLast('/').ifEmpty { "My Files" }, style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        text = {
            Box(Modifier.heightIn(min = 120.dp, max = 360.dp)) {
                val list = folders
                if (list == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else if (list.isEmpty()) Text("No folders here", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
                else LazyColumn {
                    items(list, key = { it.path }) { f ->
                        ListItem(
                            modifier = Modifier.fillMaxWidth().clickable { path = f.path },
                            leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            headlineContent = { Text(f.name) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(path) }) { Text(if (title.startsWith("Copy")) "Copy here" else "Move here") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
