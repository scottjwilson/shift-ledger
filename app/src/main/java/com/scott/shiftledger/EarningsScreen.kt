package com.scott.shiftledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable internal fun EarningsScreen(state: LedgerState, preview: ImportPreview?, busy: Boolean, importText: MutableState<String>, importProgress: String?, importNotice: String?,
    onPreview: (String, Int) -> Unit, onCancelPreview: () -> Unit, onSave: () -> Unit, onOpenFile: () -> Unit,
    onManual: () -> Unit, onEntry: (Entry) -> Unit, onRates: () -> Unit, requestImport: Boolean, onImportHandled: () -> Unit) {
    var importExpanded by rememberSaveable { mutableStateOf(false) }
    val showImport = importExpanded || state.entries.isEmpty() || preview != null || importText.value.isNotBlank() || importProgress != null
    LaunchedEffect(requestImport) {
        if (requestImport) { importExpanded = true; onImportHandled() }
    }
    var year by rememberSaveable { mutableStateOf(LocalDate.now().year.toString()) }
    var filter by rememberSaveable { mutableStateOf("Earnings") }
    val rates = remember(state.entries) { orderRates(state.entries).associateBy { it.order.id } }
    val listState = rememberLazyListState()
    LaunchedEffect(preview?.parsed?.source?.id) {
        if (preview != null) {
            importExpanded = false
            listState.animateScrollToItem(3)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Earnings", "Import your work. Keep payouts separate.") }
        item { OutlinedButton(onClick = onRates, modifier = Modifier.fillMaxWidth()) { Text("Dollars per mile · orders & periods") } }
        item { Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Import Dispatch history", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (state.entries.isNotEmpty() && preview == null && importText.value.isBlank() && importProgress == null) {
                    TextButton(onClick = { importExpanded = !importExpanded }) { Text(if (showImport) "Hide" else "Open") }
                }
            }
            if (showImport) {
            Text("Open a report or paste its earnings table. Import one calendar year at a time.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Open PDF / screenshot / text") }
            importProgress?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            importNotice?.let { Hint(it) }
            OutlinedTextField(value = importText.value, enabled = importProgress == null, onValueChange = { importText.value = it.take(2_000_000); onCancelPreview() },
                label = { Text("Paste earnings history") }, modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 220.dp), maxLines = 8)
            OutlinedTextField(value = year, onValueChange = { year = it.take(4); onCancelPreview() }, label = { Text("Year for payouts without a year") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onPreview(importText.value, year.toInt()) }, enabled = !busy && importText.value.isNotBlank() && year.toIntOrNull() in 2000..2100) { Text("Preview import") }
            }
            }
        } }
        if (preview != null) {
            item { Panel {
                Text("Check before saving", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${preview.newEntries.size} new · ${preview.duplicates} already saved · ${preview.conflicts.size} conflicts")
                Text("New earnings: ${money(preview.newEntries.filter { it.kind != "PAYOUT" }.sumOf { it.amountCents })}")
                Text("New payouts: ${money(preview.newEntries.filter { it.kind == "PAYOUT" }.sumOf { it.amountCents })}")
                preview.parsed.warnings.forEach { Hint(it) }
                if (preview.conflicts.isNotEmpty()) Hint("Conflicting rows will not be saved. Review their original imports in Data; undo an old import before replacing its records.")
                if (state.entries.any { it.kind == "MANUAL" }) Hint("Manual earnings exist. Remove any that represent the same work before importing, to avoid counting it twice.")
                Button(onClick = onSave, enabled = !busy && preview.newEntries.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save ${preview.newEntries.size} new rows") }
                TextButton(onClick = onCancelPreview) { Text("Cancel preview") }
            } }
            items(preview.parsed.entries, key = { "preview-${it.id}" }) { entry ->
                EntryCard(entry, when { preview.newEntries.any { it.id == entry.id } -> "NEW"; preview.conflicts.any { it.id == entry.id } -> "CONFLICT · EXCLUDED"; else -> "ALREADY SAVED" }, onEntry)
            }
        } else {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onManual) { Text("+ Manual earnings") }
            } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Earnings", "Payouts").forEach { label -> FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) }) }
            } }
            val rows = state.entries.filter { if (filter == "Payouts") it.kind == "PAYOUT" else it.kind != "PAYOUT" }
            if (rows.isEmpty()) item { Text("No ${filter.lowercase()} saved yet.", color = MaterialTheme.colorScheme.secondary) }
            items(rows, key = { it.id }) { EntryCard(it, null, onEntry, rates[it.id]) }
        }
    }
}

@Composable private fun EntryCard(entry: Entry, status: String?, onEntry: (Entry) -> Unit, rate: OrderRate? = null) {
    Card(onClick = { onEntry(entry) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, contentColor = MaterialTheme.colorScheme.onSurface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.orderId?.let { "Order $it" } ?: if (entry.kind == "MANUAL") "Manual earnings" else "Payout", fontWeight = FontWeight.SemiBold)
                Text(money(entry.amountCents), fontWeight = FontWeight.Bold)
            }
            Text("${entry.date} · ${entry.kind.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (entry.kind == "ORDER") Text("${decimal(entry.engagedMiles ?: 0.0)} engaged mi · ${durationText((entry.engagedMinutes ?: 0) * 60_000L)}", style = MaterialTheme.typography.bodySmall)
            if (entry.kind == "ORDER") Text("${rateText(rate?.earningsCents ?: entry.amountCents, entry.engagedMiles)} / engaged mi${if (rate?.adjustments?.isNotEmpty() == true) " · includes ${money(rate.adjustmentCents)} adjustments" else ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}
