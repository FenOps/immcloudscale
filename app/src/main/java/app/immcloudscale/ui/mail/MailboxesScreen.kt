package app.immcloudscale.ui.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.mail.MailStatus
import app.immcloudscale.data.mail.Mailbox
import app.immcloudscale.data.mail.NewMailbox
import app.immcloudscale.graph
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.ErrorState
import app.immcloudscale.ui.components.SectionHeader
import app.immcloudscale.ui.components.formatAgo
import app.immcloudscale.ui.components.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

/** Mail providers with their usual server and what kind of password they need. */
enum class MailProvider(val label: String, val domains: List<String>, val hint: String) {
    Gmail("Gmail", listOf("gmail.com", "googlemail.com"),
        "Use an app password, not your Google password: turn on 2-Step Verification, then create one at myaccount.google.com/apppasswords. Also check IMAP is on in Gmail's settings."),
    Outlook("Outlook / Hotmail", listOf("outlook.com", "hotmail.com", "live.com", "msn.com"),
        "Use an app password from account.microsoft.com (Security, Advanced security options)."),
    Yahoo("Yahoo", listOf("yahoo.com", "ymail.com"), "Use an app password from Yahoo's Account security page."),
    ICloud("iCloud", listOf("icloud.com", "me.com", "mac.com"), "Use an app-specific password from account.apple.com (Sign-In and Security)."),
    Other("Other (IMAP)", emptyList(),
        "Your mail provider's help or settings page lists its incoming (IMAP) server, usually on port 993 with SSL. Use the mailbox's own password.");

