package app.immcloudscale.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.pairing.Features
import app.immcloudscale.data.pairing.JoinRequestInfo
import app.immcloudscale.data.pairing.Member
import app.immcloudscale.data.pairing.PairCode
import app.immcloudscale.graph
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.QrCode
import app.immcloudscale.ui.components.SectionHeader
import kotlinx.coroutines.launch

data class MembersUi(
    val requests: List<JoinRequestInfo> = emptyList(),
    val members: List<Member> = emptyList(),
    val features: Features? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class MembersActions(
    val onApprove: (JoinRequestInfo) -> Unit = {},
    val onDecline: (JoinRequestInfo) -> Unit = {},
    val onAdmin: (Member, Boolean) -> Unit = { _, _ -> },
    val onPairPhone: (Member) -> Unit = {},
    val onMachineLearning: (Boolean) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** For admins: sign-up requests to approve, and who's on the server (and who else is an admin). */
@Composable
fun MembersScreen(onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val me by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var ui by remember { mutableStateOf(MembersUi()) }
    var shown by remember { mutableStateOf<Pair<String, PairCode>?>(null) }
    var declining by remember { mutableStateOf<JoinRequestInfo?>(null) }
    var makingAdmin by remember { mutableStateOf<Member?>(null) }

    suspend fun load() {
        ui = runCatching {
            MembersUi(g.members.requests().filter { it.status == "pending" }, g.members.members(), runCatching { g.members.features() }.getOrNull(), false)
        }
            .getOrElse { MembersUi(loading = false, error = it.message ?: "Couldn't reach your server") }
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        runCatching { block() }.onFailure { snack.showSnackbar(it.message ?: "Something went wrong") }
        load()
    }
    LaunchedEffect(Unit) { load() }

    MembersContent(
        ui, me.ncUser.ifBlank { me.immichUserName }, snack,
        MembersActions(
            onApprove = { r -> act { shown = r.name to g.members.approve(r.id) } },
            onDecline = { declining = it },
            onAdmin = { m, on -> if (on) makingAdmin = m else act { g.members.setAdmin(m.name, false) } },
            onPairPhone = { m -> act { shown = m.name to g.members.pairCode(m.name) } },
            onMachineLearning = { on -> act { g.members.setMachineLearning(on); snack.showSnackbar(if (on) "Turning on; this takes a few minutes" else "Turning off") } },
            onBack = onBack,
        ),
    )

    shown?.let { (name, code) ->
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text("Connect $name's phone") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("If $name asked from the app, it connects by itself. Otherwise scan this in ImmCloudScale, or type the server and code.")
                    if (code.link.isNotBlank()) QrCode(code.link)
                    Text(code.code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                    Text("Server: ${code.server}", style = MaterialTheme.typography.bodySmall)
                    Text("Valid for 15 minutes, one phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Done") } },
        )
    }
    makingAdmin?.let { m ->
        AlertDialog(
            onDismissRequest = { makingAdmin = null },
            title = { Text("Make ${m.name} an admin?") },
            text = { Text("They'll be able to approve sign-ups, manage members and pair phones for anyone, which gives full access to their photos and files.") },
            confirmButton = { TextButton(onClick = { makingAdmin = null; act { g.members.setAdmin(m.name, true) } }) { Text("Make admin") } },
            dismissButton = { TextButton(onClick = { makingAdmin = null }) { Text("Cancel") } },
        )
    }
    declining?.let { r ->
        AlertDialog(
            onDismissRequest = { declining = null },
            title = { Text("Decline ${r.name}?") },
            text = { Text("No account is created. They can ask again later.") },
            confirmButton = { TextButton(onClick = { declining = null; act { g.members.decline(r.id) } }) { Text("Decline") } },
            dismissButton = { TextButton(onClick = { declining = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersContent(ui: MembersUi, myName: String, snack: SnackbarHostState, a: MembersActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Members") },
                navigationIcon = { IconButton(onClick = a.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (ui.error != null && ui.members.isEmpty()) {
            Column(Modifier.padding(pad)) { EmptyState(Icons.Outlined.PersonAdd, "Can't load members", ui.error) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item { SectionHeader("Sign-up requests") }
            if (ui.requests.isEmpty()) item {
                Text(
                    if (ui.loading) "Loading…" else "No one is waiting. New people tap \"Ask to join\" in the app while on your home Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(ui.requests, key = { "r-" + it.id }) { r ->
                ListItem(
                    leadingContent = { LetterAvatar(r.name, 40.dp) },
                    headlineContent = { Text(r.name) },
                    supportingContent = { Text(listOf(r.email, r.device).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Asked from the app" }) },
                )
                Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { a.onApprove(r) }) { Text("Approve") }
                    OutlinedButton(onClick = { a.onDecline(r) }) { Text("Decline") }
                }
            }
            item { SectionHeader("People") }
            items(ui.members, key = { "m-" + it.name }) { m ->
                ListItem(
                    leadingContent = { LetterAvatar(m.name, 40.dp) },
                    headlineContent = { Text(if (m.name == myName) "${m.name} (you)" else m.name) },
                    supportingContent = { Text("${if (m.isAdmin) "Admin · " else ""}${m.devices} phone${if (m.devices == 1) "" else "s"}") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { a.onPairPhone(m) }) { Icon(Icons.Outlined.QrCode2, "Pair a phone for ${m.name}") }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Admin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                // You can't remove your own admin rights here (another admin can).
                                Switch(
                                    m.isAdmin, { on -> a.onAdmin(m, on) }, enabled = m.name != myName,
                                    modifier = Modifier.semantics { contentDescription = "${m.name} is an admin" },
                                )
                            }
                        }
                    },
                )
            }
            ui.features?.let { f ->
                item { SectionHeader("Server features") }
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Face, null) },
                        headlineContent = { Text("Face recognition, smart search and duplicates") },
                        supportingContent = {
                            Text(
                                when {
                                    f.applying -> "Applying the change on the server…"
                                    f.memoryGB in 0.1..5.9 -> "Needs about 2 GB of extra memory; this server has ${"%.1f".format(f.memoryGB)} GB, so it may get slow."
                                    else -> "Finds people, searches photos by what's in them (\"beach at sunset\") and spots duplicates."
                                },
                            )
                        },
                        trailingContent = { Switch(f.machineLearning, { a.onMachineLearning(it) }, enabled = !f.applying) },
                    )
                }
            }

        }
    }
}
