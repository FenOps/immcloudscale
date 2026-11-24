package app.immcloudscale.ui.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.vpn.VpnManager
import app.immcloudscale.data.vpn.WgRoutes

@Composable
fun PasteConfigDialog(onDismiss: () -> Unit, onSave: (String) -> String?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste WireGuard config") },
        text = {
            Column {
                Text(
                    "Paste the [Interface] / [Peer] text your NAS, router or wg-easy generated for this phone.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    text, { text = it; error = null },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text("[Interface]\nPrivateKey = …\nAddress = 10.8.0.2/24\n\n[Peer]\n…", fontFamily = FontFamily.Monospace) },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { error = onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Tailscale-style "add this device": generates a key pair here, shows the public key to paste into
 * the NAS's WireGuard server, and builds the config from the server's details.
 */
@Composable
fun AddDeviceDialog(onDismiss: () -> Unit, onCopy: (String) -> Unit, onSave: (String) -> String?) {
    val keys = remember { VpnManager.newKeyPair() }
    var address by remember { mutableStateOf("10.8.0.2/32") }
    var dns by remember { mutableStateOf("") }
    var serverKey by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf("10.8.0.0/24, 192.168.1.0/24") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add this device") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Add this phone as a peer on your NAS with this public key:", style = MaterialTheme.typography.bodyMedium)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(keys.second, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onCopy(keys.second) }) { Icon(Icons.Outlined.ContentCopy, "Copy public key") }
                    }
                }
                Text("2. Enter what the server shows:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Endpoint (host:port)") }, placeholder = { Text("home.example.com:51820") }, singleLine = true)
                OutlinedTextField(serverKey, { serverKey = it }, label = { Text("Server public key") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("This device's address") }, singleLine = true)
                OutlinedTextField(allowed, { allowed = it }, label = { Text("Home networks (AllowedIPs)") }, singleLine = true)
                OutlinedTextField(dns, { dns = it }, label = { Text("DNS (optional)") }, placeholder = { Text("10.8.0.1") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = endpoint.isNotBlank() && serverKey.isNotBlank() && address.isNotBlank(),
                onClick = { error = onSave(WgRoutes.buildConfig(keys.first, address, dns, serverKey, endpoint, allowed)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelSheet(apps: List<AppEntry>?, excluded: Set<String>, onDismiss: () -> Unit, onChange: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(excluded) }
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = { onChange(chosen); onDismiss() }) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Split tunneling", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "Checked apps bypass the VPN and use your regular connection.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                filter, { filter = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search apps") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (apps == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(apps.filter { filter.isBlank() || it.label.contains(filter, true) }, key = { it.pkg }) { app ->
                    ListItem(
                        modifier = Modifier.clickable { chosen = if (app.pkg in chosen) chosen - app.pkg else chosen + app.pkg },
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.pkg, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = { Checkbox(app.pkg in chosen, { chosen = if (it) chosen + app.pkg else chosen - app.pkg }) },
                    )
                }
            }
        }
    }
}
