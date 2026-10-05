package com.scott.shiftledger

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
@Entity(tableName = "fuel_stops")
data class FuelStop(
    @PrimaryKey val id: String,
    val date: String,
    val odometer: Double,
    val gallons: Double,
    val amountCents: Long,
    val fullTank: Boolean,
    val missedFill: Boolean = false,
    val station: String = "",
    val note: String = "",
    val barsBefore: Int? = null,
    val barsAfter: Int? = null,
    val countAsExpense: Boolean = true,
    val createdAt: Long,
) { val expenseId get() = "fuel:$id" }

@Serializable
@Entity(tableName = "fuel_readings")
data class FuelReading(@PrimaryKey val id: String, val date: String, val bars: Int, val odometer: Double? = null, val createdAt: Long)

@Serializable
@Entity(tableName = "vehicle_settings")
data class VehicleSettings(@PrimaryKey val id: String = "primary", val name: String = "2023 Ram ProMaster", val tankGallons: Double = 24.0,
    val pendingFuelGapDate: String? = null, val pendingFuelGapOdometer: Double? = null,
)

@Serializable
@Entity(tableName = "maintenance")
data class MaintenanceRecord(
    @PrimaryKey val id: String, val date: String, val service: String,
    val odometer: Double, val amountCents: Long = 0,
    val shop: String = "", val note: String = "",
    val nextOdometer: Double? = null, val nextDate: String? = null,
    val countAsExpense: Boolean = true,
) { val expenseId get() = "service:$id" }

data class FuelCycle(val start: FuelStop, val end: FuelStop, val stops: List<FuelStop>) {
    val miles = end.odometer - start.odometer
    val gallons = stops.sumOf { it.gallons }
    val costCents = stops.sumOf { it.amountCents }
    val mpg = miles / gallons
    val centsPerMile = costCents / miles
}

data class GaugeObservation(val date: String, val bars: Int, val odometer: Double?, val createdAt: Long)

/** Pump prices commonly use three decimal places; keep cent totals exact. */
fun fuelUnitPrice(amountCents: Long, quantity: Double): String = if (!quantity.isFinite() || quantity <= 0) "—" else
    "$" + java.math.BigDecimal.valueOf(amountCents, 2).divide(java.math.BigDecimal.valueOf(quantity), 3, java.math.RoundingMode.HALF_UP).toPlainString()

