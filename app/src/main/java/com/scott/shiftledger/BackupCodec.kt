package com.scott.shiftledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LedgerBackup(
    val format: String = "shift-ledger",
    val version: Int = 6,
    val createdAt: Long,
    val entries: List<Entry>,
    val shifts: List<Shift>,
    val expenses: List<Expense>,
    val sources: List<ImportSource>,
    val messageTemplates: List<MessageTemplate> = emptyList(),
    val goalPlans: List<WeeklyGoalPlan> = emptyList(),
    val fuelStops: List<FuelStop> = emptyList(),
    val fuelReadings: List<FuelReading> = emptyList(),
    val vehicles: List<VehicleSettings> = emptyList(),
    val maintenance: List<MaintenanceRecord> = emptyList(),
)

object BackupCodec {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    fun encode(state: LedgerState, now: Long = System.currentTimeMillis()): String = json.encodeToString(
        LedgerBackup(createdAt = now, entries = state.entries, shifts = state.shifts, expenses = state.expenses, sources = state.sources, messageTemplates = state.messageTemplates, goalPlans = state.goalPlans, fuelStops = state.fuelStops, fuelReadings = state.fuelReadings, vehicles = state.vehicles, maintenance = state.maintenance)
    )
    fun decode(text: String): LedgerBackup {
        require(text.length <= 20_000_000) { "Backup is too large (20 MB limit)." }
        val backup = json.decodeFromString<LedgerBackup>(text)
        require(backup.format == "shift-ledger" && backup.version in 1..6) { "Unsupported backup format or version." }
        require(backup.createdAt > 0)
        fun ids(values: List<String>) = values.size <= 100_000 && values.none { it.isBlank() } && values.distinct().size == values.size
        require(ids(backup.entries.map { it.id }) && ids(backup.shifts.map { it.id }) && ids(backup.expenses.map { it.id }) && ids(backup.sources.map { it.id }) && ids(backup.messageTemplates.map { it.id })) { "Invalid or repeated record IDs." }
        backup.messageTemplates.forEach { SupportMessages.validate(it.title, it.body); require(it.updatedAt > 0) { "Invalid saved message." } }
        require(ids(backup.goalPlans.map { it.startWeek })) { "Invalid or repeated goal weeks." }
        backup.goalPlans.forEach(WeeklyGoals::validate)
        require(backup.entries.all {
            validDate(it.date) && it.kind in setOf("ORDER", "ADJUSTMENT", "PAYOUT", "MANUAL") &&
                it.amountCents in -1_000_000_000_000..1_000_000_000_000 &&
                (it.engagedMinutes == null || it.engagedMinutes in 0..1_000_000) &&
                (it.engagedMiles == null || it.engagedMiles.isFinite() && it.engagedMiles in 0.0..1_000_000.0) &&
                (it.sourceId.isBlank() || backup.sources.any { source -> source.id == it.sourceId })
        }) { "Invalid earnings records or missing source history." }
        require(backup.expenses.all { validDate(it.date) && it.amountCents in 1..1_000_000_000_000 }) { "Invalid expense records." }
        require(backup.shifts.count { it.endedAt == null } <= 1)
        require(backup.shifts.all {
            validDate(it.date) && it.startedAt > 0 && it.startedAt <= backup.createdAt &&
                (it.endedAt == null || it.endedAt in it.startedAt..backup.createdAt) &&
                (it.runningSince == null || it.endedAt == null && it.runningSince in it.startedAt..backup.createdAt) &&
                it.workedMillis in 0..(backup.createdAt - it.startedAt) && it.gaps >= 0 &&
                it.gpsMeters.isFinite() && it.gpsMeters in 0.0..1_000_000_000.0 &&
                (it.correctedMiles == null || it.correctedMiles.isFinite() && it.correctedMiles in 0.0..100_000.0) &&
                (listOf(it.correctedStartedAt, it.correctedEndedAt, it.correctedWorkedMillis).all { time -> time == null } ||
                    (it.endedAt != null && it.correctedStartedAt != null && it.correctedEndedAt != null && it.correctedWorkedMillis != null &&
                        it.correctedStartedAt > 0 && it.correctedEndedAt in (it.correctedStartedAt + 1)..backup.createdAt &&
                        it.correctedWorkedMillis in 0..(it.correctedEndedAt - it.correctedStartedAt)))
        }) { "Invalid shift records." }
        require(ids(backup.fuelStops.map { it.id }) && ids(backup.fuelReadings.map { it.id }) && ids(backup.maintenance.map { it.id }) && ids(backup.vehicles.map { it.id })) { "Invalid or repeated vehicle record IDs." }
        require(backup.vehicles.size <= 1) { "Only one vehicle is supported." }
        backup.fuelStops.forEach(FuelTracking::validate)
        backup.fuelReadings.forEach(FuelTracking::validate)
        backup.vehicles.forEach(FuelTracking::validate)
        backup.maintenance.forEach(FuelTracking::validate)
        FuelTracking.validateSequence(backup.fuelStops)
        FuelTracking.validateLinks(backup.fuelStops, backup.maintenance, backup.expenses)
        return backup
    }

