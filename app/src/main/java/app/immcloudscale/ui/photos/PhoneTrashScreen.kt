package app.immcloudscale.ui.photos

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

/** Whole days until [expires] (epoch seconds), rounded up: "1 day" until the very end. Null when unknown. */
fun daysLeft(expires: Long, now: Long = System.currentTimeMillis()): Long? =
    if (expires <= 0) null else ((expires * 1000 - now + 86_399_999L) / 86_400_000L).coerceAtLeast(1)

/**
 * The phone's own trash (Android 11+): photos and videos moved there, by this app or any other that uses Android's
 * trash, until Android deletes them for good after 30 days. They can be restored, or deleted for good now.
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
    var confirmForever by remember { mutableStateOf<List<LocalMedia>?>(null) } // what the dialog is about, fixed when it opened
    var loadFailed by remember { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<Pair<String, List<String>>?>(null) } // "restore" | "delete", keys

    // Without access to photos, Android only lists this app's own items: the trash would look empty when it isn't.
    val perms = mediaPermissions()
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(perms.any(::granted)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        allowed = r.values.any { it }
        val activity = ctx.findActivity()
        denied = !allowed && activity != null && perms.none { activity.shouldShowRequestPermissionRationale(it) }
    }

    fun load() = scope.launch {
        val found = withContext(Dispatchers.IO) { runCatching { LocalMediaSource.trashed(ctx) }.getOrNull() }
        loadFailed = found == null
        if (found == null) return@launch // not knowing isn't the same as empty: keep what's shown
        val keys = found.mapTo(HashSet()) { it.first.key() }
        items = found
        selected = selected intersect keys
        // Restored in another app, or gone for good: nothing to hide any more on the other screens.
        PhoneTrashed.ids.update { it intersect keys }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = pending
        pending = null
        if (r.resultCode == Activity.RESULT_OK && p != null) {
            selected = emptySet()
            if (p.first == "restore") PhoneTrashed.restore(p.second) // back on the phone: shown again elsewhere
            val n = p.second.size
            scope.launch {
                snack.showSnackbar(
                    if (p.first == "restore") (if (n == 1) "Restored to your phone" else "$n restored to your phone")
                    else (if (n == 1) "Deleted for good" else "$n deleted for good"),
                )
            }
        }
        load()
    }
    fun ask(kind: String, chosen: List<LocalMedia>) {
        if (chosen.isEmpty() || pending != null) return // nothing picked, or already asking
        pending = kind to chosen.map { it.key() }
        scope.launch {
            val uris = chosen.map { it.uri }
            val sender = withContext(Dispatchers.IO) {
                runCatching { if (kind == "restore") PhoneCopies.restoreRequest(ctx, uris) else PhoneCopies.deleteRequest(ctx, uris) }.getOrNull()
            }
            if (sender == null) {
                pending = null
                snack.showSnackbar("Couldn't do that on this phone")
            } else launcher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }
    LifecycleResumeEffect(Unit) {
        allowed = perms.any(::granted)
        if (allowed) load()
        onPauseOrDispose {}
    }
    LaunchedEffect(allowed) { if (allowed) load() }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    val list = items
    val chosen = list.orEmpty().map { it.first }.filter { it.key() in selected }
    val assets = remember(list) { list.orEmpty().map { it.first.toAsset() } }
    val sections = remember(assets) { groupByDay(assets) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (chosen.isEmpty()) "Phone trash" else "${chosen.size} selected") },
                navigationIcon = {
                    if (chosen.isEmpty()) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear selection") }
                },
                actions = {
                    when {
                        chosen.isNotEmpty() -> {
                            TextButton(onClick = { ask("restore", chosen) }) { Text("Restore") }
                            TextButton(onClick = { confirmForever = chosen }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text("Delete")
                            }
                        }
                        !list.isNullOrEmpty() -> TextButton(onClick = { selected = list.mapTo(HashSet()) { it.first.key() } }) { Text("Select all") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when {
                !canTrashOnPhone -> EmptyState(Icons.Outlined.DeleteOutline, "No trash on this phone", "Phones with Android 11 or newer keep deleted photos in a trash for 30 days.")
                !allowed -> EmptyState(
                    Icons.Outlined.PhotoLibrary, "Allow access to your photos",
                    "To show what's in your phone's trash, the app needs to see the photos and videos on this phone." +
                        if (denied) " Android won't ask again: allow it in Settings, Permissions, Photos and videos." else "",
                    action = if (denied) "Open settings" else "Allow",
                    onAction = {
                        if (denied) runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }
                        else askAccess.launch(perms)
                    },
                )
                list == null && loadFailed -> EmptyState(Icons.Outlined.DeleteOutline, "Couldn't open the phone's trash", "Android didn't answer. Go back and try again.")
                list == null -> CenteredLoading()
                list.isEmpty() -> EmptyState(Icons.Outlined.DeleteOutline, "Phone trash is empty", "Photos and videos moved to Android's trash stay here for 30 days.")
                else -> {
                    val next = list.firstNotNullOfOrNull { daysLeft(it.second) }
                    Text(
                        "Items are deleted for good 30 days after they're moved here." +
                            (next?.let { " The next one goes in $it day${if (it == 1L) "" else "s"}." } ?: ""),
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
                                val inOrder = sections.flatMap { it.assets } // swipes the way the grid reads
                                LocalViewerSession.items = inOrder
                                LocalViewerSession.start = inOrder.indexOfFirst { it.id == a.id }.coerceAtLeast(0)
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
    confirmForever?.let { these ->
        ConfirmDeleteForever(these.size, onDismiss = { confirmForever = null }) {
            confirmForever = null
            ask("delete", these)
        }
    }
}

/** Deleting from the phone's trash can't be undone: asks in plain words first. */
@Composable
fun ConfirmDeleteForever(n: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (n == 1) "Delete for good?" else "Delete $n items for good?") },
        text = { Text("${if (n == 1) "It's" else "They're"} removed from your phone now and can't be restored. Anything that isn't backed up is gone for good.") },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete for good") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
