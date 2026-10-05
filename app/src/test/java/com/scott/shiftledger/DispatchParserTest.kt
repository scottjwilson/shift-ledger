package com.scott.shiftledger

import org.junit.Assert.*
import org.junit.Test

class DispatchParserTest {
    private fun sample() = javaClass.getResource("/dispatch-history.txt")!!.readText()

    @Test fun `extracts the real pasted history without double counting payouts or headers`() {
        val parsed = DispatchParser.parse(sample(), 2026, 1)
        val orders = parsed.entries.filter { it.kind == "ORDER" }
        val adjustments = parsed.entries.filter { it.kind == "ADJUSTMENT" }
        val payouts = parsed.entries.filter { it.kind == "PAYOUT" }
        assertEquals(50, parsed.entries.size)
        assertEquals(27, orders.size)
        assertEquals(5, adjustments.size)
        assertEquals(18, payouts.size)
        assertEquals(406681L, orders.sumOf { it.amountCents })
        assertEquals(10300L, adjustments.sumOf { it.amountCents })
        assertEquals(420864L, payouts.sumOf { it.amountCents })
        assertEquals(1419.19, orders.sumOf { it.engagedMiles!! }, 0.0001)
        assertEquals(1644, orders.sumOf { it.engagedMinutes!! })
        assertTrue(parsed.warnings.none { it.startsWith("Skipped") })
        assertTrue(parsed.warnings.any { it.contains("2806287") })
        assertEquals("2026-10-02", payouts.first().date)
    }

    @Test fun `reimporting is idempotent across whitespace and headers`() {
        val first = DispatchParser.parse(sample(), 2026)
        val again = DispatchParser.parse(sample().replace("\n", "   "), 2026)
        val preview = DispatchParser.preview(again, first.entries)
        assertEquals(0, preview.newEntries.size)
        assertEquals(50, preview.duplicates)
        assertTrue(preview.conflicts.isEmpty())
    }

    @Test fun `changed order amount is a conflict not new earnings`() {
        val original = DispatchParser.parse(sample(), 2026)
        val changed = DispatchParser.parse(sample().replace("$99.50", "$100.50"), 2026)
        val preview = DispatchParser.preview(changed, original.entries)
        assertEquals(1, preview.conflicts.size)
        assertEquals(0, preview.newEntries.size)
        assertEquals("2813447", preview.conflicts.single().orderId)
    }

    @Test fun `changed adjustment amount is a conflict`() {
        val original = DispatchParser.parse(sample(), 2026)
        val changed = DispatchParser.parse(sample().replace("$8.00", "$9.00"), 2026)
        assertEquals(1, DispatchParser.preview(changed, original.entries).conflicts.size)
    }

    @Test fun `wrong payout year excludes ambiguous payouts`() {
        val result = DispatchParser.parse(sample(), 2025)
        assertEquals(32, result.entries.size)
        assertTrue(result.entries.none { it.kind == "PAYOUT" })
        assertTrue(result.warnings.any { it.contains("Payouts were excluded") })
    }

    @Test fun `partial order metrics are flagged rather than inferred as adjustment`() {
        val result = DispatchParser.parse("$42.00 Oct 2, 2026, 2:41PM 35mi Order 123", 2026)
        assertTrue(result.entries.isEmpty())
        assertTrue(result.warnings.any { it.contains("Incomplete") })
    }

    @Test fun `negative adjustment uses exact signed cents`() {
        val result = DispatchParser.parse("-$8.25 Oct 2, 2026, 2:41PM Order 123 Adjustment", 2026)
        assertEquals(-825L, result.entries.single().amountCents)
        assertEquals("ADJUSTMENT", result.entries.single().kind)
    }

    @Test fun `conflicting repeated orders within same import are excluded`() {
        val result = DispatchParser.parse("$42.00 Oct 2, 2026, 2:41PM 30mins 35mi Order 123\n$43.00 Oct 2, 2026, 2:41PM 30mins 35mi Order 123", 2026)
        assertTrue(result.entries.isEmpty())
        assertTrue(result.warnings.any { it.contains("conflicting rows") })
    }
}
