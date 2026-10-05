package com.scott.shiftledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun DataScreen(state: LedgerState, busy: Boolean, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit,
    onAppearance: () -> Unit, onMessages: () -> Unit, onAddWidget: () -> Unit, onCsv: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onSource: (ImportSource) -> Unit, onUndo: (ImportSource) -> Unit) {
    val appearance = LocalLedgerAppearance.current
    LazyColumn(Modifier.fillMaxSize().testTag("data_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Settings & data", "Preferences, backups, and saved records.") }
        item { Panel {
            Text("Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${appearance.palette.label} colors · ${appearance.font.label} text")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(selected = themeMode == mode, onClick = { onThemeChange(mode) }, label = { Text(mode.label) })
                }
            }
            if (appearance.palette == ColorPalette.BLACK) Text("Pure Black keeps backgrounds black in every mode.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onAppearance, modifier = Modifier.fillMaxWidth()) { Text("Choose colors & fonts") }
        } }
        item { Panel {
            Text("Backup & export", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Save all your records in a restorable backup. Keep a copy outside this phone.")
            Button(onClick = onBackup, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save full backup") }
            OutlinedButton(onClick = onCsv, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export spreadsheet CSV") }
            TextButton(onClick = onRestore, enabled = !busy && state.activeShift == null) { Text("Restore from backup") }
            if (state.activeShift != null) Text("End your shift before restoring a backup.", style = MaterialTheme.typography.bodySmall)
            Text("CSV is for spreadsheets. Use a full backup to restore your records.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Panel {
            Text("Tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onMessages, modifier = Modifier.fillMaxWidth()) { Text("Open saved messages") }
            Text("${state.messageTemplates.size} reusable support messages.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            HorizontalDivider()
            OutlinedButton(onClick = onAddWidget, modifier = Modifier.fillMaxWidth()) { Text("Add home-screen widget") }
            Text("Today's earnings and shift controls on your home screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        } }
        item { Panel {
            Text("About Shift Ledger", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Personal companion · version ${BuildConfig.VERSION_NAME}")
            Text("Your records stay on this phone. Review imported earnings and GPS mileage before relying on totals.")
            Text("Shifts are grouped by their start date. Use odometer readings to check GPS gaps.", style = MaterialTheme.typography.bodySmall)
        } }
        item { Text("Import history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (state.sources.isEmpty()) item { Text("Saved imports and their original text will appear here.") }
        items(state.sources, key = { it.id }) { source -> Panel {
            val date = java.time.Instant.ofEpochMilli(source.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            Text("Imported $date", fontWeight = FontWeight.Bold)
            Text("${state.entries.count { it.sourceId == source.id }} saved rows · payout year ${source.payoutYear}")
            Row { TextButton(onClick = { onSource(source) }) { Text("View original") }; TextButton(onClick = { onUndo(source) }) { Text("Undo import") } }
        } }
    }
}
