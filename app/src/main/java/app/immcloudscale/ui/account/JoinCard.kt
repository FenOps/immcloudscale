package app.immcloudscale.ui.account

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.pairing.JoinTicket
import app.immcloudscale.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Ask to join": someone new asks the server's admins for an account, from the home Wi-Fi. */
@Composable
fun JoinDialog(onDismiss: () -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Ask to join") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("An admin of the server approves your request; then this app connects by itself. Be on the same Wi-Fi as the server.")
                OutlinedTextField(server, { server = it }, label = { Text("Server address") }, placeholder = { Text("192.168.1.49") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c in "._-" } }, label = { Text("Your name") },
                    placeholder = { Text("alex") }, singleLine = true, supportingText = { Text("Lowercase letters and digits, starting with a letter") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Email (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && server.isNotBlank() && name.length >= 2, onClick = {
                busy = true; error = null
                scope.launch {
                    val device = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")
                    runCatching { g.members.requestJoin(server, name, email, device) }
                        .onSuccess { t ->
                            g.settings.update { it.copy(joinServer = server.trim(), joinId = t.id, joinToken = t.token, joinName = name) }
                            onDismiss()
                        }
                        .onFailure { error = it.message ?: "Couldn't reach the server" }
                    busy = false
                }
            }) { Text("Send request") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** While a request waits: checks every few seconds and pairs by itself once an admin approves. */
@Composable
fun JoinWaiting(s: AppSettings, onPaired: () -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("pending") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.joinId) {
        val ticket = JoinTicket(s.joinId, s.joinToken)
        while (true) {
            runCatching { g.members.joinStatus(s.joinServer, ticket) }
                .onSuccess { st ->
                    status = st.status; error = null
                    val code = st.code
                    if (st.status == "approved" && code != null) {
                        runCatching { g.pairWithServer(s.joinServer, code) }
                            .onSuccess { onPaired(); return@LaunchedEffect }
                            .onFailure { error = it.message }
                    }
                    if (st.status == "declined") return@LaunchedEffect
                }
                .onFailure { error = "Can't reach the server right now; still waiting" }
            withContext(Dispatchers.IO) { delay(5_000) } // real time, not the screen's frame clock
        }
    }
    val clear = { scope.launch { g.settings.update { it.copy(joinServer = "", joinId = "", joinToken = "", joinName = "") } } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (status == "declined") {
            Text("Your request to join as ${s.joinName} was declined.", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { clear() }) { Text("Start over") }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A request can wait a while for an admin: a still hourglass, the spinner only while connecting.
            if (status == "approved") CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.HourglassTop, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                if (status == "approved") "Approved! Connecting…" else "Waiting for an admin to approve ${s.joinName}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text("Ask an admin to open ImmCloudScale → Account → Members, or the server's admin console. This app connects by itself once they approve.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { clear() }) { Text("Cancel request") }
    }
}