    companion object {
        fun of(email: String): MailProvider = entries.firstOrNull { p -> p.domains.any { email.trim().lowercase().endsWith("@$it") } } ?: Other
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxesScreen(onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val me by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var status by remember { mutableStateOf<MailStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var people by remember { mutableStateOf<List<String>>(emptyList()) }
    var removing by remember { mutableStateOf<Mailbox?>(null) }
    var password by remember { mutableStateOf<Mailbox?>(null) }
    var assigning by remember { mutableStateOf<Mailbox?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { g.mail.status() }.onSuccess { status = it; error = null }.onFailure { error = it.message ?: "Couldn't reach your server" }
    }
    fun act(done: String? = null, block: suspend () -> Unit) = scope.launch {
        busy = true
        try {
            block()
            done?.let { snack.showSnackbar(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            snack.showSnackbar(e.message ?: "Something went wrong")
        } finally {
            busy = false
        }
        load()
    }
    LaunchedEffect(Unit) {
        load()
        people = runCatching { g.members.members().map { it.name } }.getOrDefault(emptyList()) // admins only; others get a 403
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mailboxes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            if (status?.configured == true) ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add a mailbox") })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val s = status
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                s == null && error != null -> ErrorState(error!!, { scope.launch { load() } })
                s == null -> CenteredLoading()
                !s.configured -> EmptyState(Icons.Outlined.Mail, "Email isn't set up on your server yet", "On the NAS, install Bichon, then run: sudo ics-server mail connect")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { SectionHeader("Your mailboxes") }
                    if (s.accounts.isEmpty()) item {
                        Text(
                            "None yet. Add a mailbox: its mail is copied to your NAS, and you can search it in Mail.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    items(s.accounts, key = { "a" + it.id }) { m ->
                        MailboxRow(m) { close ->
                            DropdownMenuItem(text = { Text(if (m.paused) "Resume copying" else "Pause copying") }, onClick = {
                                close(); act(if (m.paused) "Copying again" else "Paused: nothing new is copied") { g.mail.pause(m.id, !m.paused) }
                            })
                            if (!m.deletesOnServer) DropdownMenuItem(text = { Text("Enter password (to delete on the mail server)") }, onClick = { close(); password = m })
                            DropdownMenuItem(text = { Text("Remove from NAS", color = MaterialTheme.colorScheme.error) }, onClick = { close(); removing = m })
                        }
                    }
                    if (s.unassigned.isNotEmpty()) {
                        item { SectionHeader("Not given to anyone") }
                        item {
                            Text(
                                "Added in the archive's own web page. Give each to the person it belongs to: only they will see it.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        items(s.unassigned, key = { "u" + it.id }) { m ->
                            MailboxRow(m) { close -> DropdownMenuItem(text = { Text("Give to…") }, onClick = { close(); assigning = m }) }
                        }
                    }
                    item { Box(Modifier.padding(48.dp)) }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.TopCenter))
        }
    }

    if (adding) AddMailboxDialog(people, me.ncUser.ifBlank { me.immichUserName }, onDismiss = { adding = false }) { m ->
        adding = false
        act("Added ${m.email}. Copying starts now; a big mailbox can take a few hours.") { g.mail.add(m) }
    }
    removing?.let { m ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${m.email} from your NAS?") },
            text = { Text("Copying stops and all ${m.count} archived emails are deleted from your NAS. Nothing changes in the mailbox itself.") },
            confirmButton = { TextButton(onClick = { removing = null; act("Removed ${m.email}") { g.mail.remove(m.id) } }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    password?.let { m ->
        MailboxPasswordDialog(m.email, onDismiss = { password = null }) { p -> password = null; act("Saved: deleting now also deletes on the mail server") { g.mail.setPassword(m.id, p) } }
    }
    assigning?.let { m ->
        AlertDialog(
            onDismissRequest = { assigning = null },
            title = { Text("Whose is ${m.email}?") },
            text = {
                Column {
                    if (people.isEmpty()) Text("Only admins can give out mailboxes.")
                    people.forEach { p ->
                        ListItem(
                            headlineContent = { Text(p) },
                            modifier = Modifier.fillMaxWidth().clickable { assigning = null; act("${m.email} is now $p's") { g.mail.assign(m.id, p) } },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { assigning = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MailboxRow(m: Mailbox, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val last = m.lastSync?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Mail, null) },
        headlineContent = { Text(m.email) },
        supportingContent = {
            Text(
                listOfNotNull(
                    "${m.count} emails · ${formatBytes(m.bytes)}",
                    when {
                        m.paused -> "paused"
                        m.syncing -> "copying now"
                        last != null -> "checked ${formatAgo(last)}"
                        else -> null
                    },
                    m.error?.let { "problem: $it" },
                ).joinToString(" · "),
                color = if (m.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Options for ${m.email}") }
                DropdownMenu(open, { open = false }) { menu { open = false } }
            }
        },
    )
}

/** Adds a mailbox: the provider fills in its server; any other provider needs its IMAP server. */
@Composable
internal fun AddMailboxDialog(people: List<String>, me: String, onDismiss: () -> Unit, onAdd: (NewMailbox) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(MailProvider.Gmail) }
    var picked by remember { mutableStateOf(false) } // chosen by hand: typing the address doesn't change it any more
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("993") }
    var security by remember { mutableStateOf("ssl") }
    var login by remember { mutableStateOf("") }
    var forPerson by remember { mutableStateOf("") }
    val other = provider == MailProvider.Other
    val ok = email.contains("@") && pass.isNotBlank() && (!other || host.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a mailbox") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Picker("Provider", provider.label, MailProvider.entries.map { it.label }) { i -> provider = MailProvider.entries[i]; picked = true }
                OutlinedTextField(
                    email, { email = it; if (!picked) provider = MailProvider.of(it) }, label = { Text("Email address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    pass, { pass = it }, label = { Text(if (other) "Password" else "App password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                Text(provider.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (other) {
                    OutlinedTextField(host, { host = it.trim() }, label = { Text("Incoming (IMAP) server") }, placeholder = { Text("mail.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    val kinds = listOf("ssl" to "SSL/TLS", "starttls" to "STARTTLS", "none" to "None (not recommended)")
                    Picker("Security", kinds.first { it.first == security }.second, kinds.map { it.second }) { i ->
                        security = kinds[i].first
                        if (port == "993" || port == "143") port = if (security == "ssl") "993" else "143"
                    }
                    OutlinedTextField(login, { login = it.trim() }, label = { Text("Username (if not the email address)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (people.size > 1) {
                    Picker("Whose mailbox", forPerson.ifBlank { "$me (you)" }, people.map { if (it == me) "$it (you)" else it }) { i ->
                        forPerson = people[i].takeIf { it != me }.orEmpty()
                    }
                }
                Text(
                    "Deleting an email in the app deletes it here and in the mailbox too, so your server keeps this password (only on the NAS).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                onAdd(
                    if (other) NewMailbox(email.trim(), pass, host, port.toIntOrNull() ?: 993, security, login, forPerson)
                    else NewMailbox(email.trim(), pass, forPerson = forPerson),
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A read-only field that opens a list to pick from. */
@Composable
private fun Picker(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Over the field, so a tap anywhere on it opens the list.
        Box(Modifier.matchParentSize().clickable(onClickLabel = "Choose $label") { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}
