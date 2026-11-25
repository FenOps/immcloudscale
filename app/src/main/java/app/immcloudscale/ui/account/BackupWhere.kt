package app.immcloudscale.ui.account

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.immcloudscale.data.AppSettings
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Where backup runs: only on the home Wi-Fi, or anywhere (away from home the app connects through Tailscale
 * by itself, so choosing "Anywhere" also turns on Remote access).
 */
fun chooseBackupWhere(s: AppSettings, anywhere: Boolean) =
    s.copy(backupAnywhere = anywhere, tsEnabled = if (anywhere && s.tsServerIp.isNotBlank()) true else s.tsEnabled)

/** [compact] (on the Photos page) explains only when something needs doing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupWhereChooser(
    s: AppSettings,
    onChange: (anywhere: Boolean) -> Unit,
    onOpenNetwork: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier) {
        if (compact) Text(
            "Back up on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !s.backupAnywhere, onClick = { onChange(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { SegmentedButtonDefaults.Icon(!s.backupAnywhere) { Icon(Icons.Outlined.Home, null) } },
            ) { Text("Home Wi-Fi only") }
            SegmentedButton(
                selected = s.backupAnywhere, onClick = { onChange(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { SegmentedButtonDefaults.Icon(s.backupAnywhere) { Icon(Icons.Outlined.Public, null) } },
            ) { Text("Anywhere") }
        }
        val (text, needsNetwork) = when {
            !s.backupAnywhere -> "Backs up only when you're on your home Wi-Fi." to false
            s.tsServerIp.isBlank() -> "Away from home, backup needs Remote access, which your server doesn't offer yet. See the Network tab." to true
            !s.tsEnabled -> "Away from home, backup needs Remote access, which is off. Turn it on in the Network tab." to true
            else -> "Backs up wherever you are. Away from home Wi-Fi, the app connects to your server by itself." to false
        }
        if (!compact || needsNetwork) Text(
            text, style = MaterialTheme.typography.bodySmall,
            color = if (needsNetwork) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (needsNetwork) TextButton(onClick = onOpenNetwork) { Text("Open Network") }
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
