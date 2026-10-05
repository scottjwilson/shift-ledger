package com.scott.shiftledger

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class GoalDay(val date: LocalDate, val earned: Long, val expenses: Long) {
    val net get() = earned - expenses
}

data class GoalWeek(val start: LocalDate, val target: Long, val days: List<GoalDay>, val hasRecords: Boolean = true) {
    val end: LocalDate get() = start.plusDays(6)
    val earned get() = days.sumOf { it.earned }
    val expenses get() = days.sumOf { it.expenses }
    val net get() = earned - expenses
    val reached get() = net >= target
    val remaining get() = (target - net).coerceAtLeast(0)
    val progress get() = (net.toDouble() / target).coerceIn(0.0, 1.0).toFloat()
    // Never show 100% while even one cent is still missing.
    val percent: String get() = java.math.BigDecimal.valueOf(net.coerceAtLeast(0)).multiply(java.math.BigDecimal(100))
        .divide(java.math.BigDecimal.valueOf(target), 0, java.math.RoundingMode.DOWN).toPlainString() + "%"
    fun dailyPace(today: LocalDate): Long {
        require(today in start..end)
        val remainingDays = 8 - today.dayOfWeek.value // Includes today.
        return (remaining + remainingDays - 1) / remainingDays
    }
    fun milestoneReached(percent: Int) = net >= (target * percent + 99) / 100
    val label: String get() = "${start.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))} – ${end.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))}"
}

data class GoalDashboard(val current: GoalWeek, val history: List<GoalWeek>, val streak: Int) {
    val wins get() = (history + current).count { it.reached }
    val best get() = (history + current).filter { it.hasRecords }.maxByOrNull { it.net }
}

object WeeklyGoals {
    const val DEFAULT_TARGET = 150_000L
    fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun validate(plan: WeeklyGoalPlan) {
        require(validDate(plan.startWeek) && LocalDate.parse(plan.startWeek).dayOfWeek == DayOfWeek.MONDAY) { "A goal must start on a Monday." }
        require(plan.targetCents in 1..100_000_000) { "Enter a weekly goal from $0.01 to $1,000,000." }
    }
    fun target(plans: List<WeeklyGoalPlan>, week: LocalDate): Long = plans
        .filter { LocalDate.parse(it.startWeek) <= week }.maxByOrNull { it.startWeek }?.targetCents ?: DEFAULT_TARGET

    /** Income and costs follow their recorded dates, matching Home. Cash payouts never count twice. */
    fun dashboard(state: LedgerState, today: LocalDate): GoalDashboard {
        val entries = state.entries.filter { it.kind != "PAYOUT" && LocalDate.parse(it.date) <= today }.groupBy { LocalDate.parse(it.date) }
        val expenses = state.expenses.filter { LocalDate.parse(it.date) <= today }.groupBy { LocalDate.parse(it.date) }
        val currentStart = monday(today)
        val recordedStarts = (entries.keys + expenses.keys).map(::monday).toSet()
        val starts = recordedStarts + currentStart
        val weeks = starts.map { start ->
            GoalWeek(start, target(state.goalPlans, start), (0L..6L).map { offset ->
                val date = start.plusDays(offset)
                GoalDay(date, entries[date].orEmpty().sumOf { it.amountCents }, expenses[date].orEmpty().sumOf { it.amountCents })
            }, hasRecords = start in recordedStarts)
        }.sortedByDescending { it.start }
        val current = weeks.first { it.start == currentStart }
        val wins = weeks.filter { it.reached }.map { it.start }.toSet()
        var cursor = if (current.reached) currentStart else currentStart.minusWeeks(1)
        var streak = 0
        while (cursor in wins) { streak++; cursor = cursor.minusWeeks(1) }
        return GoalDashboard(current, weeks.filter { it.start < currentStart }, streak)
    }
}
