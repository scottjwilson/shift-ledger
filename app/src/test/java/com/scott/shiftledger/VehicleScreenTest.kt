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
@Config(sdk=[35], qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleScreenTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val model get()=ViewModelProvider(compose.activity)[LedgerViewModel::class.java]
    @Before fun setup()=runBlocking {
        val repository=model.repository
        withContext(Dispatchers.IO) { repository.database.clearAllTables() }
        model.setColorPalette(ColorPalette.OCEAN); model.setThemeMode(ThemeMode.DARK)
        val today=LocalDate.now()
        model.repository.saveFuel(FuelStop("start",today.minusDays(2).toString(),10000.0,20.0,8000,true,station="Starting tank",createdAt=1000))
        model.repository.saveFuel(FuelStop("partial",today.minusDays(1).toString(),10100.0,5.0,2000,false,station="Partial fill",createdAt=2000))
    }
    @Test fun `fuel form validates saves gauges survive recreation and service links to costs`() {
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("open_vehicle"))
        compose.onNodeWithTag("open_vehicle").performClick()
        compose.onNodeWithContentDescription("Fuel gauge: 9 of 9 bars").assertExists()
        screenshot("fuel-baseline-0.6.0")
        compose.onNodeWithText("+ Fill-up").performClick()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Odometer (mi)").performScrollTo().performTextInput("10300")
        compose.onNodeWithText("Gallons added (US)").performScrollTo().performTextInput("15")
        compose.onNodeWithText("Total paid ($)").performScrollTo().performTextInput("60")
        compose.onNodeWithContentDescription("Filled to full (pump shut off)").performScrollTo().performClick()
        screenshot("fuel-fill-form-0.6.0")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(20_000) { runBlocking { model.repository.snapshot().fuelStops.size==3 } }
        compose.onNodeWithTag("vehicle_list").performScrollToIndex(4)
        compose.onNodeWithText("15.00").assertExists()
        compose.onNodeWithText("$160.00").assertExists()
        screenshot("fuel-analysis-0.6.0")
        compose.onNodeWithTag("vehicle_list").performScrollToIndex(0)
        screenshot("fuel-gauge-ocean-0.6.0")
        compose.onNodeWithText("Update bars").performClick()
        repeat(6) { compose.onNodeWithContentDescription("Fewer fuel bars").performClick() }
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Fuel gauge: 3 of 9 bars").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Fuel gauge: 3 of 9 bars").assertExists()
        screenshot("fuel-gauge-low-0.6.0")
        compose.onNodeWithText("Maintenance",substring=false).performClick()
        compose.onNodeWithText("+ Add service").performClick()
        compose.onNodeWithText("Odometer (mi)").performScrollTo().performTextInput("10300")
        compose.onNodeWithText("Total paid ($, optional)").performScrollTo().performTextInput("100")
        compose.onNodeWithText("Next service odometer (optional)").performScrollTo().performTextInput("15300")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(20_000) { runBlocking { model.repository.snapshot().maintenance.size==1 } }
        compose.onNodeWithText("Next at 15300.00 mi").assertExists()
        screenshot("maintenance-0.6.0")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Costs").performClick()
        compose.onNodeWithText("$260.00").assertExists()
        compose.runOnIdle { runBlocking {
            val snapshot=model.repository.snapshot()
            assertEquals(4,snapshot.expenses.size)
            assertEquals(3,snapshot.fuelReadings.single().bars)
            assertEquals(15.0,FuelTracking.cycles(snapshot.fuelStops).single().mpg,0.001)
        } }
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
        val bitmap=compose.runOnIdle {
            val view=compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
