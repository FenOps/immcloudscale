package app.immcloudscale.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.data.immich.DuplicateGroup
import app.immcloudscale.graph
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.RemoteImage
import app.immcloudscale.ui.components.formatBytes
import app.immcloudscale.ui.photos.rememberPhoneTrash
import kotlinx.coroutines.launch

/** The copy to keep: the biggest picture (most pixels, then the largest file). */
fun bestOf(group: List<AssetDto>): AssetDto = group.maxWith(
    compareBy<AssetDto>({ (it.exifInfo?.exifImageWidth ?: 0).toLong() * (it.exifInfo?.exifImageHeight ?: 0) }, { it.exifInfo?.fileSizeInByte ?: 0 }),
)

class DuplicatesActions(
    val onKeepBest: (DuplicateGroup) -> Unit = {},
    val onKeepBestAll: () -> Unit = {},
    val onNotDuplicates: (DuplicateGroup) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Duplicates Immich found: keep the best copy of each and move the rest to the trash (after asking). */
@Composable
fun DuplicatesScreen(onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val phoneTrash = rememberPhoneTrash()
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<List<DuplicateGroup>?>(null) }

    suspend fun load() {
        runCatching { g.immich.duplicates().filter { it.assets.size > 1 } }
            .onSuccess { groups = it; error = null }
            .onFailure { error = it.message ?: "Couldn't load duplicates" }
    }
    LaunchedEffect(Unit) { load() }

    DuplicatesContent(
        groups, error, snack,
        DuplicatesActions(
            onKeepBest = { confirm = listOf(it) },
            onKeepBestAll = { confirm = groups },
            onNotDuplicates = { grp -> scope.launch { runCatching { g.immich.dismissDuplicates(grp.duplicateId) }; load() } },
            onBack = onBack,
        ),
    )

    confirm?.let { chosen ->
        val extra = chosen.flatMap { grp -> grp.assets - bestOf(grp.assets) }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Move ${extra.size} duplicate${if (extra.size == 1) "" else "s"} to trash?") },
            text = {
                Text(
                    "The best copy of each photo is kept (marked with a tick). The others leave your library and this phone; " +
                        "you can restore them from Trash for 30 days. That frees ${formatBytes(extra.sumOf { it.exifInfo?.fileSizeInByte ?: 0 })}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        runCatching { g.immich.delete(extra.map { it.id }) }
                            .onSuccess { phoneTrash(extra); snack.showSnackbar("Moved ${extra.size} to trash") }
                            .onFailure { snack.showSnackbar(it.message ?: "Couldn't move them") }
                        load()
                    }
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesContent(groups: List<DuplicateGroup>?, error: String?, snack: SnackbarHostState, a: DuplicatesActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicates") },
                navigationIcon = { IconButton(onClick = a.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (!groups.isNullOrEmpty()) TextButton(onClick = a.onKeepBestAll) { Text("Clean up all") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                groups == null && error == null -> CenteredLoading()
                groups == null -> EmptyState(
                    Icons.Outlined.ContentCopy, "Duplicates aren't available",
                    "Finding duplicates needs face recognition and smart search switched on for your server (an admin can do it in Account → Members). $error",
                )
                groups.isEmpty() -> EmptyState(Icons.Outlined.ContentCopy, "No duplicates", "Nothing to clean up. New duplicates show up here after backups.")
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Text(
                            "The copy marked Best is kept; the others go to Trash, where you can restore them for 30 days.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(groups, key = { it.duplicateId }) { grp -> DuplicateRow(grp, a) }
                }
            }
        }
    }
}

@Composable
private fun DuplicateRow(grp: DuplicateGroup, a: DuplicatesActions) {
    val g = LocalContext.current.graph
    val best = bestOf(grp.assets)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(grp.assets, key = { it.id }) { asset ->
                val keep = asset.id == best.id
                Box(
                    Modifier.size(120.dp).clip(RoundedCornerShape(12.dp))
                        .then(if (keep) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier),
                ) {
                    RemoteImage(g.immich.thumbnailUrl(asset.id), asset.id, Modifier.fillMaxSize(), contentDescription = if (keep) "Kept copy" else "Duplicate")
                    if (keep) Row(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Best", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.fillMaxWidth().height(32.dp).align(Alignment.BottomStart).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))))
                    Text(
                        formatBytes(asset.exifInfo?.fileSizeInByte ?: 0), style = MaterialTheme.typography.labelSmall,
                        color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    )
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.onKeepBest(grp) }) { Text("Keep best, trash ${grp.assets.size - 1}") }
            TextButton(onClick = { a.onNotDuplicates(grp) }) { Text("Not duplicates") }
        }
    }
}
