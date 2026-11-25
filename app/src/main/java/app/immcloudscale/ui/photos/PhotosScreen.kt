package app.immcloudscale.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.immcloudscale.data.backup.BackupWorker
import app.immcloudscale.data.immich.AssetQuery
import app.immcloudscale.data.immich.MemoryDto
import app.immcloudscale.graph
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.Pinwheel
import app.immcloudscale.ui.components.RemoteImage
import java.time.LocalDate

enum class BackupIndicator { Off, Done, Running }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosTopBar(
    accountName: String,
    backup: BackupIndicator,
    backupProgress: Float?,
    onSearch: () -> Unit,
    onCreate: () -> Unit,
    onBackup: () -> Unit,
    onAccount: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pinwheel(26.dp)
                Spacer(Modifier.width(10.dp))
                Text("Photos", fontSize = 22.sp, fontWeight = FontWeight.Normal)
            }
        },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search") }
            IconButton(onClick = onCreate) { Icon(Icons.Outlined.Add, "Create") }
            IconButton(onClick = onBackup) {
                when (backup) {
                    BackupIndicator.Off -> Icon(Icons.Outlined.CloudOff, "Backup is off")
                    BackupIndicator.Done -> Icon(Icons.Outlined.CloudDone, "Backup complete")
                    BackupIndicator.Running -> Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { backupProgress ?: 0f }, modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
                        Icon(Icons.Outlined.CloudUpload, "Backing up", Modifier.size(16.dp))
                    }
                }
            }
            IconButton(onClick = onAccount) { LetterAvatar(accountName, 32.dp) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** "Memories" row at the top of the timeline: tall rounded cards, one per year. */
@Composable
fun MemoriesRow(memories: List<MemoryDto>, thumb: (String) -> String?, onOpen: (MemoryDto) -> Unit) {
    val year = LocalDate.now().year
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(memories, key = { it.id }) { m ->
            val cover = m.assets.first()
            Box(Modifier.width(112.dp).height(168.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpen(m) }) {
                RemoteImage(thumb(cover.id), cover.id, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxWidth().height(72.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))),
                )
                val ago = year - m.data.year
                Text(
                    if (ago <= 1) "1 year ago" else "$ago years ago",
                    color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
            }
        }
    }
}

@Composable
fun PhotosScreen(
    onOpenViewer: () -> Unit,
    onSearch: () -> Unit,
    onCreate: () -> Unit,
    onAccount: () -> Unit,
    onBackupSettings: () -> Unit,
    onSetup: () -> Unit,
    bottomPadding: PaddingValues,
) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings by g.settingsState.collectAsStateWithLifecycle()
    // Backup progress only matters once backup is on; don't start WorkManager before that.
    val work by androidx.compose.runtime.remember(settings.backupEnabled) {
        if (settings.backupEnabled) WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(BackupWorker.NOW)
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING }
    val progress = running?.progress?.let { p ->
        val t = p.getInt(BackupWorker.KEY_TOTAL, 0); if (t > 0) p.getInt(BackupWorker.KEY_DONE, 0).toFloat() / t else null
    }
    val indicator = when {
        running != null -> BackupIndicator.Running
        settings.backupEnabled -> BackupIndicator.Done
        else -> BackupIndicator.Off
    }
    val topBar: @Composable () -> Unit = {
        PhotosTopBar(
            settings.immichUserName.ifBlank { settings.ncUser.ifBlank { "Me" } }, indicator, progress,
            onSearch, onCreate, onBackupSettings, onAccount,
        )
    }

    if (!settings.immichConfigured) {
        androidx.compose.material3.Scaffold(topBar = topBar) { pad ->
            Column(Modifier.padding(pad)) {
                EmptyState(
                    Icons.Outlined.PhotoLibrary, "Connect your photo library",
                    "Sign in to your Immich server to see your photos and back up this phone.",
                    action = "Connect Immich", onAction = onSetup,
                )
            }
        }
        return
    }

    val vm = app.immcloudscale.ui.components.graphViewModel("timeline") { AssetListViewModel(it, AssetQuery.Timeline) }
    val st by vm.state.collectAsStateWithLifecycle()
    AssetBrowser(
        vm = vm,
        onOpenViewer = onOpenViewer,
        topBar = topBar,
        bottomPadding = bottomPadding,
        header = if (st.memories.isEmpty()) null else ({
            item(key = "memories", span = { GridItemSpan(maxLineSpan) }) {
                MemoriesRow(st.memories, { g.immich.thumbnailUrl(it, preview = true) }) { m ->
                    ViewerSession.open(m.assets, m.assets.first(), AssetQuery.Timeline) { upd, removed -> vm.applyEdit(upd, removed) }
                    onOpenViewer()
                }
            }
        }),
    )
}
