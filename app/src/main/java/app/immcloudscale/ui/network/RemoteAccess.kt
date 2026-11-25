package app.immcloudscale.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.tailscale.TsState
import app.immcloudscale.data.tailscale.TsStatus
import app.immcloudscale.ui.theme.GoogleColors

class RemoteAccessActions(
    val onEnabled: (Boolean) -> Unit = {},
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onSignIn: (String) -> Unit = {},
    val onSignOut: () -> Unit = {},
)

/** Is remote access through Tailscale worth showing: the phone is paired, or knows the server's Tailscale address. */
fun remoteAccessAvailable(s: AppSettings) = s.pairedServer.isNotBlank() || s.tsServerIp.isNotBlank()

/**
 * Remote access through Tailscale, which runs inside the app. The switch is the one thing most people need:
 * on, the app connects by itself whenever the phone is away from the home Wi-Fi.
 */
@Composable
fun RemoteAccessCard(ts: TsState, s: AppSettings, atHome: Boolean, a: RemoteAccessActions) {
    val connected = ts.status == TsStatus.Connected
    val (icon, title, detail) = when {
        s.tsServerIp.isBlank() -> Triple(
            Icons.Outlined.PublicOff, "Not available yet",
            "Your server isn't on Tailscale. On the server, run: sudo ics-server tailscale",
        )
        ts.status == TsStatus.NeedsLogin -> Triple(Icons.Outlined.Login, "Sign in to connect", "Sign in to Tailscale once; after that the app connects by itself.")
        ts.status == TsStatus.Starting -> Triple(Icons.Outlined.Public, "Connecting…", "Joining your Tailscale network")
        ts.status == TsStatus.Error -> Triple(Icons.Outlined.PublicOff, "Couldn't connect", ts.error ?: "Try again in a moment")
        connected -> Triple(Icons.Outlined.Public, "Connected from outside", "Photos, files and backup work as if you were home")
        atHome -> Triple(Icons.Outlined.Home, "You're home", "On the home Wi-Fi the app talks to your server directly")
        s.tsEnabled -> Triple(Icons.Outlined.Public, "Ready", "Connects by itself when you leave the home Wi-Fi")
        else -> Triple(Icons.Outlined.PublicOff, "Off", "Away from home, your photos and files can't be reached")
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = if (connected) GoogleColors.Online.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ts.status == TsStatus.Starting) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(icon, null, tint = if (connected) GoogleColors.Online else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Remote access", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Connect away from home Wi-Fi", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Turn this on to back up and see your photos and files when you're not on your home Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    s.tsEnabled, a.onEnabled, enabled = s.tsServerIp.isNotBlank(),
                    modifier = Modifier.semantics { contentDescription = "Connect away from home Wi-Fi" },
                )
            }
            if (s.tsServerIp.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    ts.status == TsStatus.NeedsLogin && ts.authUrl != null ->
                        Button(onClick = { a.onSignIn(ts.authUrl) }) { Text("Sign in to Tailscale") }
                    connected -> OutlinedButton(onClick = a.onDisconnect) { Text("Disconnect") }
                    ts.status != TsStatus.Starting -> OutlinedButton(onClick = a.onConnect) { Text("Connect now") }
                }
                if (s.tsAuthKey.isBlank() && (connected || ts.status == TsStatus.Off)) TextButton(onClick = a.onSignOut) { Text("Sign out") }
            }
        }
    }
}
