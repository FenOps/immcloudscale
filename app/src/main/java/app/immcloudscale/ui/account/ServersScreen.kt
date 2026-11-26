package app.immcloudscale.ui.account

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.immcloudscale.data.normalizeServerUrl
import app.immcloudscale.graph
import app.immcloudscale.ui.components.Pinwheel
import app.immcloudscale.ui.theme.GoogleColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Connects (or disconnects) the Immich and Nextcloud servers. Also the first-run screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(onBack: (() -> Unit)?, onNetwork: () -> Unit, onDone: () -> Unit) {
    val g = LocalContext.current.graph
    val s by g.settingsState.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = { Text(if (onBack == null) "" else "Servers") },
            actions = { if (onBack == null) TextButton(onClick = onDone) { Text(if (s.immichConfigured || s.nextcloudConfigured) "Done" else "Skip") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onBack == null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Pinwheel(56.dp)
                    Text("Welcome to ImmCloudScale", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    Text(
                        "Your photos, files and home network, all on your own NAS.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            PairCard(onPaired = { if (onBack == null) onDone() else onBack() })
            Text(
                "Or connect servers you already run",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            ImmichCard()
            NextcloudCard()
        }
    }
}

@Composable
private fun ServerCard(
    icon: ImageVector, title: String, product: String, connected: Boolean, status: String,
    content: @Composable () -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(product, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (connected) Icon(Icons.Filled.CheckCircle, "Connected", tint = GoogleColors.Online)
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImmichCard() {
    val g = LocalContext.current.graph
    val s by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(s.immichUrl) }
    var apiKeyMode by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ServerCard(
        Icons.Outlined.PhotoLibrary, "Photos", "Immich", s.immichConfigured,
        if (s.immichConfigured) "Signed in as ${s.immichEmail.ifBlank { s.immichUserName }} on ${s.immichUrl}" else "Photo & video library with backup, people, places and smart search",
    ) {
        if (s.immichConfigured) {
            OutlinedButton(onClick = {
                scope.launch {
                    g.settings.update { it.copy(immichToken = "", immichUserName = "", immichEmail = "", backupEnabled = false) }
                    g.timelineCache.clear() // signed out: the saved timeline goes too
                }
            }) {
                Text("Sign out")
            }
            return@ServerCard
        }
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("http://192.168.1.10:2283") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!apiKeyMode, { apiKeyMode = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Email") }
            SegmentedButton(apiKeyMode, { apiKeyMode = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("API key") }
        }
        if (apiKeyMode) {
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation())
        } else {
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            enabled = !busy && url.isNotBlank() && (if (apiKeyMode) apiKey.isNotBlank() else email.isNotBlank() && password.isNotBlank()),
            onClick = {
                busy = true; error = null
                scope.launch {
                    val base = normalizeServerUrl(url)
                    try {
                        // Save the URL first so the shared HTTP client sends credentials to it.
                        if (apiKeyMode) {
                            g.settings.update { it.copy(immichUrl = base, immichToken = apiKey.trim(), immichTokenIsApiKey = true, pairedServer = "", pairedServerId = "") }
                            val me = g.immich.me()
                            g.settings.update { it.copy(immichUserName = me.name, immichEmail = me.email) }
                        } else {
                            g.settings.update { it.copy(immichUrl = base, immichToken = "", pairedServer = "", pairedServerId = "") }
                            val login = g.immich.login(email.trim(), password)
                            g.settings.update {
                                it.copy(immichToken = login.accessToken, immichTokenIsApiKey = false, immichUserName = login.name, immichEmail = login.userEmail)
                            }
                        }
                    } catch (e: Exception) {
                        g.settings.update { it.copy(immichToken = "") }
                        error = e.message ?: "Couldn't connect"
                    }
                    busy = false
                }
            },
        ) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text("Connect")
        }
    }
}

@Composable
private fun NextcloudCard() {
    val ctx = LocalContext.current
    val g = ctx.graph
    val s by g.settingsState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(s.ncUrl) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var waitingForBrowser by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun save(base: String, u: String, p: String) {
        g.settings.update { it.copy(ncUrl = base, ncUser = u, ncAppPassword = p, ncUserId = "", pairedServer = "", pairedServerId = "") }
        try {
            // Signing in with an email works, but files live under the account's internal ID.
            val id = runCatching { g.nextcloud.currentUserId() }.getOrDefault(u)
            g.settings.update { it.copy(ncUserId = id) }
            g.nextcloud.list("/")
        } catch (e: Exception) {
            g.settings.update { it.copy(ncAppPassword = "") }
            throw e
        }
    }

    ServerCard(
        Icons.Outlined.Folder, "Files", "Nextcloud", s.nextcloudConfigured,
        if (s.nextcloudConfigured) "Signed in as ${s.ncUser} on ${s.ncUrl}" else "Files, folders, sharing links and offline copies",
    ) {
        if (s.nextcloudConfigured) {
            OutlinedButton(onClick = { scope.launch { g.settings.update { it.copy(ncAppPassword = "") } } }) { Text("Sign out") }
            return@ServerCard
        }
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, placeholder = { Text("https://cloud.example.com") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        Button(
            enabled = url.isNotBlank() && waitingForBrowser == null,
            onClick = {
                error = null
                waitingForBrowser = scope.launch {
                    try {
                        val base = normalizeServerUrl(url)
                        val (loginUrl, poll) = g.nextcloud.startLoginFlow(base)
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)))
                        val r = poll()
                        save(normalizeServerUrl(r.server), r.loginName, r.appPassword)
                    } catch (e: Exception) {
                        error = e.message ?: "Sign-in failed"
                    }
                    waitingForBrowser = null
                }
            },
        ) {
            if (waitingForBrowser != null) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Waiting for browser…") }
            else Text("Sign in with browser")
        }
        Text("or use an app password (Nextcloud → Settings → Security)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(user, { user = it }, label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("App password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FilledTonalButton(
            enabled = !busy && url.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
            onClick = {
                busy = true; error = null
                scope.launch {
                    runCatching { save(normalizeServerUrl(url), user.trim(), pass) }.onFailure { error = it.message ?: "Couldn't connect" }
                    busy = false
                }
            },
        ) { Text("Connect") }
    }
}
