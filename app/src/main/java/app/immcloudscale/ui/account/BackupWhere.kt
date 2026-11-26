package app.immcloudscale.ui.account

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.AppSettings
import app.immcloudscale.data.tailscale.TsState
import app.immcloudscale.data.tailscale.TsStatus
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Where backup runs right now: at home straight to the server, away from home through Remote access. */
data class BackupWhere(val away: Boolean, val text: String, val needsNetwork: Boolean)

fun backupWhere(s: AppSettings, atHome: Boolean, ts: TsState): BackupWhere = when {
    atHome -> BackupWhere(false, "Backing up straight to your server on the home Wi-Fi.", false)
    s.tsServerIp.isBlank() -> BackupWhere(true, "Away from home, backup needs Remote access, which your server doesn't offer yet. See the Network tab.", true)
    !s.tsEnabled -> BackupWhere(true, "Away from home, backup needs Remote access, which is off. Turn it on in the Network tab.", true)
    ts.status == TsStatus.Connected -> BackupWhere(true, "Backing up anywhere: the app reaches your server through Remote access.", false)
    ts.status == TsStatus.NeedsLogin || ts.status == TsStatus.Error ->
        BackupWhere(true, "Away from home, Remote access needs you. Open the Network tab.", true)
    else -> BackupWhere(true, "Away from home: connecting to your server through Remote access…", false)
}

/**
 * Shows where backup runs, following the connection by itself: "Home Wi-Fi" on the home network, "Anywhere"
 * elsewhere. [compact] (on the Photos page) explains only when something needs doing.
 */
@Composable
fun BackupWhereStatus(
    s: AppSettings,
    atHome: Boolean,
    ts: TsState,
    onOpenNetwork: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val w = backupWhere(s, atHome, ts)
    Column(modifier) {
        if (compact) Text(
            "Backing up on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .semantics(mergeDescendants = true) { contentDescription = if (w.away) "Backing up anywhere" else "Backing up on home Wi-Fi" },
        ) {
            Place(Icons.Outlined.Home, "Home Wi-Fi", !w.away)
            Place(Icons.Outlined.Public, "Anywhere", w.away)
        }
        if (!compact || w.needsNetwork) Text(
            w.text, style = MaterialTheme.typography.bodySmall,
            color = if (w.needsNetwork) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (w.needsNetwork) TextButton(onClick = onOpenNetwork) { Text("Open Network") }
    }
}

@Composable
private fun RowScope.Place(icon: ImageVector, label: String, here: Boolean) {
    Row(
        Modifier.weight(1f).background(if (here) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (here) Icons.Filled.Check else icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label, style = MaterialTheme.typography.labelLarge,
            color = if (here) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Whenever there's something new, or on chosen days at a chosen time. */
@Composable
fun BackupScheduleSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val ctx = LocalContext.current
    val scheduled = s.backupDays.isNotEmpty()
    val h24 = DateFormat.is24HourFormat(ctx)
    val time = LocalTime.of(s.backupTime / 60, s.backupTime % 60).format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a"))
    ListItem(
        modifier = Modifier.clickable { update { it.copy(backupDays = emptySet()) } },
        leadingContent = { RadioButton(!scheduled, onClick = { update { it.copy(backupDays = emptySet()) } }) },
        headlineContent = { Text("Whenever there's something new") },
        supportingContent = { Text("Checks for new photos about once an hour") },
    )
    ListItem(
        modifier = Modifier.clickable { if (!scheduled) update { it.copy(backupDays = (1..7).toSet()) } },
        leadingContent = { RadioButton(scheduled, onClick = { if (!scheduled) update { it.copy(backupDays = (1..7).toSet()) } }) },
        headlineContent = { Text("On a schedule") },
        supportingContent = { Text(if (scheduled) "On the days below at $time" else "Choose days and a time") },
    )
    if (scheduled) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOfWeek.entries.forEach { d ->
                val on = d.value in s.backupDays
                FilterChip(
                    selected = on,
                    onClick = {
                        update {
                            val days = if (on) it.backupDays - d.value else it.backupDays + d.value
                            it.copy(backupDays = days.ifEmpty { it.backupDays }) // keep at least one day
                        }
                    },
                    label = { Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault())) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = d.getDisplayName(TextStyle.FULL, Locale.getDefault()) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null)
            TextButton(onClick = {
                TimePickerDialog(ctx, { _, h, m -> update { it.copy(backupTime = h * 60 + m) } }, s.backupTime / 60, s.backupTime % 60, h24).show()
            }) { Text("Time: $time") }
        }
    }
}
