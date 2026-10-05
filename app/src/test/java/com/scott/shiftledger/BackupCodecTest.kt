package com.scott.shiftledger

import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun sample(): LedgerState {
        val source = ImportSource("source", 10, 2026, "Original \"quoted\" text\nline two")
        return LedgerState(
            listOf(Entry("o", "ORDER", "2026-10-02", 10000, sourceId = source.id), Entry("p", "PAYOUT", "2026-10-02", 9800)),
            listOf(Shift("s", "2026-10-02", 1000, endedAt = 5000, workedMillis = 4000, correctedMiles = 10.0)),
            listOf(Expense("e", "2026-10-02", 2500, "Fuel", "=HYPERLINK(\"bad\")")), listOf(source))
    }
    @Test fun `full backup roundtrip preserves original source and records`() {
        val state = sample()
        val decoded = BackupCodec.decode(BackupCodec.encode(state, 6000))
        assertEquals(state.entries, decoded.entries)
        assertEquals(state.shifts, decoded.shifts)
        assertEquals(state.expenses, decoded.expenses)
        assertEquals(state.sources, decoded.sources)
    }
    @Test fun `backup rejects duplicates invalid dates bad version and missing source`() {
        val state = sample()
        assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(state.copy(entries = state.entries + state.entries.first()), 6000)) }.isFailure)
        assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(state).replace("2026-10-02", "garbage")) }.isFailure)
        assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(state).replace("\"version\": 6", "\"version\": 99")) }.isFailure)
        assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(state.copy(sources = emptyList()))) }.isFailure)
    }
    @Test fun `CSV escapes formula prefixes quotes and keeps payout mileage out`() {
        val state = sample().copy(entries = sample().entries + Entry("manual", "MANUAL", "2026-10-02", 500, raw = "=SUM(1,2)"))
        val csv = BackupCodec.csv(state)
        assertTrue(csv.contains("\"'=SUM(1,2)\""))
        assertTrue(csv.contains("\"PAYOUT\""))
        assertTrue(csv.contains("Cash movement; do not add to earned income"))
        assertTrue(csv.contains("HYPERLINK(\"\"bad\"\")"))
    }
}
