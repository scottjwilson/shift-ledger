package com.scott.shiftledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun emptyLedger() = runBlocking {
        // AndroidViewModelFactory can retain its first Application across Robolectric cases.
        val repository = androidx.lifecycle.ViewModelProvider(compose.activity)[LedgerViewModel::class.java].repository
        withContext(Dispatchers.IO) { repository.database.clearAllTables() }
    }
    @Test fun `screens launch and import preview shows real sample totals`() {
        compose.onNodeWithText("Your workday").assertIsDisplayed()
        compose.onNodeWithText("Start shift").assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithText("Earnings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Import Dispatch history").assertIsDisplayed()
        compose.onNodeWithText("Paste earnings history").performTextInput(javaClass.getResource("/dispatch-history.txt")!!.readText())
        compose.onNodeWithText("Year for payouts without a year").performTextReplacement("2026")
        compose.onNodeWithText("Preview import").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Check before saving").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Check before saving").performScrollTo().assertIsDisplayed()
        try { compose.onNodeWithText("New earnings: $4,169.81").performScrollTo().assertIsDisplayed() }
        catch (error: AssertionError) {
            screenshot("import-debug"); println(compose.onRoot().printToString())
            println(androidx.lifecycle.ViewModelProvider(compose.activity)[LedgerViewModel::class.java].preview.value)
            throw error
        }
        compose.onNodeWithText("New payouts: $4,208.64").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save 50 new rows").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Saved 50 new rows. Payouts remain separate from earnings.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Data", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Dark").performClick().assertIsSelected()
        screenshot("appearance-dark")
        // Reopening the activity must retain both the preference and imported records.
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Dark").assertIsSelected()
        compose.onNodeWithText("Save full backup").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Today"))
        compose.onNodeWithText("All history").performScrollTo().performClick()
        compose.onNodeWithText("$4,169.81").performScrollTo().assertIsDisplayed()
        screenshot("home-dark")
        compose.onNodeWithText("Earnings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Import Dispatch history").assertIsDisplayed()
        screenshot("earnings-dark")
        compose.onNodeWithText("Shifts", useUnmergedTree = true).performClick()
        compose.onNodeWithText("No shifts yet").assertIsDisplayed()
        screenshot("shifts-dark")
        compose.onNodeWithText("Costs", useUnmergedTree = true).performClick()
        compose.onNodeWithText("All recorded expenses").assertIsDisplayed()
        screenshot("costs-dark")
        compose.onNodeWithText("Data", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Light").performClick().assertIsSelected()
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Start shift").assertIsDisplayed()
        screenshot("home-light")
        compose.onNodeWithText("Data", useUnmergedTree = true).performClick()
        compose.onNodeWithText("System").performClick().assertIsSelected()
        compose.onNodeWithText("Choose colors & fonts").performScrollTo().performClick()
        compose.onNodeWithText("Make it yours").assertIsDisplayed()
        fun palette(choice: ColorPalette) {
            compose.onNodeWithTag("appearance_list").performScrollToNode(hasTestTag("palette_${choice.name}"))
            compose.onNodeWithTag("palette_${choice.name}").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        }
        fun font(choice: LedgerFont) {
            compose.onNodeWithTag("appearance_list").performScrollToNode(hasTestTag("font_${choice.name}"))
            compose.onNodeWithTag("font_${choice.name}").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        }
        ColorPalette.entries.forEach(::palette)
        palette(ColorPalette.VIOLET)
        compose.onNodeWithTag("appearance_list").performScrollToIndex(2)
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        compose.onNodeWithTag("appearance_list").performScrollToIndex(0)
        screenshot("appearance-violet-dark")
        compose.onNodeWithTag("appearance_list").performScrollToIndex(2)
        compose.onNodeWithText("Light").performScrollTo().performClick()
        palette(ColorPalette.OCEAN)
        font(LedgerFont.CLASSIC)
        compose.onNodeWithTag("appearance_list").performScrollToIndex(0)
        screenshot("appearance-ocean-classic")
        palette(ColorPalette.BLACK)
        font(LedgerFont.MONO)
        compose.onNodeWithTag("appearance_list").performScrollToIndex(2)
        compose.onNodeWithText("Light").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Pure Black keeps backgrounds black in every mode.").assertExists()
        compose.onNodeWithTag("appearance_list").performScrollToIndex(0)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("LIVE PREVIEW · Pure Black / Mono").assertIsDisplayed()
        screenshot("appearance-pure-black")
        // Read through a new ViewModel too: recreation alone can retain the old ViewModel.
        compose.runOnIdle {
            val displayed = androidx.lifecycle.ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
            val reopened = LedgerViewModel(displayed.getApplication())
            org.junit.Assert.assertEquals(ColorPalette.BLACK, reopened.colorPalette.value)
            org.junit.Assert.assertEquals(LedgerFont.MONO, reopened.fontStyle.value)
            org.junit.Assert.assertEquals(ThemeMode.LIGHT, reopened.themeMode.value)
        }
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("weekly_goal_card"))
        compose.onNodeWithTag("weekly_goal_card").performClick()
        screenshot("goal-pure-black-mono")
        compose.onNodeWithText("Data", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Choose colors & fonts").performScrollTo().performClick()
        compose.onNodeWithTag("appearance_list").performScrollToNode(hasText("Reset appearance"))
        compose.onNodeWithText("Reset appearance").performScrollTo().performClick()
        compose.onNodeWithTag("appearance_list").performScrollToIndex(0)
        compose.onNodeWithText("LIVE PREVIEW · Forest / Modern").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        val image = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val output = File("build/screenshots/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
