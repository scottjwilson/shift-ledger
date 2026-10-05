package com.scott.shiftledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun WeeklyGoalCard(week: GoalWeek, onOpen: () -> Unit) {
    Card(onClick = onOpen, shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("weekly_goal_card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Weekly goal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${money(week.net)} / ${money(week.target)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { week.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
            Text(if (week.reached) "Goal reached! ${money(week.net - week.target)} beyond your target." else "${week.percent} complete · ${money(week.remaining)} to go")
            Text("After recorded expenses · ${week.label}", style = MaterialTheme.typography.bodySmall)
            Text("See progress & milestones →", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable fun GoalsScreen(state: LedgerState, today: LocalDate, busy: Boolean,
    onSave: (Long, () -> Unit) -> Unit, onExpense: () -> Unit, onImport: () -> Unit) {
    val dashboard = remember(state.entries, state.expenses, state.goalPlans, today) { WeeklyGoals.dashboard(state, today) }
    val week = dashboard.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var selectedDay by rememberSaveable(week.start.toString()) { mutableIntStateOf(today.dayOfWeek.value - 1) }
    BackHandler(editing) { editing = false }
    LazyColumn(Modifier.fillMaxSize().testTag("goals_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (editing) {
            item { Heading("Set your weekly goal", "Your target after recorded expenses.") }
            item { Panel {
                val amount = runCatching { cents(draft) }.getOrNull()
                val valid = amount != null && amount in 1..100_000_000
                OutlinedTextField(draft, { draft = it.take(20) }, label = { Text("Weekly net goal ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    isError = draft.isNotBlank() && !valid,
                    supportingText = { Text("Enter $0.01 to $1,000,000.") })
                Text("Applies starting this week (${week.label}). Past weeks keep their goals. Weeks run Monday through Sunday.")
                Button(onClick = { onSave(amount!!) { editing = false } }, enabled = !busy && valid, modifier = Modifier.fillMaxWidth()) { Text("Save weekly goal") }
                TextButton(onClick = { editing = false }, enabled = !busy) { Text("Cancel goal edit") }
            } }
        } else {
            item { Heading("Your weekly quest", week.label) }
            item { GoalHero(week) }
            item { Panel {
                Text(if (week.reached) "Target cleared" else "Your next step", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (week.reached) {
                    Text("${money(week.net - week.target)} above your weekly goal.", color = MaterialTheme.colorScheme.primary)
                    Text("Nice work. Keep earnings and expenses up to date to keep your progress accurate.")
                } else {
                    Text("${money(week.dailyPace(today))} net / day", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    val days = 8 - today.dayOfWeek.value
                    Text("${money(week.remaining)} left across $days remaining calendar ${if (days == 1) "day" else "days"}, including today.")
                    val next = listOf(25, 50, 75, 100).first { !week.milestoneReached(it) }
                    Text("Next milestone: $next% · ${money(((week.target * next + 99) / 100) - week.net)} to unlock", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExpense, modifier = Modifier.weight(1f)) { Text("Add expense") }
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Add earnings") }
                }
                TextButton(onClick = { draft = java.math.BigDecimal.valueOf(week.target, 2).toPlainString(); editing = true }) { Text("Edit weekly goal") }
            } }
            item { Panel {
                Text("Build your week", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Solid: cumulative net · Dashed: even daily pace", style = MaterialTheme.typography.bodySmall)
                WeeklyGoalChart(week, today)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.days.forEachIndexed { index, day ->
                        FilterChip(selected = selectedDay == index, onClick = { selectedDay = index },
                            label = { Text(day.date.format(DateTimeFormatter.ofPattern("EEE", Locale.US))) })
                    }
                }
                val day = week.days[selectedDay]
                Text(day.date.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)), fontWeight = FontWeight.Bold)
                if (day.date > today) Text("Upcoming day. Future-dated records count when that day arrives.")
                else {
                    GoalAmount("Earnings", day.earned)
                    GoalAmount("Expenses", -day.expenses)
                    HorizontalDivider()
                    GoalAmount("Net for this day", day.net)
                }
            } }
            item { Panel {
                Text("Your achievements", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${dashboard.wins}", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                        Text("weeks at goal")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("${dashboard.streak}", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                        Text("week streak")
                    }
                }
                Text("This week joins your streak when you reach the goal. A week below goal breaks it after Sunday.", style = MaterialTheme.typography.bodySmall)
                listOf(1 to "First finish", 4 to "Four-week club", 12 to "Twelve-week club").forEach { (required, label) ->
                    val earned = dashboard.wins >= required
                    Text("${if (earned) "★" else "☆"} $label · ${if (earned) "Unlocked" else "${dashboard.wins}/$required goal weeks"}",
                        color = if (earned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (earned) FontWeight.Bold else FontWeight.Normal)
                }
                dashboard.best?.let { best ->
                    Text("Best recorded week: ${money(best.net)} net", fontWeight = FontWeight.Bold)
                    Text("${best.label}${if (best.start == week.start) " · in progress" else ""}", style = MaterialTheme.typography.bodySmall)
                }
            } }
            item { Heading("Past weeks", "Weeks with recorded earnings or expenses.") }
            if (dashboard.history.isEmpty()) item { Hint("Import earlier earnings to build your weekly history.") }
            items(dashboard.history, key = { it.start.toString() }) { past -> Panel {
                Text(past.label, fontWeight = FontWeight.Bold)
                Text("${if (past.reached) "★ Goal reached" else "Below goal"} · ${past.percent}", color = MaterialTheme.colorScheme.primary)
                Text("${money(past.net)} net / ${money(past.target)} goal", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { past.progress }, modifier = Modifier.fillMaxWidth())
                Text("${money(past.earned)} earned − ${money(past.expenses)} expenses", style = MaterialTheme.typography.bodySmall)
            } }
            item { Hint("Progress uses orders, adjustments and manual earnings minus expenses, on their recorded dates. Payouts are excluded. Only entered costs count; unrecorded fuel, maintenance or other costs aren't deducted. History and badges recalculate when records change. Weeks before your first custom goal use $1,500; empty weeks break streaks.") }
        }
    }
}

@Composable private fun GoalHero(week: GoalWeek) {
    val accents = LocalLedgerAccents.current
    Card(colors = CardDefaults.cardColors(containerColor = accents.earnings), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (week.reached) "★ WEEKLY GOAL REACHED" else "MAKE THIS WEEK COUNT", style = MaterialTheme.typography.labelLarge, color = accents.earningsDetail)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(102.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Weekly goal ${week.percent} complete" }) {
                        val stroke = 9.dp.toPx()
                        val inset = stroke / 2
                        val diameter = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                        drawArc(accents.onEarnings.copy(alpha = .17f), -90f, 360f, false, Offset(inset, inset), diameter, style = Stroke(stroke))
                        drawArc(accents.onEarnings, -90f, week.progress * 360f, false, Offset(inset, inset), diameter, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                    Text(week.percent, style = MaterialTheme.typography.titleLarge, color = accents.onEarnings, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(money(week.net), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accents.onEarnings)
                    Text("after recorded expenses", style = MaterialTheme.typography.bodySmall, color = accents.earningsDetail)
                    Text("of ${money(week.target)}", style = MaterialTheme.typography.bodyLarge, color = accents.earningsDetail)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(25, 50, 75, 100).forEach { milestone ->
                    val reached = week.milestoneReached(milestone)
                    Surface(Modifier.weight(1f), color = accents.onEarnings.copy(alpha = if (reached) .22f else .08f), shape = RoundedCornerShape(12.dp)) {
                        Text("${if (reached) "★" else "☆"} $milestone%", textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium, color = accents.onEarnings,
                            modifier = Modifier.semantics { contentDescription = "$milestone percent milestone ${if (reached) "unlocked" else "locked"}" }.padding(vertical = 10.dp))
                    }
                }
            }
            Text("${money(week.earned)} earned − ${money(week.expenses)} expenses", style = MaterialTheme.typography.bodySmall, color = accents.earningsDetail)
        }
    }
}

@Composable private fun GoalAmount(label: String, amount: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Text(money(amount), fontWeight = FontWeight.Bold)
    }
}

@Composable private fun WeeklyGoalChart(week: GoalWeek, today: LocalDate) {
    val primary = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val count = today.dayOfWeek.value
    val cumulative = week.days.take(count).runningFold(0L) { sum, day -> sum + day.net }.drop(1)
    val min = minOf(0L, cumulative.minOrNull() ?: 0)
    val max = maxOf(week.target, cumulative.maxOrNull() ?: 0)
    Text("Goal ${money(week.target)} · now ${money(week.net)}", style = MaterialTheme.typography.bodySmall)
    Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = "Cumulative net earnings compared with even daily goal pace. Select a day below to see its earnings and expenses." }) {
        val pad = 8.dp.toPx()
        fun x(index: Int) = pad + (size.width - pad * 2) * index / 6f
        fun y(value: Long) = size.height - pad - ((value - min).toDouble() / (max - min) * (size.height - pad * 2)).toFloat()
        drawLine(grid, Offset(pad, y(0)), Offset(size.width - pad, y(0)), 1.dp.toPx())
        drawLine(grid, Offset(pad, y(week.target)), Offset(size.width - pad, y(week.target)), 1.dp.toPx())
        drawLine(guide, Offset(x(0), y(week.target / 7)), Offset(x(6), y(week.target)), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 5.dp.toPx())))
        val path = Path()
        cumulative.forEachIndexed { index, amount -> if (index == 0) path.moveTo(x(index), y(amount)) else path.lineTo(x(index), y(amount)) }
        drawPath(path, primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        cumulative.forEachIndexed { index, amount -> drawCircle(primary, 4.dp.toPx(), Offset(x(index), y(amount))) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Mon", style = MaterialTheme.typography.labelSmall)
        Text(if (min < 0) "Low ${money(min)}" else "${money(0)} baseline", style = MaterialTheme.typography.labelSmall)
        Text("Sun", style = MaterialTheme.typography.labelSmall)
    }
}