    /** Each type stays separate; CSV consumers must not sum money across types. */
    fun csv(state: LedgerState): String {
        fun cell(value: Any?): String {
            var text = value?.toString() ?: ""
            if (value is String && text.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) text = "'$text"
            return "\"${text.replace("\"", "\"\"")}\""
        }
        val lines = mutableListOf(listOf("type", "date", "id", "order_id", "amount_usd", "engaged_miles", "engaged_minutes", "shift_miles", "shift_hours", "note", "shift_start_utc", "shift_end_utc", "recorded_start_utc", "recorded_end_utc", "odometer_mi", "gallons", "full_tank", "missed_fill", "station_shop", "bars_before", "bars_after", "counts_as_expense", "next_service_mi", "next_service_date").joinToString(","))
        fun row(vararg values: Any?) { lines += (values.toList() + List((24 - values.size).coerceAtLeast(0)) { null }).joinToString(",") { cell(it) } }
        state.entries.forEach { row(it.kind, it.date, it.id, it.orderId, java.math.BigDecimal.valueOf(it.amountCents, 2),
            if (it.kind == "ORDER") it.engagedMiles else null, if (it.kind == "ORDER") it.engagedMinutes else null, null, null,
            if (it.kind == "PAYOUT") "Cash movement; do not add to earned income" else it.raw.takeIf { _ -> it.kind == "MANUAL" }) }
        state.expenses.forEach { row("EXPENSE", it.date, it.id, null, java.math.BigDecimal.valueOf(it.amountCents, 2), null, null, null, null, "${it.category}: ${it.note}") }
        state.shifts.forEach { row("SHIFT", it.date, it.id, null, null, null, null, decimal(it.miles()),
            decimal(it.durationMillis(System.currentTimeMillis()) / 3_600_000.0), "${if (it.reviewed) "Reviewed" else "Needs review"}; ${it.note}",
            java.time.Instant.ofEpochMilli(it.startTime()).toString(), it.endTime()?.let { end -> java.time.Instant.ofEpochMilli(end).toString() },
            java.time.Instant.ofEpochMilli(it.startedAt).toString(), it.endedAt?.let { end -> java.time.Instant.ofEpochMilli(end).toString() }) }
        state.fuelStops.forEach { row("FUEL_DETAIL", it.date, it.id, null, java.math.BigDecimal.valueOf(it.amountCents, 2), null, null, null, null, "Detail only; expense counted in EXPENSE rows. ${it.note}", null, null, null, null, it.odometer, it.gallons, it.fullTank, it.missedFill, it.station, it.barsBefore, if (it.fullTank) 9 else it.barsAfter, it.countAsExpense) }
        state.fuelReadings.forEach { row("FUEL_READING", it.date, it.id, null, null, null, null, null, null, "Manual gauge observation", null, null, null, null, it.odometer, null, null, null, null, null, it.bars) }
        state.maintenance.forEach { row("SERVICE_DETAIL", it.date, it.id, null, java.math.BigDecimal.valueOf(it.amountCents, 2), null, null, null, null, "${it.service}: ${it.note}; detail only", null, null, null, null, it.odometer, null, null, null, it.shop, null, null, it.countAsExpense, it.nextOdometer, it.nextDate) }
        return lines.joinToString("\r\n") + "\r\n"
    }
}
