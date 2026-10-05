package com.scott.shiftledger

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FuelTrackingTest {
    private fun stop(id: String, date: String, odo: Double, gallons: Double, full: Boolean, missed: Boolean = false) = FuelStop(id,date,odo,gallons,4000,full,missed,createdAt = 1000)
    @Test fun `full to full uses intermediate partial gallons and excludes starting purchase`() {
        val a = stop("a","2026-09-01",1000.0,20.0,true)
        val partial = stop("p","2026-09-03",1100.0,5.0,false)
        val b = stop("b","2026-09-05",1300.0,15.0,true)
        val cycle = FuelTracking.cycles(listOf(b, a, partial)).single()
        assertEquals(15.0,cycle.mpg,0.001)
        assertEquals(20.0,cycle.gallons,0.001)
        assertEquals(8000,cycle.costCents.toInt())
        assertEquals(300.0,cycle.miles,0.001)
        assertEquals(2,cycle.stops.size)
    }
    @Test fun `weighted MPG uses total miles over total gallons`() {
        val cycles = FuelTracking.cycles(listOf(stop("a","2026-09-01",1000.0,1.0,true),stop("b","2026-09-02",1100.0,10.0,true),stop("c","2026-09-03",1500.0,20.0,true)))
        assertEquals(500.0/30,cycles.sumOf { it.miles }/cycles.sumOf { it.gallons },0.001)
    }
    @Test fun `missed fill resets baseline and does not invent efficiency`() {
        val a=stop("a","2026-09-01",1000.0,20.0,true)
        val p=stop("p","2026-09-02",1200.0,5.0,false,true)
        val b=stop("b","2026-09-03",1300.0,10.0,true)
        val c=stop("c","2026-09-04",1500.0,10.0,true)
        val cycle=FuelTracking.cycles(listOf(a,p,b,c)).single()
        assertEquals("b",cycle.start.id)
        assertEquals(20.0,cycle.mpg,0.001)
        assertTrue(FuelTracking.cycles(listOf(a,c.copy(missedFill=true))).isEmpty())
    }
    @Test fun `partial only and zero distance are not measured tanks`() {
        assertTrue(FuelTracking.cycles(listOf(stop("a","2026-09-01",1000.0,10.0,false),stop("b","2026-09-02",1100.0,10.0,false))).isEmpty())
        assertTrue(FuelTracking.cycles(listOf(stop("a","2026-09-01",1000.0,10.0,true),stop("b","2026-09-01",1000.0,10.0,true))).isEmpty())
    }
    @Test fun `sequence rejects odometer rollback across dates and sorts same day by mileage`() {
        val a=stop("a","2026-09-01",1000.0,10.0,true)
        val b=stop("b","2026-09-02",900.0,10.0,true)
        assertTrue(runCatching { FuelTracking.validateSequence(listOf(a,b)) }.isFailure)
        FuelTracking.validateSequence(listOf(a,b.copy(date=a.date)))
        assertEquals("b",FuelTracking.ordered(listOf(a,b.copy(date=a.date))).first().id)
    }
    @Test fun `invalid numeric data bars and capacities are rejected`() {
        val a=stop("a","2026-09-01",1000.0,10.0,true)
        for (row in listOf(a.copy(gallons=0.0),a.copy(gallons=Double.NaN),a.copy(odometer=Double.POSITIVE_INFINITY),a.copy(barsBefore=10),a.copy(barsAfter=4),a.copy(amountCents=0),a.copy(fullTank=false,barsBefore=6,barsAfter=4))) {
            assertTrue(runCatching { FuelTracking.validate(row) }.isFailure)
        }
        assertTrue(runCatching { FuelTracking.validate(VehicleSettings(tankGallons=Double.NaN)) }.isFailure)
        assertEquals(24.0,VehicleSettings().tankGallons,0.0)
    }
    @Test fun `gauge takes latest observation not an older fill or a future record`() {
        val a=stop("a","2026-09-01",1000.0,20.0,true)
        val state=LedgerState(fuelStops=listOf(a),fuelReadings=listOf(FuelReading("r","2026-09-01",3,1100.0,2000),FuelReading("future","2026-09-05",8,null,3000)))
        assertEquals(3,FuelTracking.latestGauge(state,LocalDate.parse("2026-09-02"))!!.bars)
        assertNull(FuelTracking.latestGauge(LedgerState()))
        assertEquals(9,FuelTracking.latestGauge(LedgerState(fuelStops=listOf(a)),LocalDate.parse(a.date))!!.bars)
    }
    @Test fun `backfilled same day fills do not replace a higher odometer gauge reading`() {
        val morning = stop("morning", "2026-09-01", 1000.0, 20.0, true).copy(createdAt = 3000)
        val later = FuelReading("later", morning.date, 3, 1200.0, 2000)
        val state = LedgerState(fuelStops = listOf(morning), fuelReadings = listOf(later))
        assertEquals(3, FuelTracking.latestGauge(state, LocalDate.parse(morning.date))!!.bars)
        val noOdometer = FuelReading("current", morning.date, 2, null, 4000)
        assertEquals(2, FuelTracking.latestGauge(state.copy(fuelReadings = listOf(later, noOdometer)), LocalDate.parse(morning.date))!!.bars)
    }
    @Test fun `pump price keeps thousandths of a dollar`() {
        assertEquals("$4.349", fuelUnitPrice(4349, 10.0))
        assertEquals("$0.267", fuelUnitPrice(8000, 300.0))
        assertEquals("—", fuelUnitPrice(1000, 0.0))
    }
    @Test fun `maintenance validates due dates and mileage`() {
        val record=MaintenanceRecord("s","2026-09-01","Oil change",1000.0,nextOdometer=6000.0,nextDate="2027-03-01")
        FuelTracking.validate(record)
        assertTrue(runCatching { FuelTracking.validate(record.copy(nextOdometer=900.0)) }.isFailure)
        assertTrue(runCatching { FuelTracking.validate(record.copy(nextDate=record.date)) }.isFailure)
    }
}
