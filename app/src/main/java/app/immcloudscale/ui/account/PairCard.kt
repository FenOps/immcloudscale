package app.immcloudscale.ui.account

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.HomeGuard
import app.immcloudscale.data.pairing.PairLink
import app.immcloudscale.data.pairing.Pairing
import app.immcloudscale.data.pairing.QrImage
import app.immcloudscale.graph
import app.immcloudscale.ui.theme.GoogleColors
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Scans a pairing QR with Google's code scanner; reports a parsed link or an error message. */
fun scanPairingCode(context: android.content.Context, onLink: (PairLink) -> Unit, onError: (String) -> Unit) {
    GmsBarcodeScanning.getClient(context).startScan()
        .addOnSuccessListener { code ->
            val link = code.rawValue?.let(Pairing::parseLink)
            if (link != null) onLink(link) else onError("That isn't an ImmCloudScale pairing code")
        }
        .addOnFailureListener { onError("The QR scanner isn't available on this phone; type the code instead") }
}

/**
 * "Pair with ImmCloudScale Server": one code from the server's console sets up photos, files
 * and the home VPN together.
 */
@Composable
fun PairCard(onPaired: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }

    val openUrl = { url: String -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }; Unit }

    fun pair(link: PairLink) {
        busy = true; error = null
        scope.launch {
            runCatching { g.pairWith(link, openUrl) }
                .onSuccess { onPaired() }
                .onFailure { error = it.message ?: "Pairing failed" }
            busy = false
        }
    }

    // A QR code saved as a picture (a screenshot of the admin console, a photo of the setup screen).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val link = withContext(Dispatchers.IO) { runCatching { QrImage.read(ctx, uri) }.getOrNull() }?.let(Pairing::parseLink)
            if (link != null) pair(link)
            else error = "No ImmCloudScale pairing code found in that picture. Try a sharper screenshot, or type the code."
        }
    }

    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Dns, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("ImmCloudScale Server", style = MaterialTheme.typography.titleMedium)
                    if (s.pairedServer.isBlank() && s.joinId.isBlank()) {
                        Text("Recommended", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                if (s.pairedServer.isNotBlank()) Icon(Icons.Filled.CheckCircle, "Paired", tint = GoogleColors.Online)
            }
            if (s.pairedServer.isNotBlank()) {
                Text("Paired with ${s.pairedServerName.ifBlank { s.pairedServer }} as ${s.immichUserName}. Photos, files and remote access are set up.")
                OutlinedButton(onClick = { scope.launch { g.settings.update { it.copy(pairedServer = "") } } }) { Text("Pair again") }
                return@Column
            }
            if (s.joinId.isNotBlank()) {
                JoinWaiting(s, onPaired)
                return@Column
            }
            Text("Scan the code from the server, or paste an invite someone sent you. Photos, files and remote access are set up in one go.")
            Button(onClick = { scanPairingCode(ctx, { pair(it) }, { error = it }) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan QR code")
            }
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(8.dp)); Text("Use a QR picture from the gallery")
            }
            OutlinedButton(onClick = {
                // An invite sent by message: the whole message can be copied, the link is found inside it.
                val text = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                val link = Pairing.findLink(text)
                if (link != null) pair(link) else error = "Copy the invite message (or its link) first, then tap here."
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Paste an invite")
            }
            Text("or type them in", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(server, { server = it }, label = { Text("Server address") }, placeholder = { Text("192.168.1.50") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("Pairing code") }, placeholder = { Text("K7QX-M2PA") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { pair(PairLink(server, code)) }, enabled = !busy && server.isNotBlank() && Pairing.normalizeCode(code).length == 9) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                Text("Pair")
            }
            TextButton(onClick = { joining = true }, enabled = !busy) { Text("New here? Ask to join") }
        }
    }
    if (joining) JoinDialog { joining = false }
}

/** Confirmation shown when a pairing link (QR scanned with the camera) opens the app. */
@Composable
fun PairLinkDialog(link: PairLink, onDone: (paired: Boolean) -> Unit) {
    val g = LocalContext.current.graph
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(false) },
        icon = { Icon(Icons.Outlined.Dns, null) },
        title = { Text("Join this home server?") },
        text = {
            val s = g.settingsState.collectAsStateWithLifecycle().value
            val host = Uri.parse(link.server).host.orEmpty()
            // The deadline is checked again every few seconds, so Pair turns off when the time is up.
            LaunchedEffect(link) { while (true) { delay(10_000); now = System.currentTimeMillis() / 1000 } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This phone will back up photos and files to the ImmCloudScale server at $host.")
                inviteDeadline(link, now)?.let { (text, over) ->
                    Text(text, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (link.tailscaleIp.isNotBlank() && s.pairedServer.isNotBlank()) Text(
                    "This phone is already set up with ${s.pairedServerName.ifBlank { "a server" }}. Only join if you know who sent this invite: " +
                        "it connects through ${if (link.controlUrl.isBlank()) "Tailscale" else Uri.parse(link.controlUrl).host}.",
                    color = MaterialTheme.colorScheme.error,
                )
                if (s.immichConfigured || s.nextcloudConfigured) Text(
                    "This replaces your current setup (${listOf(s.immichUrl, s.ncUrl).filter { it.isNotBlank() }.joinToString(", ")}). " +
                        "Backups will then go to the new server.",
                    color = MaterialTheme.colorScheme.error,
                )
                if (!HomeGuard.isPrivateHost(host)) Text(
                    "$host isn't a home-network address. Only pair with a server you set up yourself: it will receive your photos.",
                    color = MaterialTheme.colorScheme.error,
                )
                Text("Code ${link.code}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val ctx = LocalContext.current
            TextButton(enabled = !busy && !link.expired(now), onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { g.pairWith(link) { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } }
                        .onSuccess { onDone(true) }
                        .onFailure { error = it.message ?: "Pairing failed"; busy = false }
                }
            }) { Text("Pair") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(false) }) { Text("Cancel") } },
    )
}

/** "Valid until 15:42" for an invite (true when it's already too late), or null when the link has no deadline. */
fun inviteDeadline(link: PairLink, nowSeconds: Long = System.currentTimeMillis() / 1000): Pair<String, Boolean>? {
    if (link.expires <= 0) return null
    val time = inviteTime(Instant.ofEpochSecond(link.expires).toString(), now = Instant.ofEpochSecond(nowSeconds))
    return if (link.expired(nowSeconds)) "This invite expired at $time. Ask for a new one." to true else "This invite works once, until $time." to false
}

