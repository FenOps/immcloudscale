package app.immcloudscale.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.pairing.Features
import app.immcloudscale.data.pairing.IPhoneInvite
import app.immcloudscale.data.pairing.InviteInfo
import app.immcloudscale.data.pairing.JoinRequestInfo
import app.immcloudscale.data.pairing.Member
import app.immcloudscale.data.pairing.PairCode
import app.immcloudscale.graph
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.QrCode
import app.immcloudscale.ui.components.SectionHeader
import java.time.Instant
import kotlinx.coroutines.launch

data class MembersUi(
    val requests: List<JoinRequestInfo> = emptyList(),
    val members: List<Member> = emptyList(),
    val features: Features? = null,
    val invites: List<InviteInfo> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class MembersActions(
    val onApprove: (JoinRequestInfo) -> Unit = {},
    val onDecline: (JoinRequestInfo) -> Unit = {},
    val onAdmin: (Member, Boolean) -> Unit = { _, _ -> },
    val onPairPhone: (Member) -> Unit = {},
    val onIPhone: (Member) -> Unit = {},
    val onMachineLearning: (Boolean) -> Unit = {},
    val onInvite: () -> Unit = {},
    val onCancelInvite: (InviteInfo) -> Unit = {},
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
    var inviting by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<InviteInfo?>(null) }
    var forIPhone by remember { mutableStateOf<Member?>(null) }
    var iphone by remember { mutableStateOf<IPhoneInvite?>(null) } // held only while the dialog is open
    var makingIPhone by remember { mutableStateOf<String?>(null) } // whose invite is on its way
    val ctx = LocalContext.current

    suspend fun load() {
        ui = runCatching {
            MembersUi(
                g.members.requests().filter { it.status == "pending" }, g.members.members(), runCatching { g.members.features() }.getOrNull(),
                runCatching { g.members.invites() }.getOrDefault(emptyList()), false,
            )
        }.getOrElse { MembersUi(loading = false, error = it.message ?: "Couldn't reach your server") }
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
            onIPhone = { forIPhone = it },
            onInvite = { inviting = true },
            onCancelInvite = { cancelling = it },
            onMachineLearning = { on -> act { g.members.setMachineLearning(on); snack.showSnackbar(if (on) "Turning on; this takes a few minutes" else "Turning off") } },
            onBack = onBack,
        ),
        makingIPhone,
    )

    shown?.let { (name, code) ->
        val deadline = inviteTime(code.expires)
        AlertDialog(
            onDismissRequest = { shown = null },
            properties = DialogProperties(dismissOnClickOutside = false), // an unsent invite isn't lost to a stray tap
            title = { Text("Invite for $name") },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (worksFromAnywhere(code.link)) "Send this invite to $name by message, or let them scan the QR code in the ImmCloudScale app. It works from anywhere."
                        else "Let $name scan this QR code in the ImmCloudScale app on your home Wi-Fi. (Away from home it needs remote access on the server.)",
                    )
                    if (code.link.isNotBlank()) QrCode(code.link)
                    Text(
                        code.code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { contentDescription = "Code " + code.code.toCharArray().joinToString(" ") },
                    )
                    Text(inviteValidity(deadline), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                Button(onClick = { shareInvite(ctx, me.pairedServerName, code) }) {
                    Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("Send invite")
                }
            },
            dismissButton = { TextButton(onClick = { shown = null }) { Text("Done") } },
        )
    }
    forIPhone?.let { m ->
        AlertDialog(
            onDismissRequest = { forIPhone = null },
            title = { Text("Set up ${m.name}'s iPhone or iPad?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${m.name} gets a message with the Immich app, the server address and a temporary password. It works for 15 minutes; Immich then asks them to choose their own.")
                    Text(
                        "Their current Photos password stops working as soon as you tap Create invite. After that, their locked folder opens only in Immich, not in this app.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    forIPhone = null
                    makingIPhone = m.name
                    act {
                        try {
                            iphone = runCatching { g.members.iphoneInvite(m.name) }
                                .getOrElse { throw Exception("Couldn't make ${m.name}'s iPhone invite. Check the server is running and try again.", it) }
                        } finally {
                            makingIPhone = null
                        }
                    }
                }) { Text("Create invite") }
            },
            dismissButton = { TextButton(onClick = { forIPhone = null }) { Text("Cancel") } },
        )
    }
    iphone?.let { inv ->
        val deadline = inviteTime(inv.expires)
        var sent by remember(inv) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { iphone = null },
            properties = DialogProperties(dismissOnClickOutside = false), // an unsent invite isn't lost to a stray tap
            title = { Text("iPhone or iPad invite for ${inv.person}") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Send this to ${inv.person}. It has the app to install, the server address and a temporary password. It isn't kept on this phone.")
                    Text(
                        inv.tempPassword, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .semantics { contentDescription = "Temporary password " + inv.tempPassword.toCharArray().joinToString(" ") },
                    )
                    Text(
                        if (deadline.isNotEmpty()) "Expires in 15 minutes (at $deadline). They should then choose a permanent password." else "Expires in 15 minutes. They should then choose a permanent password.",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    )
                    if (!sent) Text("If you close this without sending it, create a new invite later.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { sent = true; shareIPhoneInvite(ctx, me.pairedServerName, inv) }) {
                    Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("Send invite")
                }
            },
            dismissButton = { TextButton(onClick = { iphone = null }) { Text(if (sent) "Done" else "Close without sending") } },
        )
    }
    if (inviting) InviteDialog(onDismiss = { inviting = false }) { name, email ->
        inviting = false
        act { val c = g.members.invite(name, email); shown = c.name.ifBlank { name } to c }
    }
    cancelling?.let { inv ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("Cancel ${inv.name}'s invite?") },
            text = { Text("The link stops working right away. You can send a new one any time.") },
            confirmButton = { TextButton(onClick = { cancelling = null; act { g.members.cancelInvite(inv.code) } }) { Text("Cancel invite") } },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep") } },
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
fun MembersContent(ui: MembersUi, myName: String, snack: SnackbarHostState, a: MembersActions, makingIPhone: String? = null) {
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
            item {
                Button(onClick = a.onInvite, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Icon(Icons.Outlined.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Invite someone")
                }
            }
            if (ui.invites.isNotEmpty()) {
                item { SectionHeader("Open invites") }
                items(ui.invites.filter { runCatching { Instant.parse(it.expires).isAfter(Instant.now()) }.getOrDefault(true) }, key = { "i-" + it.code }) { inv ->
                    ListItem(
                        leadingContent = { LetterAvatar(inv.name, 40.dp) },
                        headlineContent = { Text(inv.name) },
                        supportingContent = {
                            Text("Valid until ${inviteTime(inv.expires)}" + (if (inv.createdBy.isNotBlank()) " · sent by ${inv.createdBy}" else ""))
                        },
                        trailingContent = { TextButton(onClick = { a.onCancelInvite(inv) }) { Text("Cancel invite") } },
                    )
                }
            }
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
                            IconButton(onClick = { a.onPairPhone(m) }) { Icon(Icons.Outlined.QrCode2, "Pair an Android phone for ${m.name}") }
                            if (makingIPhone == m.name) {
                                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "Making ${m.name}'s iPhone invite" }, strokeWidth = 2.dp)
                                }
                            } else {
                                IconButton(onClick = { a.onIPhone(m) }, enabled = makingIPhone == null) {
                                    Icon(Icons.Outlined.PhoneIphone, "Set up an iPhone or iPad for ${m.name}")
                                }
                            }
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

/** Who to invite: their username (new people are created on the server) and, optionally, their email. */
@Composable
private fun InviteDialog(onDismiss: () -> Unit, onInvite: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    val badStart = name.isNotEmpty() && !name.first().isLetter()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite someone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You'll get an invite to send by message. It works once, for 15 minutes.")
                OutlinedTextField(name, { name = it.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' }.take(32) }, label = { Text("Username") },
                    placeholder = { Text("nina") }, singleLine = true, isError = badStart,
                    supportingText = { Text(if (badStart) "Start with a letter" else "Their name on the server; an existing member gets a new link") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Email (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = name.length >= 2 && !badStart, onClick = { onInvite(name, email) }) { Text("Create invite") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
