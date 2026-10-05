package com.scott.shiftledger

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetTest {
    @Test fun `widget counts earnings separately from payouts and routes current shift controls`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LedgerApplication>()
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        manager.bindAppWidgetId(42, ComponentName(app, LedgerWidget::class.java))
        val repository = app.repository
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        repository.dao.insertEntries(listOf(Entry("earned", "ORDER", today, 9950), Entry("paid", "PAYOUT", today, 9895)))
        suspend fun update(): View { LedgerWidget.updateAll(app); return manager.getViewFor(42) }
        fun text(view: View, id: Int) = view.findViewById<TextView>(id).text.toString()
        fun click(view: View, id: Int, expected: String) {
            view.findViewById<View>(id).performClick()
            val intent = shadowOf(app).nextStartedActivity
            assertEquals(MainActivity::class.java.name, intent.component!!.className)
            assertEquals(expected, intent.getStringExtra(LedgerWidget.EXTRA_ACTION))
        }
        try {
            var view = update()
            assertEquals("$99.50 earned today", text(view, R.id.widget_earnings))
            click(view, R.id.widget_primary, "start")
            click(view, R.id.widget_secondary, "import")
            repository.startShift(null, now - 3_600_000)
            repository.recordDistance(16093.44, false, now)
            TrackingService.running.value = true
            view = update()
            assertEquals("Pause", text(view, R.id.widget_primary))
            assertTrue(text(view, R.id.widget_miles), text(view, R.id.widget_miles).startsWith("10.00 mi"))
            click(view, R.id.widget_primary, "pause")
            click(view, R.id.widget_secondary, "end")
            // Render the actual RemoteViews layout, including applied values and controls.
            val density = app.resources.displayMetrics.density
            val width = (300 * density).toInt(); val height = (250 * density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File("build/screenshots/widget.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            repository.pauseShift(now)
            view = update()
            assertEquals("Resume", text(view, R.id.widget_primary))
            click(view, R.id.widget_primary, "resume")
        } finally { TrackingService.running.value = false; repository.database.close() }
    }
}
