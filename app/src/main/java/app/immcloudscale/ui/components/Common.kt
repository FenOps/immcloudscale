package app.immcloudscale.ui.components

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import app.immcloudscale.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.immcloudscale.ui.theme.GoogleColors
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** The app mark: a four-colour pinwheel, like the Photos logo. */
@Composable
fun Pinwheel(size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_pinwheel), null, modifier.size(size))
}

/** Google-style coloured initial avatar. */
@Composable
fun LetterAvatar(name: String, size: Dp = 32.dp, modifier: Modifier = Modifier, contentDescription: String = "Account and settings") {
    val palette = listOf(Color(0xFF1A73E8), Color(0xFFD93025), Color(0xFF188038), Color(0xFFE37400), Color(0xFF9334E6), Color(0xFF007B83))
    val c = palette[abs(name.hashCode()) % palette.size]
    Box(
        modifier.size(size).clip(CircleShape).background(c).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White, fontWeight = FontWeight.Medium, fontSize = (size.value * 0.45f).sp,
        )
    }
}

@Composable
fun CenteredLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp), textAlign = TextAlign.Center)
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center,
        )
        if (action != null) TextButton(onClick = onAction, modifier = Modifier.padding(top = 12.dp)) { Text(action) }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) =
    EmptyState(Icons.Outlined.CloudOff, "Can't reach your server", message, modifier, "Try again", onRetry)

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, start: Dp = 16.dp, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = start, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

private val sizeFmt = DecimalFormat("#,##0.#")

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return sizeFmt.format(v) + " " + units[i]
}

fun formatDate(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0) return ""
    val z = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(epochMillis).atZone(z)
    val n = Instant.ofEpochMilli(now).atZone(z)
    return when {
        d.toLocalDate() == n.toLocalDate() -> d.format(DateTimeFormatter.ofPattern("h:mm a"))
        d.year == n.year -> d.format(DateTimeFormatter.ofPattern("MMM d"))
        else -> d.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }
}

fun formatAgo(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0) return "never"
    val s = (now - epochMillis) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
