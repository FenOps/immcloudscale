package app.immcloudscale.ui.collections

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.immcloudscale.AppGraph
import app.immcloudscale.data.HttpException
import app.immcloudscale.data.immich.AssetDto
import app.immcloudscale.graph
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.RemoteImage
import kotlinx.coroutines.launch

sealed interface LockedUi {
    data object Loading : LockedUi
    data class Problem(val message: String) : LockedUi
    /** No PIN yet: choose one. */
    data object NewPin : LockedUi
    data class EnterPin(val wrong: Boolean = false) : LockedUi
    data class Open(val assets: List<AssetDto>) : LockedUi
}

/** An Immich session for the locked folder, fetched from the server once (Immich won't open it for API keys). */
private suspend fun lockedSession(g: AppGraph, renew: Boolean = false): String {
    val saved = g.settingsState.value.lockedSession
    if (saved.isNotBlank() && !renew) return saved
    val s = g.members.session()
    g.settings.update { it.copy(lockedSession = s) }
    return s
}

/**
 * Immich's locked folder: photos hidden from the timeline, search, albums and other apps, opened with a PIN.
 * It locks again when you leave the screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LockedFolderScreen(onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<LockedUi>(LockedUi.Loading) }
    var session by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<AssetDto?>(null) }
    val snack = remember { SnackbarHostState() }

    suspend fun open() {
        ui = runCatching {
            // Every page, not only the first 200.
            val all = mutableListOf<AssetDto>()
            var page = 1
            while (true) {
                val p = g.immich.lockedAssets(session, page)
                all += p.items
                if (p.nextPage == null || p.items.isEmpty()) break
                page++
            }
            LockedUi.Open(all)
        }.getOrElse { LockedUi.Problem(it.message ?: "Couldn't open the locked folder") }
    }

    LaunchedEffect(Unit) {
        ui = runCatching {
            session = lockedSession(g)
            val st = runCatching { g.immich.lockStatus(session) }.getOrElse { e ->
                // An old session the server forgot: get a new one.
                if (e is HttpException && e.code == 401) { session = lockedSession(g, renew = true); g.immich.lockStatus(session) } else throw e
            }
            if (st.pinCode) LockedUi.EnterPin() else LockedUi.NewPin
        }.getOrElse { LockedUi.Problem(it.message ?: "Couldn't reach your server") }
    }
    DisposableEffect(Unit) {
        onDispose { if (session.isNotBlank()) g.scope.launch { runCatching { g.immich.lock(session) } } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) "Locked folder" else "${selected.size} selected") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (selected.isNotEmpty()) TextButton(onClick = {
                        val ids = selected.toList()
                        scope.launch {
                            val msg = runCatching { g.immich.setVisibility(ids, "timeline", session) }
                                .fold({ "Moved ${ids.size} back to your photos" }, { it.message ?: "Couldn't move them" })
                            selected = emptySet()
                            open()
                            launch { snack.showSnackbar(msg) }
                        }
                    }) { Text("Move out") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val u = ui) {
                LockedUi.Loading -> CenteredLoading()
                is LockedUi.Problem -> EmptyState(Icons.Outlined.Lock, "Locked folder unavailable", u.message)
                LockedUi.NewPin -> PinEntry(
                    "Choose a PIN", "6 digits. You'll need it every time you open the locked folder. If you forget it, ask your server's admin to reset it.",
                    confirm = true,
                ) { pin ->
                    scope.launch {
                        runCatching { g.immich.setPin(session, pin); g.immich.unlock(session, pin) }
                            .onSuccess { open() }
                            .onFailure { ui = LockedUi.Problem(it.message ?: "Couldn't set the PIN") }
                    }
                }
                is LockedUi.EnterPin -> PinEntry(
                    "Enter your PIN", if (u.wrong) "That PIN isn't right. Try again." else "Your locked photos open with your PIN.",
                    confirm = false,
                ) { pin ->
                    scope.launch {
                        runCatching { g.immich.unlock(session, pin) }.onSuccess { open() }.onFailure { ui = LockedUi.EnterPin(wrong = true) }
                    }
                }
                is LockedUi.Open -> if (u.assets.isEmpty()) EmptyState(
                    Icons.Outlined.LockOpen, "Nothing here yet",
                    "Open a photo, tap ⋮ and choose \"Move to Locked folder\". It disappears from your timeline, albums and other apps.",
                ) else LazyVerticalGrid(GridCells.Adaptive(110.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(u.assets, key = { it.id }) { a ->
                        Box(
                            Modifier.aspectRatio(1f).combinedClickable(
                                onClick = { if (selected.isEmpty()) viewing = a else selected = if (a.id in selected) selected - a.id else selected + a.id },
                                onLongClick = { selected = selected + a.id },
                            ),
                        ) {
                            RemoteImage(g.immich.thumbnailUrl(a.id), a.id, Modifier.fillMaxSize(), session = session, contentDescription = "Locked photo")
                            if (a.id in selected) Icon(
                                Icons.Filled.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    viewing?.let { a ->
        Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                RemoteImage(
                    g.immich.thumbnailUrl(a.id, preview = true), a.id, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit, background = Color.Black, session = session,
                )
                IconButton(onClick = { viewing = null }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PinEntry(title: String, body: String, confirm: Boolean, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ok = pin.length == 6 && (!confirm || again == pin)
    Column(
        Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        val digits = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        val mismatch = again.length == 6 && again != pin
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = digits, modifier = Modifier.fillMaxWidth())
        if (confirm) OutlinedTextField(again, { again = it.filter(Char::isDigit).take(6) }, label = { Text("Same PIN again") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = digits, modifier = Modifier.fillMaxWidth(),
            isError = mismatch, supportingText = { if (mismatch) Text("The PINs don't match") })
        Button(onClick = { onPin(pin) }, enabled = ok) { Text(if (confirm) "Set PIN and open" else "Open") }
    }
}
