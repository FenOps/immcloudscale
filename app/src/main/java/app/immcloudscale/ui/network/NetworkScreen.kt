package app.immcloudscale.ui.network

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.vpn.PeerStats
import app.immcloudscale.data.vpn.VpnState
import app.immcloudscale.data.vpn.VpnStatus
import app.immcloudscale.data.vpn.WgRoutes
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.SectionHeader
import app.immcloudscale.ui.components.formatAgo
import app.immcloudscale.ui.components.formatBytes
import app.immcloudscale.ui.components.graphViewModel
import app.immcloudscale.ui.theme.GoogleColors

class NetworkActions(
    val onToggle: (Boolean) -> Unit = {},
    val onExitNode: (Boolean) -> Unit = {},
    val onAutoConnect: (Boolean) -> Unit = {},
    val onSplitTunnel: () -> Unit = {},
    val onAddTrusted: () -> Unit = {},
    val onRemoveTrusted: (String) -> Unit = {},
    val onAlwaysOn: () -> Unit = {},
    val onImportFile: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onAddDevice: () -> Unit = {},
    val onViewConfig: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onRecheck: () -> Unit = {},
    val onAccount: () -> Unit = {},
)

@Composable
fun NetworkScreen(onAccount: () -> Unit, bottomPadding: PaddingValues) {
    val ctx = LocalContext.current
    val vm = graphViewModel { NetworkViewModel(it) }
    val vpn by vm.vpn.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var paste by remember { mutableStateOf(false) }
    var addDevice by remember { mutableStateOf(false) }
    var viewConfig by remember { mutableStateOf(false) }
    var splitTunnel by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) { ui.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(vpn.error) { vpn.error?.let { snack.showSnackbar(it) } }

    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) vm.connect()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText() }.getOrNull()
            val err = if (text == null) "Couldn't read the file" else vm.importConfig(text)
            if (err != null) android.widget.Toast.makeText(ctx, err, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val ssid = vm.currentSsid()
        if (granted && ssid != null) vm.addTrusted(ssid)
        else android.widget.Toast.makeText(ctx, "Connect to your home Wi-Fi and allow location so Android can tell the network name", android.widget.Toast.LENGTH_LONG).show()
    }

    val actions = NetworkActions(
        onToggle = { on ->
            if (on) {
                val consent = ctx.graphVpnPermission()
                if (consent != null) vpnConsent.launch(consent) else vm.connect()
            } else vm.disconnect()
        },
        onExitNode = vm::setExitNode,
        onAutoConnect = { on ->
            if (on && ctx.graphVpnPermission() != null) vpnConsent.launch(ctx.graphVpnPermission()!!)
            vm.setAutoConnect(on)
        },
        onSplitTunnel = { vm.loadApps(); splitTunnel = true },
        onAddTrusted = { locationPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
        onRemoveTrusted = vm::removeTrusted,
        onAlwaysOn = { runCatching { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } },
        onImportFile = { filePicker.launch(arrayOf("*/*")) },
        onPaste = { paste = true },
        onAddDevice = { addDevice = true },
        onViewConfig = { viewConfig = true },
        onRemove = { confirmRemove = true },
        onCopy = { text ->
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ImmCloudScale", text))
        },
        onRecheck = { vm.checkServices() },
        onAccount = onAccount,
    )
    NetworkContent(vpn, s, ui, vm.summary(), vm.publicKey(), actions, snack, bottomPadding)

    if (paste) PasteConfigDialog(onDismiss = { paste = false }) { text -> vm.importConfig(text).also { if (it == null) paste = false } }
    if (addDevice) AddDeviceDialog(onDismiss = { addDevice = false }, onCopy = actions.onCopy) { text ->
        vm.importConfig(text).also { if (it == null) addDevice = false }
    }
    if (viewConfig) AlertDialog(
        onDismissRequest = { viewConfig = false },
        title = { Text("WireGuard configuration") },
        text = {
            Text(
                s.vpnConfig.replace(Regex("(?im)^(PrivateKey|PresharedKey)\\s*=.*$"), "$1 = (hidden)"),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = { viewConfig = false }) { Text("Close") } },
    )
    if (splitTunnel) SplitTunnelSheet(ui.apps, s.vpnExcludedApps, onDismiss = { splitTunnel = false }) { vm.setExcluded(it) }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove VPN configuration?") },
        text = { Text("This device will disconnect from your home network. You can import the configuration again later.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; vm.removeConfig() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
}

private fun android.content.Context.graphVpnPermission(): Intent? = android.net.VpnService.prepare(this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkContent(
    vpn: VpnState,
    s: AppSettings,
    ui: NetworkUi,
    summary: WgRoutes.Summary,
    publicKey: String?,
    a: NetworkActions,
    snack: SnackbarHostState = remember { SnackbarHostState() },
    bottomPadding: PaddingValues = PaddingValues(0.dp),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
                actions = { IconButton(onClick = a.onAccount) { LetterAvatar(s.immichUserName.ifBlank { s.ncUser.ifBlank { "Me" } }, 32.dp) } },
            )
        },
        snackbarHost = { SnackbarHost(snack, Modifier.padding(bottomPadding)) },
    ) { pad ->
        if (!s.vpnConfigured) {
            Column(Modifier.fillMaxSize().padding(pad).padding(bottom = bottomPadding.calculateBottomPadding())) {
                EmptyState(
                    Icons.Outlined.Hub, "Reach your NAS from anywhere",
                    "Connect this phone to your home network with WireGuard. Import the config your NAS or router made, or add this device and copy its key to the server.",
                    Modifier.weight(1f),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = a.onImportFile, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Import .conf file")
                    }
                    OutlinedButton(onClick = a.onPaste, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Paste configuration")
                    }
                    OutlinedButton(onClick = a.onAddDevice, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add this device")
                    }
                }
            }
            return@Scaffold
        }
        val connected = vpn.status == VpnStatus.Connected
        LazyColumn(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 16.dp)) {
            item { StatusCard(vpn, s, summary, publicKey, a) }

            item { SectionHeader("Routing") }
            item {
                ToggleRow(
                    Icons.Outlined.Public, "Use home as exit node",
                    if (s.vpnFullTunnel) "All internet traffic goes through your home connection"
                    else "Only your NAS and home network go through the VPN",
                    s.vpnFullTunnel, a.onExitNode,
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = a.onSplitTunnel),
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.CallSplit, null) },
                    headlineContent = { Text("Split tunneling") },
                    supportingContent = {
                        Text(if (s.vpnExcludedApps.isEmpty()) "All apps use the VPN" else "${s.vpnExcludedApps.size} app${if (s.vpnExcludedApps.size == 1) "" else "s"} bypass the VPN")
                    },
                )
            }

            item { SectionHeader("Services on your NAS") { IconButton(onClick = a.onRecheck) { Icon(Icons.Outlined.Refresh, "Check again") } } }
            ui.services.forEach { svc ->
                item(key = "svc-" + svc.name) {
                    ListItem(
                        leadingContent = { Icon(if (svc.name == "Immich") Icons.Outlined.PhotoLibrary else Icons.Outlined.Folder, null) },
                        headlineContent = { Text(svc.name) },
                        supportingContent = { Text(svc.url.ifBlank { "Not set up" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { ReachBadge(svc) },
                    )
                }
            }

            item { SectionHeader("Devices") }
            item {
                ListItem(
                    leadingContent = { DeviceIcon(Icons.Outlined.Smartphone, connected) },
                    headlineContent = { Text("This phone") },
                    supportingContent = { Text(summary.addresses.joinToString(", ").ifEmpty { "—" }) },
                    trailingContent = { if (connected) Text("Connected", color = GoogleColors.Online, style = MaterialTheme.typography.labelMedium) },
                )
            }
            summary.peers.forEach { p ->
                val st: PeerStats? = ui.stats.firstOrNull { it.publicKey == p.publicKey }
                val online = st != null && st.lastHandshake > 0 && System.currentTimeMillis() - st.lastHandshake < 3 * 60_000
                item(key = "peer-" + p.publicKey) {
                    ListItem(
                        leadingContent = { DeviceIcon(Icons.Outlined.Router, online) },
                        headlineContent = { Text(p.name ?: p.endpoint?.substringBeforeLast(':') ?: p.publicKey.take(8) + "…") },
                        supportingContent = {
                            Column {
                                Text(p.allowedIps.joinToString(", "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (st != null) Text(
                                    "Handshake ${formatAgo(st.lastHandshake)} · ↓ ${formatBytes(st.rxBytes)} ↑ ${formatBytes(st.txBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        trailingContent = {
                            Text(if (online) "Online" else if (connected) "Waiting" else "Offline",
                                color = if (online) GoogleColors.Online else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }

            item { SectionHeader("Connect automatically") }
            item {
                ToggleRow(
                    Icons.Outlined.WifiTethering, "Auto-connect away from home",
                    "Connect when you leave trusted Wi-Fi, disconnect when you're back", s.vpnAutoConnect, a.onAutoConnect,
                )
            }
            if (s.vpnAutoConnect) {
                s.vpnTrustedSsids.sorted().forEach { ssid ->
                    item(key = "ssid-$ssid") {
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.Wifi, null) },
                            headlineContent = { Text(ssid) },
                            supportingContent = { Text("Trusted network") },
                            trailingContent = { IconButton(onClick = { a.onRemoveTrusted(ssid) }) { Icon(Icons.Filled.Close, "Remove") } },
                        )
                    }
                }
                item {
                    ListItem(
                        modifier = Modifier.clickable(onClick = a.onAddTrusted),
                        leadingContent = { Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text("Add current Wi-Fi as trusted", color = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = a.onAlwaysOn),
                    leadingContent = { Icon(Icons.Outlined.Lock, null) },
                    headlineContent = { Text("Always-on VPN") },
                    supportingContent = { Text("Keep connected and block traffic without VPN, in Android settings") },
                    trailingContent = { Icon(Icons.Outlined.Settings, null) },
                )
            }

            item { SectionHeader("Configuration") }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = a.onViewConfig),
                    leadingContent = { Icon(Icons.Outlined.Visibility, null) },
                    headlineContent = { Text("View configuration") },
                    supportingContent = { Text(summary.dns.joinToString(", ").let { if (it.isEmpty()) "No DNS set" else "DNS $it" }) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = a.onImportFile),
                    leadingContent = { Icon(Icons.Outlined.FileOpen, null) },
                    headlineContent = { Text("Replace from file") },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = a.onRemove),
                    leadingContent = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text("Remove configuration", color = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(vpn: VpnState, s: AppSettings, summary: WgRoutes.Summary, publicKey: String?, a: NetworkActions) {
    val connected = vpn.status == VpnStatus.Connected
    val connecting = vpn.status == VpnStatus.Connecting
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Hub, null, tint = if (connected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        when (vpn.status) {
                            VpnStatus.Connected -> "Connected to home"
                            VpnStatus.Connecting -> "Connecting…"
                            VpnStatus.Error -> "Not connected"
                            VpnStatus.Disconnected -> "Disconnected"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        listOfNotNull(
                            vpn.network?.let { "On $it" },
                            if (connected) (if (s.vpnFullTunnel) "Exit node on" else "Home network only") else null,
                        ).joinToString(" · ").ifEmpty { "WireGuard" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = connected || connecting, onCheckedChange = a.onToggle, enabled = !connecting)
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            InfoLine(Icons.Outlined.Dns, "This device", summary.addresses.joinToString(", ").ifEmpty { "—" }) { a.onCopy(summary.addresses.firstOrNull()?.substringBefore('/') ?: "") }
            if (publicKey != null) InfoLine(Icons.Outlined.Lock, "Public key", publicKey) { a.onCopy(publicKey) }
        }
    }
}

@Composable
private fun InfoLine(icon: ImageVector, label: String, value: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, "Copy $label", Modifier.size(18.dp)) }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(),
    )
}

@Composable
private fun DeviceIcon(icon: ImageVector, online: Boolean) {
    Box {
        Icon(icon, null)
        Box(
            Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape)
                .background(if (online) GoogleColors.Online else Color.Gray),
        )
    }
}

@Composable
private fun ReachBadge(svc: ServiceCheck) {
    val (text, color) = when (svc.reach) {
        Reach.Up -> "${svc.latencyMs} ms" to GoogleColors.Online
        Reach.Down -> "Unreachable" to MaterialTheme.colorScheme.error
        Reach.Checking -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        Reach.NotSetUp -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (svc.reach == Reach.Up || svc.reach == Reach.Down) Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}
