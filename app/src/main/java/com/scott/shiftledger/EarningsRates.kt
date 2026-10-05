package com.scott.shiftledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class OrderRate(val order: Entry, val adjustments: List<Entry>) {
    val adjustmentCents get() = adjustments.sumOf { it.amountCents }
    val earningsCents get() = order.amountCents + adjustmentCents
    val miles get() = order.engagedMiles?.takeIf { it.isFinite() && it > 0 }
}

fun orderRates(entries: List<Entry>): List<OrderRate> {
    val adjustments = entries.filter { it.kind == "ADJUSTMENT" && !it.orderId.isNullOrBlank() }.groupBy { it.orderId }
    return entries.filter { it.kind == "ORDER" }.map { OrderRate(it, adjustments[it.orderId].orEmpty()) }
        .sortedWith(compareByDescending<OrderRate> { it.order.date }.thenByDescending { it.order.occurredAt }.thenBy { it.order.id })
}

data class RateSummary(val orderCount: Int, val ratedCount: Int, val earningsCents: Long, val ratedEarningsCents: Long, val miles: Double) {
    val excludedCount get() = orderCount - ratedCount
}
fun summarizeRates(orders: List<OrderRate>): RateSummary {
    val rated = orders.filter { it.miles != null }
    return RateSummary(orders.size, rated.size, orders.sumOf { it.earningsCents }, rated.sumOf { it.earningsCents }, rated.sumOf { it.miles!! })
}
fun dollarsPerMile(earningsCents: Long, miles: Double?): BigDecimal? =
    miles?.takeIf { it.isFinite() && it > 0 }?.let { BigDecimal.valueOf(earningsCents, 2).divide(BigDecimal.valueOf(it), 2, RoundingMode.HALF_UP) }
fun rateText(earningsCents: Long, miles: Double?): String = dollarsPerMile(earningsCents, miles)?.let {
    java.text.NumberFormat.getCurrencyInstance(Locale.US).format(it)
} ?: "—"

enum class RatePeriod(val label: String) {
    DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly");
    fun start(date: LocalDate): LocalDate = when (this) {
        DAILY -> date
        WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTHLY -> date.withDayOfMonth(1)
        YEARLY -> date.withDayOfYear(1)
    }
    fun end(start: LocalDate): LocalDate = when (this) {
        DAILY -> start
        WEEKLY -> start.plusDays(6)
        MONTHLY -> start.plusMonths(1).minusDays(1)
        YEARLY -> start.plusYears(1).minusDays(1)
    }
    fun title(start: LocalDate): String = when (this) {
        DAILY -> start.format(DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.US))
        WEEKLY -> "Week of ${start.format(DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US))}"
        MONTHLY -> start.format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.US))
        YEARLY -> start.year.toString()
    }
}
data class RateGroup(val start: LocalDate, val end: LocalDate, val orders: List<OrderRate>) {
    val summary get() = summarizeRates(orders)
}
fun groupRates(orders: List<OrderRate>, period: RatePeriod): List<RateGroup> =
    orders.groupBy { period.start(LocalDate.parse(it.order.date)) }.toSortedMap(reverseOrder())
        .map { (start, rows) -> RateGroup(start, period.end(start), rows) }
