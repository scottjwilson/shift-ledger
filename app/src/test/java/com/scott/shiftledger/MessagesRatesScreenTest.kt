package com.scott.shiftledger

import android.content.ClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessagesRatesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun freshLedger() = runBlocking {
        val repository = androidx.lifecycle.ViewModelProvider(compose.activity)[LedgerViewModel::class.java].repository
        withContext(Dispatchers.IO) { repository.database.clearAllTables() }
        repository.saveTemplate("starter-sunrun-score", SupportMessages.SUNRUN_TITLE, SupportMessages.SUNRUN_BODY)
    }
    @Test fun `messages copy and persist edits while monthly rates open order-specific messages`() {
        compose.onNodeWithText("Saved messages").performClick()
        try { compose.waitUntil(10_000) { compose.onAllNodesWithText(SupportMessages.SUNRUN_TITLE).fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Exception) {
            screenshot("messages-debug"); println(compose.onRoot().printToString())
            println(runBlocking { (compose.activity.application as LedgerApplication).repository.dao.templates() })
            throw error
        }
        compose.onNodeWithText("Use message").performScrollTo().performClick()
        compose.onNodeWithText("Copy message").assertIsNotEnabled()
        compose.onNodeWithText("Order number").performTextInput("2813447")
        compose.onNodeWithText("Copy message").performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(SupportMessages.render(SupportMessages.SUNRUN_BODY, "2813447"), clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        screenshot("message-ready")
        compose.onNodeWithText("Back to messages").performScrollTo().performClick()
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        compose.onNodeWithText("Message title").performTextReplacement("My support template")
        compose.onNodeWithText("Template text").performTextReplacement("Please review order #{order}. Thank you.")
        compose.onNodeWithText("Save message").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Use message").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("My support template").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            runBlocking {
                androidx.lifecycle.ViewModelProvider(compose.activity)[LedgerViewModel::class.java].repository.commitImport(
                    DispatchParser.parse(javaClass.getResource("/dispatch-history.txt")!!.readText(), 2026))
            }
        }
        compose.onNodeWithText("Dollars per mile").performClick()
        try { compose.waitUntil(10_000) { compose.onAllNodesWithText("$2.93 / mi").fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Exception) { screenshot("rates-debug"); println(compose.onRoot().printToString()); throw error }
        compose.onNodeWithText("Monthly").performScrollTo().performClick()
        compose.onNodeWithTag("rates_list").performScrollToNode(hasText("October 2026"))
        screenshot("monthly-rates")
        compose.onNodeWithText("October 2026").performClick()
        compose.onNodeWithTag("rates_list").performScrollToNode(hasText("Order 2813447"))
        compose.onNodeWithText("$2.84 / mi").assertIsDisplayed()
        screenshot("order-rates")
        compose.onNodeWithTag("rates_list").performScrollToNode(hasText("Support message for this order"))
        compose.onAllNodesWithText("Support message for this order").onFirst().performClick()
        compose.onNodeWithText("Use message").performScrollTo().performClick()
        compose.onNodeWithText("Order number").assertTextContains("2813447")
        compose.onNodeWithText("Copy message").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Please review order #2813447. Thank you.", compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
    }
    private fun screenshot(name: String) {
        val image = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
