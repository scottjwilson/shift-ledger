package com.scott.shiftledger

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WeeklyGoalsTest {
    private val sunday = LocalDate.parse("2026-10-04")
    private fun income(date: String, amount: Long, kind: String = "ORDER") = Entry("$date-$kind", kind, date, amount)
    @Test fun `net uses recorded dates excludes payouts and future records`() {
        val state = LedgerState(entries = listOf(
            income("2026-09-28", 100000), income("2026-09-28", 100000, "PAYOUT"),
            income("2026-10-02", 60000, "MANUAL"), income("2026-10-04", -5000, "ADJUSTMENT"),
            income("2026-10-05", 999999), income("2026-09-27", 10000)
        ), expenses = listOf(Expense("fuel", "2026-10-04", 12500, "Fuel"), Expense("later", "2026-10-05", 1000, "Fuel")))
        val result = WeeklyGoals.dashboard(state, sunday)
        assertEquals("2026-09-28", result.current.start.toString())
        assertEquals(155000L, result.current.earned)
        assertEquals(142500L, result.current.net)
        assertEquals("95%", result.current.percent)
        assertEquals(7500L, result.current.dailyPace(sunday))
        assertEquals(-17500L, result.current.days.last().net)
        assertEquals(1, result.history.size)
        val thursday = WeeklyGoals.dashboard(state, sunday.minusDays(3)).current
        assertEquals(100000L, thursday.net)
        assertEquals(0L, thursday.days.last().net)
        assertEquals(12500L, thursday.dailyPace(sunday.minusDays(3)))
    }
    @Test fun `negative net and fractional day pace never understate amount needed`() {
        val monday = sunday.plusDays(1)
        val state = LedgerState(expenses = listOf(Expense("fuel", monday.toString(), 1, "Fuel")))
        val week = WeeklyGoals.dashboard(state, monday).current
        assertEquals(-1L, week.net)
        assertEquals(0f, week.progress)
        assertEquals("0%", week.percent)
        assertEquals(21429L, week.dailyPace(monday))
        assertFalse(week.milestoneReached(25))
        val pastOnly = WeeklyGoals.dashboard(state, monday.plusWeeks(1))
        assertEquals(-1L, pastOnly.best!!.net) // An empty new week isn't a record best.
    }
    @Test fun `milestones unlock at exact cents and progress caps visually without losing surplus`() {
        val before = WeeklyGoals.dashboard(LedgerState(entries = listOf(income(sunday.toString(), 37499))), sunday).current
        assertFalse(before.milestoneReached(25))
        val quarter = before.copy(days = listOf(GoalDay(sunday, 37500, 0)))
        assertTrue(quarter.milestoneReached(25))
        val won = before.copy(days = listOf(GoalDay(sunday, 165000, 0)))
        assertTrue(won.reached)
        assertEquals(1f, won.progress)
        assertEquals("110%", won.percent)
        assertEquals(0L, won.remaining)
        assertEquals(0L, won.dailyPace(sunday))
        assertEquals("99%", before.copy(days = listOf(GoalDay(sunday, 149999, 0))).percent)
    }
    @Test fun `streak waits for current week ends and breaks across missing weeks`() {
        val state = LedgerState(entries = listOf(income("2026-09-14", 150000), income("2026-09-21", 160000)))
        assertEquals(2, WeeklyGoals.dashboard(state, sunday).streak)
        assertEquals(0, WeeklyGoals.dashboard(state, sunday.plusDays(1)).streak)
        val currentWin = state.copy(entries = state.entries + income("2026-10-04", 150000))
        assertEquals(3, WeeklyGoals.dashboard(currentWin, sunday).streak)
        assertEquals(3, WeeklyGoals.dashboard(currentWin, sunday).wins)
        val gap = currentWin.copy(entries = currentWin.entries.filter { it.date != "2026-09-21" })
        assertEquals(1, WeeklyGoals.dashboard(gap, sunday).streak)
        val cost = currentWin.copy(expenses = listOf(Expense("repair", sunday.toString(), 10000, "Repairs")))
        assertEquals(2, WeeklyGoals.dashboard(cost, sunday).wins)
        assertEquals(2, WeeklyGoals.dashboard(cost, sunday).streak)
    }
    @Test fun `goal changes preserve prior weeks and carry into new year`() {
        val state = LedgerState(entries = listOf(income("2026-09-21", 160000), income("2026-10-02", 160000)),
            goalPlans = listOf(WeeklyGoalPlan("2026-09-28", 180000), WeeklyGoalPlan("2026-10-12", 200000)))
        val result = WeeklyGoals.dashboard(state, sunday)
        assertEquals(180000L, result.current.target)
        assertEquals(150000L, result.history.single().target)
        assertEquals(1, result.wins)
        assertEquals(180000L, WeeklyGoals.dashboard(state, sunday.plusDays(1)).current.target)
        assertEquals(200000L, WeeklyGoals.dashboard(state, LocalDate.parse("2027-01-01")).current.target)
        assertEquals(LocalDate.parse("2026-12-28"), WeeklyGoals.monday(LocalDate.parse("2027-01-01")))
    }
    @Test fun `empty ledger starts with default goal and no achievements`() {
        val result = WeeklyGoals.dashboard(LedgerState(), sunday)
        assertEquals(150000L, result.current.target)
        assertEquals(0L, result.current.net)
        assertEquals(0, result.wins)
        assertEquals(0, result.streak)
        assertNull(result.best)
        assertTrue(result.history.isEmpty())
    }
}
