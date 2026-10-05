package com.scott.shiftledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun RatesScreen(entries: List<Entry>, onOrder: (Entry) -> Unit, onMessages: (String) -> Unit) {
    val orders = remember(entries) { orderRates(entries) }
    val overall = remember(orders) { summarizeRates(orders) }
    val unmatched = remember(entries, orders) {
        val ids = orders.mapNotNull { it.order.orderId }.toSet()
        entries.count { it.kind == "ADJUSTMENT" && (it.orderId.isNullOrBlank() || it.orderId !in ids) }
    }
    var mode by rememberSaveable { mutableStateOf("Orders") }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedStart by rememberSaveable { mutableStateOf<String?>(null) }
    val period = RatePeriod.entries.firstOrNull { it.label == mode }
    val groups = remember(orders, period) { period?.let { groupRates(orders, it) }.orEmpty() }
    val selected = groups.firstOrNull { it.start.toString() == selectedStart }
    BackHandler(selectedStart != null) { selectedStart = null }
    LazyColumn(Modifier.fillMaxSize().testTag("rates_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Dollars per mile", "Order pay + linked adjustments ÷ Dispatch engaged miles.") }
        item { Panel {
            Text("All saved orders", fontWeight = FontWeight.Bold)
            RateMetrics(overall)
            Text("Uses Dispatch's estimated miles. Whole-shift driving, including return trips, has its own rate on Home.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("Orders") + RatePeriod.entries.map { it.label }).forEach { label ->
                FilterChip(selected = mode == label, onClick = { mode = label; selectedStart = null }, label = { Text(label) })
            }
        } }
        item { Text("Periods follow the order's date; weeks start Monday. Linked adjustments count toward their original order. Payouts and manual earnings are excluded. Rates divide combined pay by combined miles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        if (unmatched > 0) item { Hint("$unmatched ${if (unmatched == 1) "adjustment has" else "adjustments have"} no matching order and ${if (unmatched == 1) "is" else "are"} excluded here. Import the original orders to include them.") }
        if (orders.isEmpty()) item { Hint("Import Dispatch orders with mileage to see your dollars per mile.") }
        if (mode == "Orders" || selected != null) {
            if (selected != null) item { Panel {
                Text(period!!.title(selected.start), style = MaterialTheme.typography.titleLarge)
                RateMetrics(selected.summary)
                TextButton(onClick = { selectedStart = null }) { Text("Back to ${mode.lowercase()} totals") }
            } }
            if (mode == "Orders") item { OutlinedTextField(search, { search = it }, label = { Text("Find order number or date") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            val rows = if (selected != null) selected.orders else orders.filter { it.order.orderId.orEmpty().contains(search, true) || it.order.date.contains(search) }
            if (rows.isEmpty() && orders.isNotEmpty()) item { Text("No matching orders.") }
            items(rows, key = { it.order.id }) { rate -> Panel {
                Text("Order ${rate.order.orderId ?: rate.order.id}", fontWeight = FontWeight.Bold)
                Text(rate.order.date, style = MaterialTheme.typography.bodySmall)
                Text("${rateText(rate.earningsCents, rate.miles)} / mi", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("${money(rate.earningsCents)} pay · ${rate.miles?.let(::decimal) ?: "No usable"} engaged miles")
                if (rate.adjustments.isNotEmpty()) Text("${money(rate.order.amountCents)} base + ${money(rate.adjustmentCents)} adjustments", style = MaterialTheme.typography.bodySmall)
                if (rate.miles == null) Text("Zero or missing mileage: excluded from period rate calculations.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onOrder(rate.order) }) { Text("View order") }
                if (!rate.order.orderId.isNullOrBlank()) TextButton(onClick = { onMessages(rate.order.orderId!!) }) { Text("Support message for this order") }
            } }
        } else {
            items(groups, key = { it.start.toString() }) { group ->
                Card(onClick = { selectedStart = group.start.toString() }, shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(period!!.title(group.start), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (period == RatePeriod.WEEKLY) Text("${group.start} – ${group.end}", style = MaterialTheme.typography.bodySmall)
                        RateMetrics(group.summary)
                        Text("Tap to see orders", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable private fun RateMetrics(summary: RateSummary) {
    Text("${rateText(summary.ratedEarningsCents, summary.miles)} / mi", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Text("${money(summary.ratedEarningsCents)} rated pay · ${decimal(summary.miles)} engaged mi")
    Text("${summary.ratedCount} of ${summary.orderCount} orders with usable miles", style = MaterialTheme.typography.bodySmall)
    if (summary.excludedCount > 0) Text("${summary.excludedCount} orders with zero or missing miles excluded from this rate (${money(summary.earningsCents - summary.ratedEarningsCents)} pay).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
}
