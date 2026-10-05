package com.scott.shiftledger

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

@Serializable
@Entity(tableName = "entries")
data class Entry(
    @PrimaryKey val id: String,
    val kind: String, // ORDER, ADJUSTMENT, PAYOUT, MANUAL
    val date: String,
    val amountCents: Long,
    val orderId: String? = null,
    val occurredAt: String = "",
    val engagedMinutes: Int? = null,
    val engagedMiles: Double? = null,
    val sourceId: String = "",
    val raw: String = "",
)

@Serializable
@Entity(tableName = "imports")
data class ImportSource(@PrimaryKey val id: String, val createdAt: Long, val payoutYear: Int, val text: String)

@Serializable
@Entity(tableName = "expenses")
data class Expense(
    @PrimaryKey val id: String,
    val date: String,
    val amountCents: Long,
    val category: String,
    val note: String = "",
)

@Serializable
@Entity(tableName = "message_templates")
data class MessageTemplate(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "weekly_goal_plans")
data class WeeklyGoalPlan(@PrimaryKey val startWeek: String, val targetCents: Long)

@Serializable
@Entity(tableName = "shifts")
data class Shift(
    @PrimaryKey val id: String,
    val date: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val runningSince: Long? = null,
    val workedMillis: Long = 0,
    val gpsMeters: Double = 0.0,
    val correctedMiles: Double? = null,
    val odometerStart: Double? = null,
    val lastFixAt: Long? = null,
    val gaps: Int = 0,
    val reviewed: Boolean = false,
    val note: String = "",
    val correctedStartedAt: Long? = null,
    val correctedEndedAt: Long? = null,
    val correctedWorkedMillis: Long? = null,
) {
    fun miles() = correctedMiles ?: (gpsMeters / 1609.344)
    fun startTime() = correctedStartedAt ?: startedAt
    fun endTime() = correctedEndedAt ?: endedAt
    fun durationMillis(now: Long) = correctedWorkedMillis ?: (workedMillis + (runningSince?.let { (now - it).coerceAtLeast(0) } ?: 0))
}

data class LedgerState(
    val entries: List<Entry> = emptyList(),
    val shifts: List<Shift> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val sources: List<ImportSource> = emptyList(),
    val messageTemplates: List<MessageTemplate> = emptyList(),
    val goalPlans: List<WeeklyGoalPlan> = emptyList(),
    val fuelStops: List<FuelStop> = emptyList(),
    val fuelReadings: List<FuelReading> = emptyList(),
    val vehicles: List<VehicleSettings> = emptyList(),
    val maintenance: List<MaintenanceRecord> = emptyList(),
) {
    val vehicle get() = vehicles.firstOrNull() ?: VehicleSettings()
    val activeShift get() = shifts.firstOrNull { it.endedAt == null }
}

fun money(cents: Long): String = java.text.NumberFormat.getCurrencyInstance(Locale.US).format(BigDecimal.valueOf(cents, 2))
fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)
fun cents(text: String): Long = text.trim().replace("$", "").replace(",", "").toBigDecimal()
    .setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
fun validDate(text: String): Boolean = runCatching { LocalDate.parse(text) }.isSuccess
fun durationText(millis: Long): String {
    val minutes = millis / 60_000
    return "${minutes / 60}h ${minutes % 60}m"
}

data class Totals(val earned: Long, val paid: Long, val expenses: Long, val miles: Double, val hours: Double)
fun totals(state: LedgerState, from: LocalDate?, through: LocalDate, now: Long): Totals {
    fun inRange(value: String): Boolean = LocalDate.parse(value).let { it <= through && (from == null || it >= from) }
    val entries = state.entries.filter { inRange(it.date) }
    val shifts = state.shifts.filter { inRange(it.date) }
    return Totals(
        entries.filter { it.kind != "PAYOUT" }.sumOf { it.amountCents },
        entries.filter { it.kind == "PAYOUT" }.sumOf { it.amountCents },
        state.expenses.filter { inRange(it.date) }.sumOf { it.amountCents },
        shifts.sumOf { it.miles() }, shifts.sumOf { it.durationMillis(now) } / 3_600_000.0,
    )
}
