package com.scott.shiftledger

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class EarningsRatesTest {
    private fun order(id: String, amount: Long, miles: Double?, date: String = "2026-10-02") = Entry("order:$id", "ORDER", date, amount, orderId = id, engagedMiles = miles)
    @Test fun `combined rate divides combined income by miles instead of averaging rates`() {
        val summary = summarizeRates(orderRates(listOf(order("1", 10000, 10.0), order("2", 10000, 90.0))))
        assertEquals(BigDecimal("2.00"), dollarsPerMile(summary.ratedEarningsCents, summary.miles))
    }
    @Test fun `linked adjustments change the order rate and stay on the order date`() {
        val entries = listOf(order("1", 10000, 25.0),
            Entry("a", "ADJUSTMENT", "2026-10-03", 2000, orderId = "1"),
            Entry("a2", "ADJUSTMENT", "2026-10-04", -500, orderId = "1"),
            Entry("orphan", "ADJUSTMENT", "2026-10-04", 99999, orderId = "missing"),
            Entry("p", "PAYOUT", "2026-10-02", 20000, engagedMiles = 250.0),
            Entry("m", "MANUAL", "2026-10-02", 5000))
        val orders = orderRates(entries)
        assertEquals(11500L, orders.single().earningsCents)
        assertEquals("$4.60", rateText(orders.single().earningsCents, orders.single().miles))
        assertEquals(LocalDate.parse("2026-10-02"), groupRates(orders, RatePeriod.DAILY).single().start)
    }
    @Test fun `unknown or zero miles exclude both pay and miles from rates`() {
        val summary = summarizeRates(orderRates(listOf(order("1", 10000, 50.0), order("2", 5000, 0.0), order("3", 3000, null))))
        assertEquals(18000L, summary.earningsCents)
        assertEquals(10000L, summary.ratedEarningsCents)
        assertEquals(2, summary.excludedCount)
        assertEquals("$2.00", rateText(summary.ratedEarningsCents, summary.miles))
        assertEquals("—", rateText(5000, 0.0))
        assertEquals("—", rateText(5000, Double.NaN))
    }
    @Test fun `weeks cross month and year boundaries with Monday starts`() {
        val rows = orderRates(listOf(order("1", 1000, 5.0, "2026-12-31"), order("2", 2000, 5.0, "2027-01-01")))
        val week = groupRates(rows, RatePeriod.WEEKLY).single()
        assertEquals(LocalDate.parse("2026-12-28"), week.start)
        assertEquals(LocalDate.parse("2027-01-03"), week.end)
        assertEquals(2, groupRates(rows, RatePeriod.MONTHLY).size)
        assertEquals(2, groupRates(rows, RatePeriod.YEARLY).size)
        assertEquals(LocalDate.parse("2024-02-29"), RatePeriod.MONTHLY.end(LocalDate.parse("2024-02-01")))
    }
    @Test fun `supplied Dispatch history gives order and day rates without orphan adjustment`() {
        val entries = DispatchParser.parse(javaClass.getResource("/dispatch-history.txt")!!.readText(), 2026).entries
        val rows = orderRates(entries)
        assertEquals(27, rows.size)
        assertEquals(415381L, summarizeRates(rows).earningsCents)
        val adjusted = rows.single { it.order.orderId == "2771899" }
        assertEquals("$3.12", rateText(adjusted.earningsCents, adjusted.miles))
        val day = groupRates(rows, RatePeriod.DAILY).single { it.start.toString() == "2026-10-02" }.summary
        assertEquals("$2.70", rateText(day.ratedEarningsCents, day.miles))
    }
    @Test fun `rates round to cents and do not overflow on tiny mileage`() {
        assertEquals("$0.67", rateText(100, 1.5))
        assertNotNull(dollarsPerMile(1_000_000_000_000, 0.0000000001))
        assertEquals("-$1.00", rateText(-1000, 10.0))
    }
}
