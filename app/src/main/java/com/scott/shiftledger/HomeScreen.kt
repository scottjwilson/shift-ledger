package com.scott.shiftledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable internal fun HomeScreen(state: LedgerState, now: Long, tracking: Boolean, environment: GpsEnvironment, problem: String?, busy: Boolean,
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onEnd: () -> Unit,
    onImport: () -> Unit, onExpense: () -> Unit, onReview: () -> Unit, onMessages: () -> Unit, onRates: () -> Unit, onGoal: () -> Unit, onVehicle: () -> Unit) {
    var period by rememberSaveable { mutableIntStateOf(0) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val goal = remember(state.entries, state.expenses, state.goalPlans, today) { WeeklyGoals.dashboard(state, today).current }
    val from = when (period) { 0 -> today; 1 -> WeeklyGoals.monday(today); 2 -> today.withDayOfMonth(1); 3 -> today.withDayOfYear(1); else -> null }
    val total = totals(state, from, today, now)
    val shift = state.activeShift
    val accents = LocalLedgerAccents.current
    LazyColumn(Modifier.fillMaxSize().testTag("home_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Heading("Your workday", "Your shift and earnings at a glance.") }
        item { Panel {
            Text(if (shift == null) "Ready when you are" else if (tracking) "Shift in progress" else if (shift.runningSince == null) "Shift paused" else "Tracking interrupted", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (shift == null) {
                Text("Start before your first delivery.", color = MaterialTheme.colorScheme.secondary)
                Button(onClick = onStart, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Start shift") }
            } else {
                Text("${decimal(shift.miles())} mi  ·  ${durationText(shift.durationMillis(now))}", style = MaterialTheme.typography.headlineSmall)
                val health = gpsHealth(shift, tracking, environment, now)
                if (health.needsAttention) Hint("${health.title}\n${health.detail}")
                else {
                    Text(health.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(health.detail, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = if (tracking) onPause else onResume, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (tracking) "Pause" else "Resume") }
                    Button(onClick = onEnd, enabled = !busy, modifier = Modifier.weight(1f)) { Text("End shift") }
                }
                Text("Pause for personal driving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            problem?.let { Hint(it) }
            val reviews = state.shifts.count { it.endedAt != null && !it.reviewed }
            if (reviews > 0) TextButton(onClick = onReview) { Text("Review $reviews completed ${if (reviews == 1) "shift" else "shifts"}") }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Today", "This week", "This month", "This year", "All history").forEachIndexed { i, label -> FilterChip(selected = period == i, onClick = { period = i }, label = { Text(label) }) }
        } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = accents.earnings), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("RECORDED EARNINGS", color = accents.earningsDetail, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                    Text(money(total.earned), modifier = Modifier.testTag("home_earned"), color = accents.onEarnings, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                    HorizontalDivider(color = accents.onEarnings.copy(alpha = 0.2f))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Expenses", color = accents.earningsDetail, style = MaterialTheme.typography.labelMedium)
                            Text(money(total.expenses), color = accents.onEarnings, style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("After expenses", color = accents.earningsDetail, style = MaterialTheme.typography.labelMedium)
                            Text(money(total.earned - total.expenses), color = accents.onEarnings, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Text("Paid out ${money(total.paid)} · separate from earnings", color = accents.earningsDetail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import earnings") }
            OutlinedButton(onClick = onExpense, modifier = Modifier.weight(1f)) { Text("Add expense") }
        } }
        item { WeeklyGoalCard(goal, onGoal) }
        item { Panel {
            Row { Metric("Shift miles", decimal(total.miles), Modifier.weight(1f)); Metric("Shift hours", decimal(total.hours), Modifier.weight(1f)) }
            TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.testTag("toggle_home_rates")) { Text(if (showDetails) "Hide mileage & rates" else "Show mileage & rates") }
            if (showDetails) {
                val summary = summarizeRates(orderRates(state.entries).filter { row ->
                    LocalDate.parse(row.order.date).let { it <= today && (from == null || it >= from) }
                })
                HorizontalDivider()
                Metric("Earned / order mile", rateText(summary.ratedEarningsCents, summary.miles))
                Text("${summary.ratedCount} of ${summary.orderCount} orders have usable mileage. Includes linked adjustments.", style = MaterialTheme.typography.bodySmall)
                Row {
                    Metric("Earned / shift mile", rateText(total.earned, total.miles), Modifier.weight(1f))
                    Metric("Earned / shift hour", if (total.hours > 0) money((total.earned / total.hours).toLong()) else "—", Modifier.weight(1f))
                }
                Text("Gross rates use the earnings and mileage in this period. Only entered expenses are deducted above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = onRates) { Text("See orders and period totals") }
            }
        } }
        item { Text("Tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { Panel {
            Text("Fuel & maintenance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val gauge = FuelTracking.latestGauge(state, today)
            Text(gauge?.let { "${it.bars}/9 bars · reported ${it.date}" } ?: "Fill-ups, fuel economy, and service history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = onVehicle, modifier = Modifier.fillMaxWidth().testTag("open_vehicle")) { Text("Open fuel & maintenance") }
            HorizontalDivider()
            TextButton(onClick = onMessages, modifier = Modifier.fillMaxWidth()) { Text("Saved messages") }
            TextButton(onClick = onRates, modifier = Modifier.fillMaxWidth()) { Text("Dollars per mile") }
        } }
        if (state.entries.isEmpty()) item { Hint("Import Dispatch history or add earnings to start your ledger.") }
    }
}
