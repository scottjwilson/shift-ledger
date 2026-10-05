package com.scott.shiftledger

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ShiftImprovementsTest {
    @org.junit.Test fun `OCR payout separators may disappear but uncertain duration is never guessed`() {
        val text = "\$99.50 Oct 2, 2026, 2:41 PM Thr 6mins 35.01 mi Order 12345\n\$98.95 Payout Oct 2 Paid 1hr 6mins 35mi"
        val parsed = DispatchParser.parse(text, 2026)
        org.junit.Assert.assertEquals("PAYOUT", parsed.entries.single().kind)
        org.junit.Assert.assertEquals(9895L, parsed.entries.single().amountCents)
        org.junit.Assert.assertTrue(parsed.warnings.any { it.contains("Unreadable duration") })
        val corrected = DispatchParser.parse(text.replace("Thr", "1hr"), 2026)
        org.junit.Assert.assertEquals(66, corrected.entries.single { it.kind == "ORDER" }.engagedMinutes)
    }
    private val now = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
    @Test fun `GPS health distinguishes no fix stale fix pause and process death`() {
        val shift = Shift("s", "2026-10-04", now - 100_000, runningSince = now - 100_000)
        assertEquals(GpsPhase.LOST, gpsHealth(shift, true, GpsEnvironment(), now).phase)
        assertEquals(GpsPhase.WAITING, gpsHealth(shift.copy(runningSince = now - 10_000), true, GpsEnvironment(), now).phase)
        assertEquals(GpsPhase.TRACKING, gpsHealth(shift.copy(lastFixAt = now - 5_000), true, GpsEnvironment(accuracyMeters = 8f), now).phase)
        assertEquals(GpsPhase.INTERRUPTED, gpsHealth(shift, false, GpsEnvironment(), now).phase)
        assertEquals(GpsPhase.DISABLED, gpsHealth(shift, true, GpsEnvironment(locationEnabled = false), now).phase)
        assertEquals(GpsPhase.PERMISSION, gpsHealth(shift, true, GpsEnvironment(precisePermission = false), now).phase)
        assertEquals(GpsPhase.PAUSED, gpsHealth(shift.copy(runningSince = null), false, GpsEnvironment(), now).phase)
    }
    @Test fun `overnight correction subtracts breaks and preserves raw time`() {
        val times = ShiftTimes.parse("2026-10-03 23:00", "2026-10-04 02:00", "30", now, ZoneId.of("UTC"))
        assertEquals(9_000_000L, times.workedMillis)
        val original = Shift("s", "2026-10-03", now - 10000, endedAt = now, workedMillis = 10000)
        val corrected = original.copy(correctedStartedAt = times.start, correctedEndedAt = times.end, correctedWorkedMillis = times.workedMillis)
        assertEquals(10000L, corrected.workedMillis)
        assertEquals(9_000_000L, corrected.durationMillis(now))
        val backup = BackupCodec.decode(BackupCodec.encode(LedgerState(shifts = listOf(corrected)), now))
        assertEquals(corrected, backup.shifts.single())
    }
    @Test fun `invalid breaks reversed future and ambiguous times are rejected`() {
        val zone = ZoneId.of("UTC")
        assertTrue(runCatching { ShiftTimes.parse("2026-10-04 09:00", "2026-10-04 10:00", "61", now, zone) }.isFailure)
        assertTrue(runCatching { ShiftTimes.parse("2026-10-04 11:00", "2026-10-04 10:00", "0", now, zone) }.isFailure)
        assertTrue(runCatching { ShiftTimes.parse("2027-10-04 09:00", "2027-10-04 10:00", "0", now, zone) }.isFailure)
        assertTrue(runCatching { ShiftTimes.parse("2026-03-08 02:30", "2026-03-08 04:00", "0", now, ZoneId.of("America/Los_Angeles")) }.isFailure)
    }
    @Test fun `old backup without correction fields still restores`() {
        val json = """{"format":"shift-ledger","version":1,"createdAt":5000,"entries":[],"expenses":[],"sources":[],"shifts":[{"id":"s","date":"2026-10-04","startedAt":1000,"endedAt":5000,"workedMillis":4000}]}"""
        val shift = BackupCodec.decode(json).shifts.single()
        assertNull(shift.correctedWorkedMillis)
        assertEquals(4000L, shift.durationMillis(5000))
    }
    @Test fun `OCR table elements are joined by physical row instead of text block`() {
        fun box(text: String, x: Float, y: Float) = TextFragment(text, x, y, x + 70, y + 12)
        val items = listOf(box("$99.50", 0f, 10f), box("$8.00", 0f, 50f),
            box("Oct 2, 2026, 2:41PM", 100f, 11f), box("Oct 2, 2026, 3:00PM", 100f, 50f),
            box("1hr 6mins 35.01mi Order 2813447", 300f, 10f), box("Order 2813447 Adjustment", 300f, 51f))
        val parsed = DispatchParser.parse(DocumentRows.arrange(items), 2026)
        assertEquals(2, parsed.entries.size)
        assertEquals(9950L, parsed.entries.first { it.kind == "ORDER" }.amountCents)
        assertEquals(800L, parsed.entries.first { it.kind == "ADJUSTMENT" }.amountCents)
    }
    @Test fun `layout that merges unrelated orders is rejected`() {
        val parsed = DispatchParser.parse("$99.50 Oct 2, 2026, 2:41PM 1hr 6mins 35mi Order 1 Order 2", 2026)
        assertTrue(parsed.entries.isEmpty())
        assertTrue(parsed.warnings.any { it.contains("Multiple order IDs") })
    }
}
