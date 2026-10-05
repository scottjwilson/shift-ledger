package com.scott.shiftledger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LedgerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.scott.shiftledger.REFRESH_WIDGET" || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) refresh(context)
    }
    private fun refresh(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { updateAll(context) } }
            catch (error: Exception) { Log.w("LedgerWidget", "Widget refresh did not finish", error) }
            finally { pending.finish() }
        }
    }
    companion object {
        const val ACTION = "com.scott.shiftledger.WIDGET"
        const val EXTRA_ACTION = "widget_action"
        suspend fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LedgerWidget::class.java))
            if (ids.isEmpty()) return
            val state = (context.applicationContext as LedgerApplication).repository.snapshot()
            val now = System.currentTimeMillis()
            val today = LocalDate.now()
            val values = totals(state, today, today, now)
            val active = state.activeShift
            val health = gpsHealth(active, TrackingService.running.value, TrackingService.environment.value, now)
            val views = RemoteViews(context.packageName, R.layout.ledger_widget)
            views.setTextViewText(R.id.widget_earnings, "${money(values.earned)} earned today")
            views.setTextViewText(R.id.widget_miles, "${decimal(values.miles)} mi · ${durationText((values.hours * 3_600_000).toLong())}")
            views.setTextViewText(R.id.widget_status, health.title)
            views.setTextColor(R.id.widget_status, android.graphics.Color.parseColor(if (health.needsAttention) "#F5DB9B" else "#8AD6B1"))
            views.setTextViewText(R.id.widget_updated, "Checked ${Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} · Tap ↻ to refresh")
            val action = when { active == null -> "start"; active.runningSince == null || !TrackingService.running.value -> "resume"; else -> "pause" }
            views.setTextViewText(R.id.widget_primary, action.replaceFirstChar { it.uppercase() })
            views.setTextViewText(R.id.widget_secondary, if (active == null) "Import" else "End shift")
            fun launch(action: String, code: Int): PendingIntent = PendingIntent.getActivity(context, code,
                Intent(context, MainActivity::class.java).setAction(ACTION).putExtra(EXTRA_ACTION, action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, launch("home", 10))
            views.setOnClickPendingIntent(R.id.widget_primary, launch(action, 11))
            views.setOnClickPendingIntent(R.id.widget_secondary, launch(if (active == null) "import" else "end", 12))
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, 13,
                Intent(context, LedgerWidget::class.java).setAction("com.scott.shiftledger.REFRESH_WIDGET"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(ids, views)
        }
        fun pin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, LedgerWidget::class.java), null, null)
        }
    }
}
