package app.immcloudscale.ui.mail

import android.content.Intent
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import app.immcloudscale.data.HttpException
import app.immcloudscale.data.mail.MailAttachment
import app.immcloudscale.data.mail.MailItem
import app.immcloudscale.data.mail.MailMessage
import app.immcloudscale.graph
import app.immcloudscale.ui.components.CenteredLoading
import app.immcloudscale.ui.components.ErrorState
import app.immcloudscale.ui.components.LetterAvatar
import app.immcloudscale.ui.components.formatBytes
import app.immcloudscale.ui.files.openLocalFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The message opened from the mail list (set just before navigating). */
object MailSession {
    var item: MailItem? = null
    /** Deleted from the message screen: the list takes them out when it's shown again. */
    val deleted = mutableListOf<Pair<String, String>>()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailMessageScreen(onBack: () -> Unit) {
    val item = MailSession.item
    if (item == null) { // e.g. the app was restarted on this screen
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var remote by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<MailMessage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(remote, attempt) {
        error = null
        runCatching { g.mail.message(item.account, item.id, remote) }.onSuccess { msg = it }.onFailure { error = it.message ?: "Couldn't open this email" }
    }
    fun delete() {
        busy = true
        scope.launch {
            try {
                val r = g.mail.delete(listOf(item.account to item.id))
                if (r.failed.isEmpty()) {
                    MailSession.deleted += item.account to item.id
                    onBack()
                } else snack.showSnackbar("Couldn't delete: ${r.failed.first().error}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is HttpException && e.code == 409) askPassword = true else snack.showSnackbar(e.message ?: "Couldn't delete")
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirmDelete = true }, enabled = !busy) { Icon(Icons.Outlined.Delete, "Delete") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        val m = msg
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                m == null && error != null -> ErrorState(error!!, { attempt++ })
                m == null -> CenteredLoading()
                else -> MessageBody(m, remote, downloading, onShowImages = { remote = true }, onAttachment = { a ->
                    if (a.hash in downloading) return@MessageBody
                    scope.launch {
                        downloading = downloading + a.hash
                        try {
                            val f = File(ctx.cacheDir, "mail/${a.hash}/${safeName(a.name ?: "attachment")}")
                            if (f.length() != a.size || a.size == 0L) g.mail.attachment(item.account, item.id, a.hash, f, a.size)
                            openLocalFile(ctx, f, a.type)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            snack.showSnackbar(e.message ?: "Couldn't open the attachment")
                        } finally {
                            downloading = downloading - a.hash
                        }
                    }
                })
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this email?") },
            text = { Text(deleteExplanation(listOf(item.mailbox))) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; delete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (askPassword) {
        MailboxPasswordDialog(item.mailbox.ifBlank { "this mailbox" }, onDismiss = { askPassword = false }) { pass ->
            askPassword = false
            scope.launch {
                runCatching { g.mail.setPassword(item.account, pass) }
                    .onSuccess { delete() }
                    .onFailure { snack.showSnackbar(it.message ?: "That password didn't work") }
            }
        }
    }
}

private fun safeName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120).ifBlank { "attachment" }

@Composable
private fun MessageBody(m: MailMessage, remote: Boolean, downloading: Set<String>, onShowImages: () -> Unit, onAttachment: (MailAttachment) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer { Text(m.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.titleLarge) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LetterAvatar(m.sender.ifBlank { "?" }, 40.dp, contentDescription = "")
                Column(Modifier.weight(1f)) {
                    Text(m.sender.ifBlank { "(no sender)" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (m.fromName != null && m.from.isNotBlank()) {
                        Text(m.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "To " + (m.to + m.cc.orEmpty()).joinToString(", ").ifBlank { m.mailbox },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    mailDate(m.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = fullDate(m.date) },
                )
            }
            val files = m.attachmentList.filter { !it.inline }
            if (files.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.forEach { a ->
                        AssistChip(
                            onClick = { onAttachment(a) },
                            label = { Text("${a.name ?: "attachment"} · ${formatBytes(a.size)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                if (a.hash in downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp))
                            },
                            modifier = Modifier.widthIn(max = 280.dp),
                        )
                    }
                }
            }
            if (m.hasRemote && !remote) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Image, null, Modifier.size(20.dp))
                        Text(
                            "Images are hidden: senders use them to see when you read their mail.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                        TextButton(onClick = onShowImages) { Text("Show") }
                    }
                }
            }
        }
        val html = m.html
        if (!html.isNullOrBlank()) {
            MailWebView(html, allowNetwork = remote, Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
        } else {
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(m.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** An email's HTML, shown safely: no scripts, no files, nothing from the web unless asked; links open in the browser. */
@Composable
private fun MailWebView(html: String, allowNetwork: Boolean, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(Color.WHITE) // emails are designed for white paper
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = MailWebClient()
            }
        },
        update = { w ->
            w.settings.blockNetworkLoads = !allowNetwork
            (w.webViewClient as? MailWebClient)?.allowNetwork = allowNetwork
            val page = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body style=\"margin:12px\">$html</body></html>"
            if (w.tag != page) {
                w.tag = page
                w.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
    )
}

private fun fullDate(iso: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { Instant.parse(iso).atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)) }.getOrDefault("")

/**
 * Only a link the person tapped opens, and only to the web or to write an email: nothing in the mail itself (a
 * refresh, a form) can start an app or reach a tracker. Images from the web load only once allowed.
 */
private class MailWebClient : WebViewClient() {
    @Volatile var allowNetwork = false // read on WebView's own thread

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.hasGesture() && request.url.scheme?.lowercase() in setOf("http", "https", "mailto")) {
            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val web = request.url.scheme?.lowercase() in setOf("http", "https")
        val ok = allowNetwork && request.method.equals("GET", ignoreCase = true)
        return if (web && !ok) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
    }
}
