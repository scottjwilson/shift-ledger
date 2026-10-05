package com.scott.shiftledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoalsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
    @Before fun setup() = runBlocking {
        val repository = model.repository
        withContext(Dispatchers.IO) { repository.database.clearAllTables() }
        val monday = WeeklyGoals.monday(LocalDate.now())
        val elapsedDays = LocalDate.now().dayOfWeek.value
        val currentIncome = (0 until elapsedDays).map { day ->
            Entry("current-$day", "ORDER", monday.plusDays(day.toLong()).toString(),
                180000L / elapsedDays + if (day == elapsedDays - 1) 180000L % elapsedDays else 0)
        }
        model.repository.dao.insertEntries(currentIncome + listOf(
            Entry("payout", "PAYOUT", monday.toString(), 180000),
            Entry("last", "ORDER", monday.minusWeeks(1).toString(), 155000),
            Entry("earlier", "ORDER", monday.minusWeeks(2).toString(), 165000)))
        model.repository.dao.insertExpenses(listOf(Expense("fuel", monday.toString(), 37500, "Fuel")))
    }
    @Test fun `goal opens from home edits persist and expenses update milestones and history`() {
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("weekly_goal_card"))
        compose.onNodeWithTag("weekly_goal_card").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("$1,425.00").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("75 percent milestone unlocked").assertExists()
        compose.onNodeWithContentDescription("100 percent milestone locked").assertExists()
        compose.runOnIdle { model.setThemeMode(ThemeMode.DARK) }
        screenshot("weekly-goal-dark")
        compose.runOnIdle { model.setThemeMode(ThemeMode.LIGHT) }
        screenshot("weekly-goal-light")
        compose.onNodeWithTag("goals_list").performScrollToNode(hasText("Edit weekly goal"))
        compose.onNodeWithText("Edit weekly goal").performClick()
        compose.onNodeWithText("Weekly net goal ($)").performTextReplacement("0")
        compose.onNodeWithText("Save weekly goal").assertIsNotEnabled()
        compose.onNodeWithText("Weekly net goal ($)").performTextReplacement("1400")
        compose.onNodeWithText("Save weekly goal").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Your weekly quest").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("100 percent milestone unlocked").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("of $1,400.00").assertIsDisplayed()
        screenshot("weekly-goal-won")
        compose.onNodeWithTag("goals_list").performScrollToIndex(3)
        compose.onNode(hasText("Mon") and hasClickAction()).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(WeeklyGoals.monday(LocalDate.now()).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", java.util.Locale.US))).assertExists()
        screenshot("weekly-goal-chart")
        compose.onNodeWithTag("goals_list").performScrollToNode(hasText("Your achievements"))
        compose.onNodeWithText("★ First finish · Unlocked").assertExists()
        screenshot("weekly-goal-achievements")
        compose.runOnIdle {
            runBlocking {
                assertEquals(140000L, model.repository.snapshot().goalPlans.single().targetCents)
                assertEquals(listOf(150000L, 150000L), WeeklyGoals.dashboard(model.repository.snapshot(), LocalDate.now()).history.map { it.target })
                model.repository.dao.insertExpenses(listOf(Expense("repair", LocalDate.now().toString(), 10000, "Repairs")))
            }
        }
        compose.onNodeWithTag("goals_list").performScrollToIndex(0)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("$1,325.00").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("100 percent milestone locked").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("weekly_goal_card"))
        compose.onNodeWithText("$1,325.00 / $1,400.00").assertExists()
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
