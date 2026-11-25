package app.immcloudscale.ui.account

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.backup.FreeUp
import app.immcloudscale.data.backup.PhoneCopies
import app.immcloudscale.data.backup.findFreeUp
import app.immcloudscale.graph
import app.immcloudscale.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Free up space": removes photos and videos from the phone once they're safely on the server. */
@Composable
fun FreeUpSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, onMessage: (String) -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<FreeUp?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val f = found
        found = null
        if (r.resultCode == android.app.Activity.RESULT_OK && f != null) onMessage("Freed ${formatBytes(f.bytes)} on this phone")
    }
    ListItem(
        modifier = Modifier.clickable(enabled = !checking && s.immichConfigured) {
            checking = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { findFreeUp(ctx, g.immich, s.freeUpKeepDays) } }
                    .onSuccess { if (it.ids.isEmpty()) onMessage("Nothing to free up: no backed-up photos older than ${s.freeUpKeepDays} days") else found = it }
                    .onFailure { onMessage(it.message ?: "Couldn't reach your server") }
                checking = false
            }
        },
        leadingContent = { Icon(Icons.Outlined.CleaningServices, null) },
        headlineContent = { Text("Free up space on this phone") },
        supportingContent = { Text("Remove photos and videos older than ${s.freeUpKeepDays} days that are safely backed up") },
        trailingContent = { if (checking) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
    )
    ListItem(
        modifier = Modifier.clickable { update { it.copy(freeUpAuto = !it.freeUpAuto) } },
        leadingContent = { Icon(Icons.Outlined.AutoDelete, null) },
        headlineContent = { Text("Free up space automatically") },
        supportingContent = { Text("After each backup, remove what's safely on your server (older than ${s.freeUpKeepDays} days)") },
        trailingContent = { Switch(s.freeUpAuto, { v -> update { it.copy(freeUpAuto = v) } }) },
    )
    if (s.freeUpAuto && Build.VERSION.SDK_INT >= 31 && !PhoneCopies.canManageMedia(ctx)) {
        Text(
            "Android asks before removing photos. To let it happen without asking, allow \"Media management\" for ImmCloudScale.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        TextButton(
            onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).setData(android.net.Uri.fromParts("package", ctx.packageName, null))) } },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("Allow media management") }
    }
    found?.let { f ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Free up ${formatBytes(f.bytes)}?") },
            text = { Text("${f.ids.size} photos and videos will be removed from this phone. They stay safe on your server and in the app.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val sender = withContext(Dispatchers.IO) { runCatching { PhoneCopies.deleteRequest(ctx, PhoneCopies.find(ctx, f.ids)) }.getOrNull() }
                        if (sender != null) launcher.launch(IntentSenderRequest.Builder(sender).build())
                        else { found = null; onMessage("This needs Android 11 or newer") }
                    }
                }) { Text("Free up") }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Cancel") } },
        )
    }
}

/**
 * After an automatic backup found photos to free: removes them right away when Android allows it without asking
 * ("Media management"), otherwise asks once.
 */
@Composable
fun FreeUpPrompt(ids: Set<Long>) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val clear = { scope.launch { g.settings.update { it.copy(freeUpPending = it.freeUpPending - ids) } } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { clear() }
    val go = {
        scope.launch {
            val sender = withContext(Dispatchers.IO) { runCatching { PhoneCopies.deleteRequest(ctx, PhoneCopies.find(ctx, ids)) }.getOrNull() }
            if (sender != null) launcher.launch(IntentSenderRequest.Builder(sender).build()) else clear()
        }
    }
    val silent = remember { PhoneCopies.canManageMedia(ctx) }
    if (silent) {
        LaunchedEffect(ids) { go() }
        return
    }
    AlertDialog(
        onDismissRequest = { clear() },
        icon = { Icon(Icons.Outlined.CleaningServices, null) },
        title = { Text("Free up space?") },
        text = { Text("${ids.size} backed-up photos and videos can be removed from this phone. They stay safe on your server.") },
        confirmButton = { TextButton(onClick = { go() }) { Text("Free up") } },
        dismissButton = { TextButton(onClick = { clear() }) { Text("Not now") } },
    )
}
