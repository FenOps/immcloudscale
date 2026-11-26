package app.immcloudscale.ui.network

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.AppSettings
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.SectionHeader
import app.immcloudscale.ui.components.graphViewModel
import app.immcloudscale.ui.theme.GoogleColors

class NetworkActions(
    val onRecheck: () -> Unit = {},
    val onAccount: () -> Unit = {},
)

@Composable
fun NetworkScreen(onAccount: () -> Unit, bottomPadding: PaddingValues) {
    val ctx = LocalContext.current
    val vm = graphViewModel { NetworkViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ts by vm.ts.collectAsStateWithLifecycle()
    val atHome by vm.atHome.collectAsStateWithLifecycle()
    val remote = RemoteAccessActions(
        onEnabled = vm::setTsEnabled,
        onConnect = vm::tsConnect,
        onDisconnect = vm::tsDisconnect,
        onSignIn = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        onSignOut = vm::tsSignOut,
    )
    NetworkContent(s, ui, NetworkActions(onRecheck = { vm.checkServices() }, onAccount = onAccount), bottomPadding) {
        RemoteAccessCard(ts, s, atHome, remote)
    }
}

/** Remote access (Tailscale, inside the app) and whether the NAS's services answer from where the phone is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkContent(
    s: AppSettings,
    ui: NetworkUi,
    a: NetworkActions,
    bottomPadding: PaddingValues = PaddingValues(0.dp),
    remoteCard: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
                actions = { IconButton(onClick = a.onAccount) { LetterAvatar(s.immichUserName.ifBlank { s.ncUser.ifBlank { "Me" } }, 32.dp) } },
            )
        },
    ) { pad ->
        if (!remoteAccessAvailable(s)) {
            EmptyState(
                Icons.Outlined.Hub, "Reach your NAS from anywhere",
                "Pair this phone with your ImmCloudScale server. Remote access is then set up for you.",
                Modifier.padding(pad).padding(bottom = bottomPadding.calculateBottomPadding()),
                action = "Pair with your server", onAction = a.onAccount,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()), contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 16.dp)) {
            item { remoteCard() }
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
        }
    }
}

@Composable
private fun ReachBadge(svc: ServiceCheck) {
    val (text, color) = when (svc.reach) {
        Reach.Up -> "${svc.latencyMs} ms" to Color(0xFF137333) // darker than the dot, for readable small text
        Reach.Down -> "Unreachable" to MaterialTheme.colorScheme.error
        Reach.Checking -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
        Reach.NotSetUp -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (svc.reach == Reach.Up || svc.reach == Reach.Down) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (svc.reach == Reach.Up) GoogleColors.Online else color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}
