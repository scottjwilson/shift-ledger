package com.scott.shiftledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoalPersistenceTest {
    @Test fun `goal changes survive database reopen and backup restore without rewriting past targets`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "goal-persistence.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        var db = open()
        try {
            var repo = LedgerRepository(db)
            repo.saveGoal(180000, LocalDate.parse("2026-10-02"))
            repo.saveGoal(175000, LocalDate.parse("2026-10-04"))
            repo.saveGoal(200000, LocalDate.parse("2026-10-05"))
            db.close(); db = open(); repo = LedgerRepository(db)
            val snapshot = repo.snapshot()
            assertEquals(2, snapshot.goalPlans.size)
            assertEquals(175000L, WeeklyGoals.target(snapshot.goalPlans, LocalDate.parse("2026-09-28")))
            assertEquals(150000L, WeeklyGoals.target(snapshot.goalPlans, LocalDate.parse("2026-09-21")))
            val backup = BackupCodec.decode(BackupCodec.encode(snapshot))
            assertEquals(snapshot.goalPlans, backup.goalPlans)
            val other = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
            try {
                val target = LedgerRepository(other)
                target.saveGoal(190000, LocalDate.parse("2026-10-05"))
                val report = target.restore(backup)
                assertTrue(report, report.contains("1 conflicting"))
                assertEquals(190000L, WeeklyGoals.target(target.snapshot().goalPlans, LocalDate.parse("2026-10-05")))
                assertEquals(175000L, WeeklyGoals.target(target.snapshot().goalPlans, LocalDate.parse("2026-09-28")))
            } finally { other.close() }
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun `old backups default safely and invalid goal data cannot be restored`() {
        for (version in 1..3) {
            val old = """{"format":"shift-ledger","version":$version,"createdAt":5000,"entries":[],"shifts":[],"expenses":[],"sources":[]}"""
            assertTrue(BackupCodec.decode(old).goalPlans.isEmpty())
        }
        val plan = WeeklyGoalPlan("2026-09-28", 150000)
        for (plans in listOf(listOf(plan.copy(targetCents = 0)), listOf(plan.copy(startWeek = "2026-09-29")), listOf(plan.copy(startWeek = "bad")), listOf(plan, plan))) {
            assertTrue(runCatching { BackupCodec.decode(BackupCodec.encode(LedgerState(goalPlans = plans))) }.isFailure)
        }
    }
}
