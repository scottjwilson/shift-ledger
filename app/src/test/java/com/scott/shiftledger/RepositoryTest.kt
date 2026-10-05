package com.scott.shiftledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoryTest {
    private lateinit var database: LedgerDatabase
    private lateinit var repository: LedgerRepository
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).allowMainThreadQueries().build()
        repository = LedgerRepository(database)
    }
    @After fun close() { database.close() }
    @Test fun `real database deduplicates imports and undo removes only imported records`() = runTest {
        val parsed = DispatchParser.parse(javaClass.getResource("/dispatch-history.txt")!!.readText(), 2026)
        assertEquals(50, repository.commitImport(parsed))
        assertEquals(0, repository.commitImport(parsed))
        repository.dao.insertExpenses(listOf(Expense("expense", "2026-10-04", 2000, "Fuel")))
        repository.undoImport(parsed.source.id)
        assertEquals(0, repository.dao.entries().size)
        assertEquals(1, repository.dao.expenses().size)
        assertEquals(0, repository.dao.sources().size)
    }
    @Test fun `paused shifts ignore locations and resuming retains accumulated work`() = runTest {
        repository.startShift(null, 1000)
        repository.recordDistance(100.0, false, 2000)
        repository.pauseShift(3000)
        repository.recordDistance(500.0, false, 4000)
        repository.resumeShift(5000)
        repository.recordDistance(100.0, false, 6000)
        repository.endShift(7000)
        val saved = repository.dao.shifts().single()
        assertEquals(4000L, saved.workedMillis)
        assertEquals(200.0, saved.gpsMeters, 0.0)
        assertEquals(0, saved.gaps)
        assertNull(saved.runningSince)
    }
    @Test fun `restore merges missing records while retaining newer existing values`() = runTest {
        val original = Entry("o", "ORDER", "2026-10-02", 10000)
        repository.dao.insertEntries(listOf(original.copy(amountCents = 12000)))
        val backup = LedgerBackup(createdAt = 6000, entries = listOf(original, Entry("p", "PAYOUT", "2026-10-02", 9800)),
            shifts = listOf(Shift("s", "2026-10-02", 1000, runningSince = 1000)), expenses = emptyList(), sources = emptyList())
        val report = repository.restore(backup)
        assertEquals(12000L, repository.dao.entries().first { it.id == "o" }.amountCents)
        assertEquals(2, repository.dao.entries().size)
        assertTrue(report.contains("1 conflicting"))
        val restored = repository.dao.shifts().single()
        assertEquals(6000L, restored.endedAt)
        assertEquals(5000L, restored.workedMillis)
        assertFalse(restored.reviewed)
        assertEquals(1, restored.gaps)
    }
    @Test fun `restart without pause flags tracking interruption`() = runTest {
        repository.startShift(null, 1000)
        repository.resumeShift(5000)
        assertEquals(1, repository.dao.activeShift()!!.gaps)
        assertTrue(runCatching { repository.startShift(null, 6000) }.isFailure)
    }
    @Test fun `time corrections update totals without replacing recorded timestamps`() = runTest {
        val start = java.time.Instant.parse("2026-10-01T09:00:00Z").toEpochMilli()
        val shift = Shift("edit", "2026-10-01", start, endedAt = start + 7_200_000, workedMillis = 7_200_000)
        repository.dao.insertShifts(listOf(shift))
        repository.reviewShift(shift.id, 22.0, "Forgot to start", ShiftTiming(start - 3_600_000, start + 7_200_000, 9_000_000))
        val corrected = repository.dao.shifts().single()
        assertEquals(start, corrected.startedAt)
        assertEquals(7_200_000L, corrected.workedMillis)
        assertEquals(9_000_000L, corrected.durationMillis(System.currentTimeMillis()))
        repository.reviewShift(shift.id, 22.0, "Reset", resetTimes = true)
        assertEquals(7_200_000L, repository.dao.shifts().single().durationMillis(System.currentTimeMillis()))
    }
}
