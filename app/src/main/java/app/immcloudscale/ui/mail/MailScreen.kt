package app.immcloudscale.ui.mail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.mail.MailItem
import app.immcloudscale.data.mail.MailStatus
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.EmptyState
import app.immcloudscale.ui.components.ErrorState
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.Pullable
import app.immcloudscale.ui.components.graphViewModel
import app.immcloudscale.ui.photos.SelectCircle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The mail tab: search everything archived on the NAS from all of your mailboxes, read it, delete it. */
@Composable
fun MailScreen(onOpen: (MailItem) -> Unit, onMailboxes: () -> Unit, onAccount: () -> Unit, accountName: String, bottomPadding: PaddingValues) {
    val vm = graphViewModel { MailViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }
    LaunchedEffect(st.message) { st.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    BackHandler(st.selected.isNotEmpty()) { vm.clearSelection() }

    Scaffold(snackbarHost = { SnackbarHost(snack, Modifier.padding(bottom = bottomPadding.calculateBottomPadding())) }) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            val status = st.status
            if (st.selected.isNotEmpty()) {
                MailSelectionBar(st.selected.size, st.selected.size == st.items.size, vm::selectAll, vm::clearSelection) { confirmDelete = true }
            } else {
                MailSearchBar(st.query.text, accountName, enabled = status?.accounts?.isNotEmpty() == true, onSearch = vm::search, onMailboxes = onMailboxes, onAccount = onAccount)
            }
            when {
                status == null && st.error != null -> Pullable { ErrorState(st.error!!, vm::refresh) }
                status == null -> CenteredLoading()
                !status.configured || !status.reachable -> NotSetUp(status)
                status.accounts.isEmpty() -> EmptyState(
                    Icons.Outlined.Mail, "Keep your email on your NAS",
                    "Add your Gmail, Outlook or any other mailbox. Everything is copied to your home server, where only you can search it." +
                        if (status.unassigned.isNotEmpty()) " ${status.unassigned.size} mailbox${if (status.unassigned.size == 1) " is" else "es are"} waiting to be given to someone." else "",
                    action = "Add a mailbox", onAction = onMailboxes,
                )
                else -> {
                    Filters(st, status, vm)
                    MailList(st, vm, onOpen, bottomPadding)
                }
            }
        }
    }

    if (confirmDelete) {
        val n = st.selected.size
        val boxes = st.items.filter { it.key() in st.selected }.map { it.mailbox }.distinct()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (n == 1) "Delete 1 email?" else "Delete $n emails?") },
            text = { Text(deleteExplanation(boxes)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    st.needPassword?.let { id ->
        val box = st.status?.accounts?.firstOrNull { it.id == id }?.email ?: "this mailbox"
        MailboxPasswordDialog(box, onDismiss = vm::dismissPassword) { vm.savePasswordAndDelete(id, it) }
    }
    if (st.deleting) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/** What deleting does, in words: from the NAS and from the mailboxes the emails came from. */
fun deleteExplanation(mailboxes: List<String>): String {
    val names = mailboxes.joinToString(", ").ifBlank { "your mail provider" }
    val gmail = mailboxes.any { it.endsWith("@gmail.com") || it.endsWith("@googlemail.com") }
    return "They're deleted from your NAS and from $names." +
        if (gmail) " Gmail keeps them in its Trash for 30 days." else " Your mail provider may keep them in its Trash for a while."
}

@Composable
private fun NotSetUp(status: MailStatus) {
    EmptyState(
        Icons.Outlined.Mail,
        if (status.configured) "Can't reach the email archive" else "Email isn't set up on your server yet",
        if (status.configured) "Your server can't reach Bichon, the email archive. Check it's running on the NAS (sudo docker ps), then pull down to try again."
        else "The person who runs your server installs Bichon (the email archive) on the NAS, then runs: sudo ics-server mail connect",
    )
}

@Composable
private fun MailSearchBar(query: String, accountName: String, enabled: Boolean, onSearch: (String) -> Unit, onMailboxes: () -> Unit, onAccount: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(query) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = text,
            onValueChange = { text = it; onSearch(it) },
            enabled = enabled,
            placeholder = { Text("Search mail") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = ""; onSearch("") }) { Icon(Icons.Filled.Close, "Clear search") } },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
            modifier = Modifier.weight(1f).height(56.dp),
        )
        IconButton(onClick = onMailboxes) { Icon(Icons.Outlined.ManageAccounts, "Mailboxes") }
        IconButton(onClick = onAccount) { LetterAvatar(accountName) }
    }
}