object FuelTracking {
    fun validate(stop: FuelStop) {
        require(stop.id.isNotBlank() && validDate(stop.date) && stop.createdAt > 0) { "Check the fill-up date." }
        require(stop.odometer.isFinite() && stop.odometer in 0.0..10_000_000.0) { "Enter a valid odometer reading." }
        require(stop.gallons.isFinite() && stop.gallons in 0.001..200.0) { "Enter gallons between 0.001 and 200." }
        require(stop.amountCents in 1..100_000_000) { "Enter the total fuel cost greater than zero." }
        require(listOf(stop.barsBefore, stop.barsAfter).all { it == null || it in 0..9 }) { "Choose 0 to 9 bars." }
        require(!stop.fullTank || stop.barsAfter == null || stop.barsAfter == 9) { "A full tank should show 9 bars." }
        require(stop.barsBefore == null || stop.barsAfter == null || stop.barsAfter >= stop.barsBefore) { "After-fill bars cannot be below before-fill bars." }
        require(stop.station.length <= 120 && stop.note.length <= 2000)
    }
    fun validate(reading: FuelReading) {
        require(reading.id.isNotBlank() && validDate(reading.date) && reading.bars in 0..9 && reading.createdAt > 0) { "Check the gauge reading." }
        require(reading.odometer == null || reading.odometer.isFinite() && reading.odometer in 0.0..10_000_000.0) { "Check the odometer." }
    }
    fun validate(settings: VehicleSettings) {
        require(settings.id == "primary" && settings.name.isNotBlank() && settings.name.length <= 100)
        require((settings.pendingFuelGapDate == null) == (settings.pendingFuelGapOdometer == null))
        settings.pendingFuelGapDate?.let { require(validDate(it)) }
        settings.pendingFuelGapOdometer?.let { require(it.isFinite() && it in 0.0..10_000_000.0) }
        require(settings.tankGallons.isFinite() && settings.tankGallons in 1.0..200.0) { "Enter tank capacity from 1 to 200 US gallons." }
    }
    fun validate(record: MaintenanceRecord) {
        require(record.id.isNotBlank() && validDate(record.date) && record.service.isNotBlank() && record.service.length <= 100) { "Enter a service and valid date." }
        require(record.odometer.isFinite() && record.odometer in 0.0..10_000_000.0) { "Check the odometer." }
        require(record.amountCents in 0..100_000_000) { "Cost cannot be negative." }
        require(record.nextOdometer == null || record.nextOdometer.isFinite() && record.nextOdometer > record.odometer && record.nextOdometer <= 10_000_000.0) { "Next service mileage must be after this service." }
        require(record.nextDate == null || validDate(record.nextDate) && record.nextDate > record.date) { "Next service date must be after this service." }
        require(record.shop.length <= 120 && record.note.length <= 2000)
    }
    fun followsPendingGap(stop: FuelStop, vehicle: VehicleSettings): Boolean {
        val date = vehicle.pendingFuelGapDate ?: return false
        return stop.date > date || (stop.date == date && stop.odometer >= vehicle.pendingFuelGapOdometer!!)
    }
    fun clearPendingGap(vehicle: VehicleSettings) = vehicle.copy(pendingFuelGapDate = null, pendingFuelGapOdometer = null)
    fun ordered(stops: List<FuelStop>) = stops.sortedWith(compareBy<FuelStop> { it.date }.thenBy { it.odometer }.thenBy { it.createdAt }.thenBy { it.id })
    fun validateSequence(stops: List<FuelStop>) {
        require(ordered(stops).zipWithNext().all { (a,b) -> b.odometer >= a.odometer }) { "Odometer readings must increase with fill-up dates. Check your date or mileage." }
    }
    /** Fuel purchased at the starting full tank belongs to the preceding, unknown interval. */
    fun cycles(stops: List<FuelStop>): List<FuelCycle> {
        val result = mutableListOf<FuelCycle>()
        var start: FuelStop? = null
        val pending = mutableListOf<FuelStop>()
        for (stop in ordered(stops)) {
            if (stop.missedFill || (start != null && stop.odometer < start.odometer)) { start = null; pending.clear() }
            if (start == null) { if (stop.fullTank) start = stop; continue }
            pending += stop
            if (stop.fullTank) {
                if (stop.odometer > start.odometer) result += FuelCycle(start, stop, pending.toList())
                start = stop; pending.clear()
            }
        }
        return result
    }
    fun latestGauge(state: LedgerState, through: LocalDate = LocalDate.now()): GaugeObservation? {
        val stops = state.fuelStops.mapNotNull { stop ->
            val bars = if (stop.fullTank) 9 else stop.barsAfter
            bars?.let { GaugeObservation(stop.date, it, stop.odometer, stop.createdAt) }
        }
        val readings = state.fuelReadings.map { GaugeObservation(it.date, it.bars, it.odometer, it.createdAt) }
        val observations = (stops + readings).filter { LocalDate.parse(it.date) <= through }
        val latestDate = observations.maxOfOrNull { it.date } ?: return null
        val sameDay = observations.filter { it.date == latestDate }
        val latestEntered = sameDay.maxBy { it.createdAt }
        // A check-in without mileage uses entry time. Otherwise mileage orders backfilled observations.
        if (latestEntered.odometer == null) return latestEntered
        return sameDay.filter { it.odometer != null }
            .maxWithOrNull(compareBy<GaugeObservation> { it.odometer }.thenBy { it.createdAt })
    }
    fun expense(stop: FuelStop) = Expense(stop.expenseId, stop.date, stop.amountCents, "Fuel", "${decimal(stop.gallons)} gal · ${stop.station.ifBlank { "Fill-up" }} · ${decimal(stop.odometer)} mi")
    fun expense(record: MaintenanceRecord) = Expense(record.expenseId, record.date, record.amountCents, "Maintenance", "${record.service} · ${decimal(record.odometer)} mi · ${record.shop}")
    fun validateLinks(stops: List<FuelStop>, maintenance: List<MaintenanceRecord>, expenses: List<Expense>) {
        val linked = stops.filter { it.countAsExpense }.map(::expense) + maintenance.filter { it.countAsExpense && it.amountCents > 0 }.map(::expense)
        require(linked.all { expected -> expenses.any { it == expected } }) { "A fuel or maintenance record is missing its matching expense." }
        val ids = linked.map { it.id }.toSet()
        require(expenses.none { (it.id.startsWith("fuel:") || it.id.startsWith("service:")) && it.id !in ids }) { "A linked expense has no fuel or maintenance record." }
    }
}
