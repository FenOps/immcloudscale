package app.immcloudscale.ui.photos

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.backup.LocalMedia
import app.immcloudscale.data.backup.LocalMediaSource
import app.immcloudscale.data.backup.PhoneCopies
import app.immcloudscale.data.immich.groupByDay
import app.immcloudscale.graph
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's own trash (Android 11+): photos and videos moved there, by this app or any other, until Android
 * deletes them for good after 30 days. They can be restored, or deleted for good now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneTrashScreen(onBack: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val settings by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<Pair<LocalMedia, Long>>?>(null) }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmForever by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<Pair<String, List<String>>?>(null) } // "restore" | "delete", keys
    fun load() = scope.launch { items = withContext(Dispatchers.IO) { runCatching { LocalMediaSource.trashed(ctx) }.getOrDefault(emptyList()) } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = pending
        pending = null
        if (r.resultCode == Activity.RESULT_OK && p != null) {
            selected = emptySet()
            if (p.first == "restore") PhoneTrashed.ids.update { it - p.second.toSet() } // back on the phone: shown again elsewhere
            scope.launch { snack.showSnackbar(if (p.first == "restore") "Restored to your phone" else "Deleted for good") }
        }
        load()
    }
    fun ask(kind: String, chosen: List<LocalMedia>) = scope.launch {
        val uris = chosen.map { it.uri }
        val sender = withContext(Dispatchers.IO) {
            runCatching { if (kind == "restore") PhoneCopies.restoreRequest(ctx, uris) else PhoneCopies.deleteRequest(ctx, uris) }.getOrNull()
        }
        if (sender == null) snack.showSnackbar("Couldn't do that on this phone")
        else {
            pending = kind to chosen.map { it.key() }
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }
    LifecycleResumeEffect(Unit) {
        load()
        onPauseOrDispose {}
    }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    val list = items
    val chosen = list.orEmpty().map { it.first }.filter { it.key() in selected }
    val assets = remember(list) { list.orEmpty().map { it.first.toAsset() } }
    val sections = remember(assets) { groupByDay(assets) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Phone trash" else "${selected.size} selected") },
                navigationIcon = {
                    if (selected.isEmpty()) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { ask("restore", chosen) }) { Icon(Icons.Outlined.RestoreFromTrash, "Restore") }
                        IconButton(onClick = { confirmForever = true }) { Icon(Icons.Outlined.DeleteForever, "Delete for good") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                !canTrashOnPhone -> EmptyState(Icons.Outlined.DeleteOutline, "No trash on this phone", "Phones with Android 11 or newer keep deleted photos in a trash for 30 days.")
                list == null -> CenteredLoading()
                list.isEmpty() -> EmptyState(Icons.Outlined.DeleteOutline, "The phone's trash is empty", "Photos and videos deleted on this phone stay here for 30 days.")
                else -> {
                    val soonest = list.first().second
                    val days = ((soonest * 1000 - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                    Text(
                        "Android deletes each item for good 30 days after it was put here (the first one in $days day${if (days == 1L) "" else "s"}). " +
                            "Long-press to restore or delete.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    AssetGrid(
                        sections = sections,
                        columns = settings.photoColumns,
                        selected = selected,
                        thumbUrl = { it.localUri },
                        onOpen = { a ->
                            if (selected.isNotEmpty()) selected = if (a.id in selected) selected - a.id else selected + a.id
                            else {
                                LocalViewerSession.items = assets
                                LocalViewerSession.start = assets.indexOfFirst { it.id == a.id }.coerceAtLeast(0)
                                LocalViewerSession.inTrash = true
                                onOpen()
                            }
                        },
                        onToggle = { a -> selected = if (a.id in selected) selected - a.id else selected + a.id },
                        onSelectDay = { day ->
                            val ids = day.assets.map { it.id }
                            selected = if (ids.all { it in selected }) selected - ids.toSet() else selected + ids
                        },
                        onColumnsChange = { n -> scope.launch { g.settings.update { it.copy(photoColumns = n) } } },
                        selectLocal = true,
                    )
                }
            }
        }
    }
    if (confirmForever) {
        val n = chosen.size
        AlertDialog(
            onDismissRequest = { confirmForever = false },
            title = { Text(if (n == 1) "Delete for good?" else "Delete $n items for good?") },
            text = { Text("They're removed from your phone now and can't be restored. Anything not backed up is gone for good.") },
            confirmButton = {
                TextButton(onClick = { confirmForever = false; ask("delete", chosen) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete for good")
                }
            },
            dismissButton = { TextButton(onClick = { confirmForever = false }) { Text("Cancel") } },
        )
    }
}