@Composable
private fun MailSelectionBar(count: Int, all: Boolean, onSelectAll: () -> Unit, onClear: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear selection") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        IconButton(onClick = if (all) onClear else onSelectAll) {
            Icon(if (all) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, if (all) "Deselect all" else "Select all")
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete") }
    }
}

@Composable
private fun Filters(st: MailState, status: MailStatus, vm: MailViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer)
        if (status.accounts.size > 1) {
            FilterChip(st.query.account == null, { vm.filterAccount(null) }, { Text("All mailboxes") }, shape = RoundedCornerShape(8.dp), colors = chip)
            status.accounts.forEach { a ->
                FilterChip(st.query.account == a.id, { vm.filterAccount(a.id) }, { Text(a.email) }, shape = RoundedCornerShape(8.dp), colors = chip)
            }
        }
        FilterChip(
            st.query.hasAttachment, { vm.filterAttachments(!st.query.hasAttachment) }, { Text("Has attachments") },
            leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(8.dp), colors = chip,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MailList(st: MailState, vm: MailViewModel, onOpen: (MailItem) -> Unit, bottomPadding: PaddingValues) {
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 10 } == true } }
    LaunchedEffect(nearEnd, st.items.size) { if (nearEnd) vm.loadMore() }
    PullToRefreshBox(st.loading && st.items.isNotEmpty() && st.page <= 1, vm::refresh, Modifier.fillMaxSize()) {
        when {
            st.items.isEmpty() && st.loading -> CenteredLoading()
            st.items.isEmpty() && st.error != null -> Pullable { ErrorState(st.error, vm::refresh) }
            st.items.isEmpty() && st.query.text.isNotBlank() -> Pullable { EmptyState(Icons.Outlined.SearchOff, "No emails found", "Nothing matches \"${st.query.text}\".") }
            st.items.isEmpty() -> Pullable {
                EmptyState(Icons.Outlined.Mail, "No emails yet", "Your mail is being copied to the NAS. A big mailbox can take a few hours the first time.")
            }
            else -> LazyColumn(state = list, contentPadding = PaddingValues(bottom = bottomPadding.calculateBottomPadding() + 16.dp)) {
                items(st.items, key = { it.key() }) { m ->
                    val selected = m.key() in st.selected
                    MailRow(
                        m, selected, selecting = st.selected.isNotEmpty(),
                        Modifier.combinedClickable(
                            onClick = { if (st.selected.isNotEmpty()) vm.toggle(m) else onOpen(m) },
                            onLongClick = { vm.toggle(m) },
                            onLongClickLabel = "Select",
                        ).semantics { this.selected = selected },
                    )
                }
                if (st.more) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                }
            }
        }
    }
}

@Composable
private fun MailRow(m: MailItem, selected: Boolean, selecting: Boolean, modifier: Modifier) {
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        leadingContent = {
            if (selected) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { SelectCircle(true, Modifier.size(24.dp)) }
            else LetterAvatar(m.sender.ifBlank { "?" }, 40.dp, contentDescription = "")
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.sender.ifBlank { "(no sender)" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(mailDate(m.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.subject.ifBlank { "(no subject)" }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (m.attachments > 0) Icon(Icons.Outlined.AttachFile, "Has attachments", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(m.preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}

/** "14:05" today, "Nov 3" this year, else "3/11/2024": like a mail app's list. */
fun mailDate(iso: String, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
    val at = runCatching { Instant.parse(iso).atZone(zone) }.getOrNull() ?: return ""
    val today = now.atZone(zone)
    return when {
        at.toLocalDate() == today.toLocalDate() -> at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        at.year == today.year -> at.format(DateTimeFormatter.ofPattern("MMM d"))
        else -> at.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    }
}

/** Asks once for a mailbox's password, so the server can delete on the mail server too. */
@Composable
fun MailboxPasswordDialog(mailbox: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password for $mailbox") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This mailbox was added in the archive's own page, so your server doesn't have its password yet. It needs it once to delete emails on the mail server too.")
                Text("For Gmail, Outlook, Yahoo and iCloud, use an app password.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(pass) }, enabled = pass.isNotBlank()) { Text("Save and delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
