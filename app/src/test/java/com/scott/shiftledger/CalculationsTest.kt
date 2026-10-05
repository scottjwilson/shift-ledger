package com.scott.shiftledger

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalculationsTest {
    @Test fun `totals exclude payouts from earnings and engaged miles from shift miles`() {
        val entries = listOf(Entry("o", "ORDER", "2026-10-02", 10000, engagedMiles = 10.0),
            Entry("p", "PAYOUT", "2026-10-02", 9800, engagedMiles = 10.0),
            Entry("a", "ADJUSTMENT", "2026-10-02", 500), Entry("old", "ORDER", "2026-09-01", 50000))
        val shift = Shift("s", "2026-10-02", 1000, endedAt = 7_201_000, workedMillis = 7_200_000, correctedMiles = 60.0)
        val result = totals(LedgerState(entries, listOf(shift), listOf(Expense("e", "2026-10-02", 2000, "Fuel"))), LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-04"), 7_201_000)
        assertEquals(10500L, result.earned)
        assertEquals(9800L, result.paid)
        assertEquals(2000L, result.expenses)
        assertEquals(60.0, result.miles, 0.0)
        assertEquals(2.0, result.hours, 0.0)
    }
    @Test fun `money parser uses integer cents and rejects fractional cents`() {
        assertEquals(144900L, cents("1,449.00"))
        assertEquals(54L, cents("0.54"))
        assertTrue(runCatching { cents("1.001") }.isFailure)
    }
    @Test fun `running time never goes negative after clock adjustment`() {
        assertEquals(500L, Shift("s", "2026-10-02", 1000, runningSince = 2000, workedMillis = 500).durationMillis(1000))
    }
    @Test fun `GPS rejects noisy and implausible fixes and does not bridge a gap`() {
        val accumulator = LocationAccumulator()
        assertEquals(0.0, accumulator.add(Fix(37.0, -122.0, 8f, 1000)).meters, 0.0)
        assertEquals(0.0, accumulator.add(Fix(37.00001, -122.0, 8f, 6000)).meters, 0.0)
        val good = accumulator.add(Fix(37.001, -122.0, 8f, 16000))
        assertEquals(111.2, good.meters, 1.0)
        assertFalse(accumulator.add(Fix(38.0, -122.0, 8f, 21000)).accepted)
        assertFalse(accumulator.add(Fix(37.002, -122.0, 100f, 26000)).accepted)
        val gap = accumulator.add(Fix(37.003, -122.0, 8f, 200000))
        assertTrue(gap.gap)
        assertEquals(0.0, gap.meters, 0.0)
        assertFalse(accumulator.add(Fix(37.004, -122.0, 8f, 100000)).accepted)
    }
    @Test fun `reset does not count personal driving between sessions`() {
        val accumulator = LocationAccumulator()
        accumulator.add(Fix(37.0, -122.0, 8f, 1000))
        accumulator.reset()
        assertEquals(0.0, accumulator.add(Fix(38.0, -122.0, 8f, 20000)).meters, 0.0)
    }
}
