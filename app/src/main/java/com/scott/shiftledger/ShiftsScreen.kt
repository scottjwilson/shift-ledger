package com.scott.shiftledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun ShiftsScreen(state: LedgerState, now: Long, onReview: (Shift) -> Unit, onManual: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Shifts", "Whole-shift mileage and time, with room to correct.") }
        item { OutlinedButton(onClick = onManual) { Text("+ Add a missed shift") } }
        if (state.shifts.isEmpty()) item { Panel { Text("No shifts yet", style = MaterialTheme.typography.titleLarge); Text("Start tracking from Home, or add a past shift using your own mileage record.") } }
        items(state.shifts, key = { it.id }) { shift -> Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shift.date, fontWeight = FontWeight.Bold)
                Text(if (shift.endedAt == null) "OPEN" else if (shift.reviewed) "REVIEWED" else "NEEDS REVIEW", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Text("${decimal(shift.miles())} mi · ${durationText(shift.durationMillis(now))}", style = MaterialTheme.typography.titleLarge)
            Text("${ShiftTimes.display(shift.startTime())} → ${shift.endTime()?.let { ShiftTimes.display(it) } ?: "Open"}", style = MaterialTheme.typography.bodySmall)
            if (shift.correctedWorkedMillis != null) Text("Time corrected · originally recorded ${durationText(shift.workedMillis)}", style = MaterialTheme.typography.bodySmall)
            Text(if (shift.correctedMiles != null) "Manual / corrected distance · GPS recorded ${decimal(shift.gpsMeters / 1609.344)} mi" else "GPS distance estimate", style = MaterialTheme.typography.bodySmall)
            if (shift.gaps > 0) Hint("${shift.gaps} tracking ${if (shift.gaps == 1) "gap" else "gaps"} flagged. Check mileage against your records.")
            if (shift.note.isNotBlank()) Text(shift.note)
            if (shift.endedAt != null) TextButton(onClick = { onReview(shift) }) { Text(if (shift.reviewed) "Edit times & mileage" else "Review times & mileage") }
        } }
    }
}
