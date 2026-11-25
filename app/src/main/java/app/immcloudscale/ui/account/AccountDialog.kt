package app.immcloudscale.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.immich.ServerStorage
import app.immcloudscale.data.nextcloud.Quota
import app.immcloudscale.data.vpn.VpnStatus
import app.immcloudscale.graph
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.Pinwheel
import app.immcloudscale.ui.components.formatAgo
import app.immcloudscale.ui.components.formatBytes

/** Google's account card, adapted: who you are on each server, backup, storage, and the VPN. */
@Composable
fun AccountDialog(onDismiss: () -> Unit, onServers: () -> Unit, onBackup: () -> Unit, onNetwork: () -> Unit, onMembers: () -> Unit = {}) {
    val g = LocalContext.current.graph
    val s by g.settingsState.collectAsStateWithLifecycle()
    val vpn by g.vpn.state.collectAsStateWithLifecycle()
    var immichStorage by remember { mutableStateOf<ServerStorage?>(null) }
    var quota by remember { mutableStateOf<Quota?>(null) }
    LaunchedEffect(Unit) {
        if (s.immichConfigured) immichStorage = runCatching { g.immich.storage() }.getOrNull()
        if (s.nextcloudConfigured) quota = runCatching { g.nextcloud.quota() }.getOrNull()
        // Someone may have made this person an admin (or not) since pairing.
        if (s.deviceToken.isNotBlank()) runCatching { g.members.me() }.onSuccess { me ->
            if (me.isAdmin != s.isAdmin) g.settings.update { it.copy(isAdmin = me.isAdmin) }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AccountCard(s, vpn.status == VpnStatus.Connected, immichStorage, quota, onDismiss, onServers, onBackup, onNetwork, onMembers)
    }
}

@Composable
fun AccountCard(
    s: AppSettings,
    vpnConnected: Boolean,
    immichStorage: ServerStorage?,
    quota: Quota?,
    onDismiss: () -> Unit,
    onServers: () -> Unit,
    onBackup: () -> Unit,
    onNetwork: () -> Unit,
    onMembers: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Box(Modifier.fillMaxWidth().padding(8.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) { Icon(Icons.Filled.Close, "Close") }
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    Pinwheel(20.dp); Spacer(Modifier.width(8.dp))
                    Text("ImmCloudScale", style = MaterialTheme.typography.titleMedium)
                }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.padding(horizontal = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val name = s.immichUserName.ifBlank { s.ncUser.ifBlank { "Me" } }
                    LetterAvatar(name, 64.dp, contentDescription = "$name's account")
                    Text("Hi, ${name.substringBefore(' ')}!", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        listOfNotNull(s.immichEmail.ifBlank { null }, s.ncUser.takeIf { s.nextcloudConfigured }?.let { "$it on Nextcloud" }).joinToString(" · ")
                            .ifEmpty { "No servers connected yet" },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = { onDismiss(); onServers() }, modifier = Modifier.padding(top = 12.dp)) { Text("Manage your servers") }
                }
            }
            Spacer(Modifier.padding(4.dp))
            val rowColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ListItem(
                modifier = Modifier.clickable { onDismiss(); onBackup() },
                colors = rowColors,
                leadingContent = { Icon(if (s.backupEnabled) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, null) },
                headlineContent = { Text(if (s.backupEnabled) "Backup is on" else "Backup is off") },
                supportingContent = {
                    Text(if (s.backupEnabled) "Last backed up ${formatAgo(s.lastBackupAt)}" else "Back up this phone's photos to Immich")
                },
            )
            if (immichStorage != null) StorageRow(
                Icons.Outlined.Storage, "Photo storage",
                "${immichStorage.diskUse} of ${immichStorage.diskSize} used on the server",
                (immichStorage.diskUsagePercentage / 100).toFloat(), rowColors,
            )
            if (quota != null) StorageRow(
                Icons.Outlined.Dns, "File storage",
                if (quota.total > 0) "${formatBytes(quota.used)} of ${formatBytes(quota.total)} used" else "${formatBytes(quota.used)} used",
                if (quota.total > 0) quota.used.toFloat() / quota.total else null, rowColors,
            )
            ListItem(
                modifier = Modifier.clickable { onDismiss(); onNetwork() },
                colors = rowColors,
                leadingContent = { Icon(Icons.Outlined.Hub, null) },
                headlineContent = { Text(if (vpnConnected) "Connected to home" else "Home VPN") },
                supportingContent = { Text(if (s.vpnConfigured) (if (vpnConnected) "WireGuard tunnel is up" else "Not connected") else "Not set up") },
            )
            if (s.isAdmin) ListItem(
                modifier = Modifier.clickable { onDismiss(); onMembers() },
                colors = rowColors,
                leadingContent = { Icon(Icons.Outlined.Group, null) },
                headlineContent = { Text("Members") },
                supportingContent = { Text("Approve sign-up requests and choose admins") },
            )
            Text(
                "Your photos and files stay on your own server",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StorageRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, fraction: Float?,
    colors: androidx.compose.material3.ListItemColors,
) {
    ListItem(
        colors = colors,
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(body)
                if (fraction != null) LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    gapSize = 0.dp, drawStopIndicator = {},
                )
            }
        },
    )
}
