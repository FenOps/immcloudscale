package app.immcloudscale.ui.account

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.backup.BackupWorker
import app.immcloudscale.data.backup.LocalMediaSource
import app.immcloudscale.graph
import app.immcloudscale.ui.components.SectionHeader
import app.immcloudscale.ui.components.formatAgo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val mediaPermissions = if (Build.VERSION.SDK_INT >= 33)
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

/** Google Photos' "Backup" settings page, driving the Immich backup worker. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    val work by remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(BackupWorker.NOW) }.collectAsStateWithLifecycle(emptyList())
    val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val failed = work.firstOrNull { it.state == WorkInfo.State.FAILED }?.outputData?.getString(BackupWorker.KEY_ERROR)

    fun update(block: (AppSettings) -> AppSettings) = scope.launch { g.settings.update(block) }
    suspend fun loadFolders() { folders = withContext(Dispatchers.IO) { runCatching { LocalMediaSource.folders(ctx) }.getOrDefault(emptyList()) } }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.entries.filter { it.key != Manifest.permission.POST_NOTIFICATIONS }.all { it.value }) {
            update { it.copy(backupEnabled = true) }
            scope.launch { loadFolders() }
        }
    }
    LaunchedEffect(Unit) { loadFolders() }

    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text("Backup") },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            Surface(
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(4.dp)) {
                    ListItem(
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        headlineContent = { Text("Back up to Immich", style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Text(
                                if (!s.immichConfigured) "Connect your Immich server first"
                                else if (s.backupEnabled) "Last backed up ${formatAgo(s.lastBackupAt)}"
                                else "Keep this phone's photos and videos safe on your NAS",
                            )
                        },
                        trailingContent = {
                            Switch(s.backupEnabled, enabled = s.immichConfigured, onCheckedChange = { on ->
                                if (on) perms.launch(mediaPermissions) else update { it.copy(backupEnabled = false) }
                            })
                        },
                    )
                    if (running != null) {
                        val p = running.progress
                        val total = p.getInt(BackupWorker.KEY_TOTAL, 0)
                        val done = p.getInt(BackupWorker.KEY_DONE, 0)
                        Text(if (total > 0) "Backing up $done of $total" else "Preparing backup…", Modifier.padding(horizontal = 16.dp))
                        if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(16.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    } else if (s.backupEnabled) {
                        Button(onClick = { BackupWorker.runNow(ctx, s) }, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)) {
                            Icon(Icons.Outlined.CloudUpload, null); Text("  Back up now")
                        }
                    }
                    failed?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                }
            }

            SectionHeader("When to back up")
            Toggle(Icons.Outlined.Wifi, "Only on Wi-Fi", "Don't use mobile data", s.backupWifiOnly) { v -> update { it.copy(backupWifiOnly = v) } }
            Toggle(Icons.Outlined.BatteryChargingFull, "Only while charging", "Save battery", s.backupChargingOnly) { v -> update { it.copy(backupChargingOnly = v) } }
            Toggle(Icons.Outlined.Videocam, "Back up videos", "Include videos as well as photos", s.backupVideos) { v -> update { it.copy(backupVideos = v) } }

            SectionHeader("Device folders")
            Text(
                if (s.backupFolders.isEmpty()) "All folders are backed up. Pick folders to back up only those."
                else "Backing up ${s.backupFolders.size} folder${if (s.backupFolders.size == 1) "" else "s"}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { f ->
                    FilterChip(
                        selected = f in s.backupFolders,
                        onClick = { update { it.copy(backupFolders = if (f in it.backupFolders) it.backupFolders - f else it.backupFolders + f) } },
                        label = { Text(f) },
                    )
                }
                if (folders.isEmpty()) Text("Allow access to photos to see your folders", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ListItem(
                modifier = Modifier.clickable { update { it.copy(backupSince = 0) } },
                headlineContent = { Text("Re-check everything") },
                supportingContent = { Text("Compare all photos on this phone with the server again. Already-uploaded ones are skipped.") },
            )
        }
    }
}

@Composable
private fun Toggle(icon: ImageVector, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked, onChange) },
    )
}
