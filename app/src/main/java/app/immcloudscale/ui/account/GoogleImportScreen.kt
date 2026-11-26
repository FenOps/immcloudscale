package app.immcloudscale.ui.account

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.SignInCatcher
import app.immcloudscale.data.pairing.GoogleAccount
import app.immcloudscale.data.pairing.GoogleStatus
import app.immcloudscale.data.pairing.TakeoutImport
import app.immcloudscale.graph
import app.immcloudscale.ui.components.SectionHeader
import app.immcloudscale.ui.components.formatAgo
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ticked-off steps of "Move to your own NAS", kept in [AppSettings.googleDone]. */
object GoogleSteps {
    const val STARTED = "started" // something was connected or imported: the checklist and reminders apply
    const val PHOTOS_BACKUP = "photos-backup"
    const val DRIVE_DESKTOP = "drive-desktop"
    const val CHECKED = "checked"
    const val NO_REMINDERS = "no-reminders"
}

class GoogleActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenBackup: () -> Unit = {},
    val onSaveKey: (String, String) -> Unit = { _, _ -> },
    val onConnect: () -> Unit = {},
    val onSync: (GoogleAccount) -> Unit = {},
    val onRemove: (GoogleAccount) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onOpenGooglePhotos: () -> Unit = {},
    val onStep: (String, Boolean) -> Unit = { _, _ -> },
)

