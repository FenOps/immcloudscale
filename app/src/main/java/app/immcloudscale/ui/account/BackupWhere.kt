package app.immcloudscale.ui.account

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
data class BackupWhere(
    val away: Boolean,
    val label: String,
    val text: String,
    val needsNetwork: Boolean,
    val connecting: Boolean = false,
    val waiting: Boolean = false,
    val action: String = "Open Network",
)

/** Null for servers set up by hand (not paired): the app can't tell home from away for those. */
fun backupWhere(s: AppSettings, atHome: Boolean, ts: TsState, unmetered: Boolean = true): BackupWhere? = when {
    s.pairedServer.isBlank() -> null
    s.backupWifiOnly && !unmetered -> BackupWhere(
        !atHome, "Waiting for Wi-Fi", "\"Only on Wi-Fi\" is on in Backup settings, so backup waits for a Wi-Fi network.", false,
        waiting = true,
    )
    atHome -> BackupWhere(false, "Backing up on home Wi-Fi", "Backing up straight to your server on the home Wi-Fi.", false)
    s.tsServerIp.isBlank() ->
        BackupWhere(true, "Backup paused away from home", "Your server doesn't offer Remote access yet, so backup waits until you're home.", true)
    !s.tsEnabled ->
        BackupWhere(true, "Backup paused away from home", "Remote access is off, so backup waits until you're home.", true, action = "Turn on Remote access")
    ts.status == TsStatus.Connected ->
        BackupWhere(true, "Backing up anywhere", "Backing up anywhere: the app reaches your server through Remote access.", false)
    ts.status == TsStatus.NeedsLogin || ts.status == TsStatus.Error ->
        BackupWhere(true, "Backup paused away from home", "Remote access needs you, so backup waits until it's connected or you're home.", true)
    else -> BackupWhere(true, "Backing up anywhere", "Connecting to your server through Remote access…", false, connecting = true)
}

/**
 * One line saying where backup runs, following the connection by itself: on the home Wi-Fi, or anywhere through
 * Remote access. [compact] (on the Photos page) explains only when something needs doing.
 */
@Composable
fun BackupWhereStatus(
    s: AppSettings,
    atHome: Boolean,
    ts: TsState,
    unmetered: Boolean,
    onOpenNetwork: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val w = backupWhere(s, atHome, ts, unmetered) ?: return
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (w.connecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(
                when {
                    w.needsNetwork || w.waiting -> Icons.Outlined.CloudOff
                    w.away -> Icons.Outlined.Public
                    else -> Icons.Outlined.Home
                },
                null, Modifier.size(18.dp), tint = when {
                    w.needsNetwork -> MaterialTheme.colorScheme.error
                    w.waiting -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(w.label, style = MaterialTheme.typography.labelLarge)
        }
        if (!compact || w.needsNetwork || w.connecting || w.waiting) Text(
            w.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (w.needsNetwork) TextButton(onClick = onOpenNetwork, modifier = Modifier.offset(x = (-12).dp)) { Text(w.action) }
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