/** Moving from Google: Drive copied and kept in sync, Google Photos imported from Takeout, then a checklist. */
@Composable
fun GoogleImportScreen(onBack: () -> Unit, onOpenBackup: () -> Unit = {}) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var status by remember { mutableStateOf<GoogleStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by rememberSaveable { mutableStateOf(false) } // survives a trip to the browser
    var removing by remember { mutableStateOf<GoogleAccount?>(null) }

    suspend fun load() {
        runCatching { g.members.google() }
            .onSuccess { st ->
                status = st
                error = null
                if ((st.accounts.isNotEmpty() || st.imports.isNotEmpty()) && GoogleSteps.STARTED !in s.googleDone) {
                    g.settings.update { it.copy(googleDone = it.googleDone + GoogleSteps.STARTED) }
                }
            }
            .onFailure { error = it.message ?: "Couldn't reach your server" }
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { snack.showSnackbar(it.message ?: "Something went wrong") }
        load()
    }
    // While something runs on the server, keep the page current.
    LaunchedEffect(Unit) {
        while (true) {
            load()
            val busy = status?.let { st -> st.toolsBuilding || st.importing || st.accounts.any { it.syncing } } == true
            delay(if (busy) 5_000 else 30_000)
        }
    }

    GoogleImportContent(
        status, error, s, snack,
        GoogleActions(
            onBack = onBack,
            onRetry = { scope.launch { load() } },
            onOpenBackup = onOpenBackup,
            onSaveKey = { id, secret -> act { g.members.setGoogleKey(id, secret); snack.showSnackbar("Google key saved") } },
            onConnect = { connecting = true },
            onSync = { a -> act { g.members.syncGoogle(a.id); snack.showSnackbar("Copying ${a.email} again") } },
            onRemove = { removing = it },
            onOpen = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onOpenGooglePhotos = { openGooglePhotos(ctx) },
            onStep = { step, done ->
                scope.launch { g.settings.update { it.copy(googleDone = if (done) it.googleDone + step else it.googleDone - step) } }
            },
        ),
    )

    if (connecting) ConnectGoogleDialog(
        isAdmin = s.isAdmin,
        onDismiss = { connecting = false },
        onConnected = { a ->
            connecting = false
            scope.launch { snack.showSnackbar("${a.email} connected: copying its Drive"); load() }
        },
    )
    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Disconnect ${a.email}?") },
            text = { Text("Its Drive stops being copied. What's already on your server stays in ${a.folder}.") },
            confirmButton = { TextButton(onClick = { removing = null; act { g.members.removeGoogle(a.id) } }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** Opens the Google Photos app (its backup switch is in its settings), or its website when it isn't installed. */
private fun openGooglePhotos(ctx: Context) {
    val app = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
    runCatching { ctx.startActivity(app ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://photos.google.com/settings"))) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleImportContent(
    status: GoogleStatus?,
    error: String?,
    s: AppSettings,
    snack: SnackbarHostState = remember { SnackbarHostState() },
    a: GoogleActions = GoogleActions(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import from Google") },
                navigationIcon = { IconButton(onClick = a.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp)) {
            item {
                Text(
                    "Bring your Google Drive and Google Photos to your own server. Drive is copied and kept in sync; " +
                        "photos come from a Google Takeout export. Nothing is deleted from Google.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (status == null) {
                item {
                    if (error != null) {
                        Notice(Icons.Outlined.ErrorOutline, error, isError = true)
                        TextButton(onClick = a.onRetry, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Try again") }
                    } else Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                return@LazyColumn
            }
            if (!status.keySet) item { KeySetup(s.isAdmin, a) }
            if (status.toolsBuilding) item { Notice(null, "Getting the import tools ready on your server. This takes a few minutes, once.") }
            status.toolsError?.let { e -> item { Notice(Icons.Outlined.ErrorOutline, e, isError = true) } }

            item { SectionHeader("Google accounts") }
            if (status.accounts.isEmpty()) item {
                Text(
                    "Connect each Google account you want to move. Its Drive is copied into Files.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            status.accounts.forEach { acct -> item(key = acct.id) { AccountRow(acct, a) } }
            item {
                FilledTonalButton(onClick = a.onConnect, enabled = status.keySet, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Connect a Google account")
                }
                if (!status.keySet) Text(
                    "Available once the Google key is added above.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item { SectionHeader("Google Photos") }
            item { TakeoutSteps(status, a) }
            if (status.importing) item { Notice(null, "Importing photos. This can take a while for a big library.") }
            status.imports.forEach { im -> item(key = "im-" + im.export + im.at) { ImportRow(im) } }

            if (status.accounts.isNotEmpty() || status.imports.isNotEmpty() || GoogleSteps.STARTED in s.googleDone) {
                item { SectionHeader("Finish moving to your server") }
                item { Checklist(s, status, a) }
            }
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String, isError: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(icon, null, tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

/** Numbered steps, one per line. */
@Composable
private fun Steps(vararg steps: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            Row {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun KeySetup(isAdmin: Boolean, a: GoogleActions) {
    var id by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("First, a Google API key (once)", style = MaterialTheme.typography.titleMedium)
            }
            if (!isAdmin) {
                Text("Google asks every home server for its own key. Ask an admin of your server to add it here.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text("Google asks every home server for its own free key to read Drive. It takes about 10 minutes.", style = MaterialTheme.typography.bodyMedium)
            Steps(
                "Open Google Cloud and create a project (any name, e.g. ImmCloudScale).",
                "APIs & Services → Library: turn on \"Google Drive API\".",
                "Google Auth Platform → Branding: app name ImmCloudScale and your email. Audience: External, then Publish app.",
                "Clients → Create client → Desktop app. Copy the client ID and secret here.",
            )
            OutlinedButton(onClick = { a.onOpen("https://console.cloud.google.com/apis/credentials") }) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open Google Cloud")
            }
            OutlinedTextField(id, { id = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                secret, { secret = it }, label = { Text("Client secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(onClick = { a.onSaveKey(id, secret) }, enabled = id.isNotBlank() && secret.isNotBlank()) { Text("Save key") }
        }
    }
}

@Composable
private fun AccountRow(acct: GoogleAccount, a: GoogleActions) {
    val detail = when {
        acct.syncing -> "Copying Drive…"
        acct.error != null -> acct.error
        acct.synced != null -> "Drive: ${"%,d".format(acct.files)} files, ${humanBytes(acct.bytes)} · copied ${formatAgo(parseInstant(acct.synced))}"
        else -> "Drive: waiting to be copied"
    }
    ListItem(
        leadingContent = {
            if (acct.syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(if (acct.error != null) Icons.Outlined.ErrorOutline else Icons.Outlined.Folder, null,
                tint = if (acct.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(acct.email, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(detail, color = if (acct.error != null && !acct.syncing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("In Files: ${acct.folder}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Options for ${acct.email}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Copy now") }, enabled = !acct.syncing, leadingIcon = { Icon(Icons.Outlined.Sync, null) },
                    onClick = { menu = false; a.onSync(acct) },
                )
                DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; a.onRemove(acct) })
            }
        },
    )
}

@Composable
private fun TakeoutSteps(status: GoogleStatus, a: GoogleActions) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Export your photos from Google", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Google lets photos leave only through Google Takeout. Ask for an export once; your server does the rest.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Steps(
                "Open Google Takeout, signed in to the account to move.",
                "Deselect all, then select only Google Photos.",
                "Delivery method: Add to Drive. Frequency: Export once. File type .zip, size 50 GB.",
                "Create export. Google takes hours to days; when it lands in that account's Drive, your server imports it by itself.",
            )
            Text(
                "\"Add to Drive\" needs free space in that Google account as big as the photos. Not enough? Choose \"Send download link " +
                    "via email\" instead, download the files, and put them in Files → ${status.takeoutFolder}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { a.onOpen("https://takeout.google.com/") }) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open Google Takeout")
            }
            Text(
                "Delivered another way? Put the zip files in Files → ${status.takeoutFolder}. A folder inside it (e.g. \"work account\") names where they came from.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.accounts.isEmpty()) Text(
                "To import exports delivered to Drive, connect that Google account above.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportRow(im: TakeoutImport) {
    val ok = im.error == null && im.errors == 0
    ListItem(
        leadingContent = {
            Icon(if (ok) Icons.Outlined.CloudDownload else Icons.Outlined.ErrorOutline, null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        },
        headlineContent = { Text(if (ok) "${"%,d".format(im.imported)} photos imported" else "Import didn't finish") },
        supportingContent = {
            Column {
                Text(
                    buildString {
                        append("From ${im.source}")
                        if (im.duplicates > 0) append(" · ${"%,d".format(im.duplicates)} already on your server")
                        if (im.errors > 0) append(" · ${im.errors} couldn't be read")
                        if (im.at.isNotBlank()) append(" · ${formatAgo(parseInstant(im.at))}")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!ok) Text(im.error ?: "It's tried again tomorrow.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Composable
private fun Checklist(s: AppSettings, status: GoogleStatus, a: GoogleActions) {
    val done = s.googleDone
    Column(Modifier.padding(horizontal = 8.dp)) {
        Text(
            "Google doesn't let other apps switch off its backup, so these are for you to do. Ticked steps stay ticked.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Step(
            GoogleSteps.PHOTOS_BACKUP in done, "Turn off Google Photos backup",
            "In Google Photos: your profile picture → Photos settings → Backup → off. Do it on every phone that backs up to Google.",
            { a.onStep(GoogleSteps.PHOTOS_BACKUP, it) },
        ) { OutlinedButton(onClick = a.onOpenGooglePhotos) { Text("Open Google Photos") } }
        Step(
            GoogleSteps.DRIVE_DESKTOP in done, "Stop Google Drive on your computers",
            "Phones don't back up to Drive by themselves. If a computer runs Google Drive for desktop, quit it there or remove its folders.",
            { a.onStep(GoogleSteps.DRIVE_DESKTOP, it) },
        )
        val nasOn = s.backupEnabled
        ListItem(
            leadingContent = { Checkbox(nasOn, onCheckedChange = null, enabled = false) },
            headlineContent = { Text("This phone backs up to your server") },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (nasOn) "On (checked by the app)" else "Off")
                    if (!nasOn) OutlinedButton(onClick = a.onOpenBackup) { Text("Open Backup") }
                }
            },
        )
        val imported = status.imports.filter { it.error == null && it.errors == 0 }
        Step(
            GoogleSteps.CHECKED in done, "Check that everything arrived",
            buildString {
                append("Look through Photos and Files. ")
                if (imported.isNotEmpty()) append("Imported so far: ${"%,d".format(imported.sumOf { it.imported })} photos (${"%,d".format(imported.sumOf { it.duplicates })} were already here). ")
                status.accounts.filter { it.synced != null }.forEach { append("${it.email}: ${"%,d".format(it.files)} Drive files. ") }
                append("Compare with what Google shows.")
            },
            { a.onStep(GoogleSteps.CHECKED, it) },
        )
        if (GoogleSteps.PHOTOS_BACKUP in done && GoogleSteps.CHECKED in done) {
            Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    "You can now free up space in Google by deleting photos and files there. Deleting in Google is permanent, " +
                        "but nothing on your server is affected. Keep anything you're unsure about until you've found it here.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (GoogleSteps.PHOTOS_BACKUP !in done) {
            val muted = GoogleSteps.NO_REMINDERS in done
            TextButton(onClick = { a.onStep(GoogleSteps.NO_REMINDERS, !muted) }) {
                Text(if (muted) "Remind me every few days" else "Stop reminding me")
            }
        }
    }
}

@Composable
private fun Step(done: Boolean, title: String, body: String, onDone: (Boolean) -> Unit, action: (@Composable () -> Unit)? = null) {
    ListItem(
        modifier = Modifier.toggleable(value = done, role = Role.Checkbox, onValueChange = onDone),
        leadingContent = { Checkbox(done, onCheckedChange = null) },
        headlineContent = { Text(title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                if (!done) action?.invoke()
            }
        },
    )
}

/** Signing in to Google: open the sign-in page, then paste back the address it ends on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectGoogleDialog(isAdmin: Boolean, onDismiss: () -> Unit, onConnected: (GoogleAccount) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val me by g.settingsState.collectAsStateWithLifecycle()
    var people by remember { mutableStateOf<List<String>>(emptyList()) }
    var forPerson by rememberSaveable { mutableStateOf("") }
    var pasted by rememberSaveable { mutableStateOf("") }
    var opened by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isAdmin) {
        if (isAdmin) people = runCatching { g.members.members().map { it.name } }.getOrDefault(emptyList())
    }
    // The phone itself catches the end of the sign-in, so usually nothing needs pasting.
    val catcher = remember { SignInCatcher() }
    DisposableEffect(Unit) { onDispose { catcher.stop() } }
    fun finish(url: String) {
        busy = true
        scope.launch {
            runCatching { g.members.finishGoogle(url) }.onSuccess(onConnected).onFailure { error = it.message }
            busy = false
        }
    }
    LaunchedEffect(opened) {
        if (!opened) return@LaunchedEffect
        val caught = catcher.await() ?: return@LaunchedEffect
        pasted = caught
        finish(caught)
    }
    // Back from the browser with the address copied: fill it in.
    LifecycleResumeEffect(Unit) {
        if (pasted.isBlank()) {
            val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            if (looksDone(clip)) pasted = clip.trim()
        }
        onPauseOrDispose {}
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Connect a Google account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isAdmin && people.size > 1) {
                    var open by remember { mutableStateOf(false) }
                    val mine = me.ncUser.ifBlank { me.immichUserName }
                    ExposedDropdownMenuBox(open, { open = it }) {
                        OutlinedTextField(
                            forPerson.ifBlank { mine }, {}, readOnly = true, label = { Text("Import into") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        )
                        ExposedDropdownMenu(open, { open = false }) {
                            people.forEach { p -> DropdownMenuItem(text = { Text(if (p == mine) "$p (you)" else p) }, onClick = { forPerson = if (p == mine) "" else p; open = false }) }
                        }
                    }
                }
                Steps(
                    "Open Google's sign-in and choose the account.",
                    "Google may warn that the app isn't verified: it's your own key, so tap Advanced, then Go to ImmCloudScale. Allow access.",
                    "Then come back here: the account connects by itself. If the browser shows \"This site can't be reached\" " +
                        "instead, tap its address bar, tap Copy, and come back; the address fills in below.",
                )
                Button(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { g.members.connectGoogle(forPerson) }
                            .onSuccess { url -> opened = true; error = null; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                            .onFailure { error = it.message }
                        busy = false
                    }
                }, enabled = !busy) { Text(if (opened) "Open sign-in again" else "Open Google sign-in") }
                OutlinedTextField(
                    pasted, { pasted = it }, label = { Text("Address of the page that didn't load") },
                    placeholder = { Text("http://127.0.0.1:53682/?…") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    isError = pasted.isNotBlank() && !looksDone(pasted),
                    supportingText = {
                        if (pasted.isNotBlank() && !looksDone(pasted)) Text("That isn't the address yet: it starts with http://127.0.0.1 and contains code=")
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            pasted = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                        }) { Icon(Icons.Outlined.ContentPaste, "Paste") }
                    },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { finish(pasted) }, enabled = !busy && looksDone(pasted)) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/** The address Google's sign-in ends on, carrying the code. */
private fun looksDone(s: String) = s.contains("127.0.0.1") && s.contains("code=")

private fun parseInstant(s: String?): Long = runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)

private fun humanBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}
