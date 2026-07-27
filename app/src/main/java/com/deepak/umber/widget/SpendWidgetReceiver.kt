package com.deepak.umber.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import android.util.Log

class SpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = SpendWidget()
}

/**
 * Single entry point for "the widget should redraw".
 *
 * Called from two places: ingest (a new transaction landed) and [com.deepak.umber.work.RollupWorker]
 * (the rolling window boundaries have moved). Failures are swallowed — a widget that didn't refresh
 * is a cosmetic problem, and letting it propagate would abort the ingest that triggered it.
 */
class WidgetUpdater(private val context: Context) {

    suspend fun refresh() {
        try {
            SpendWidget().updateAll(context)
        } catch (e: Exception) {
            Log.w(TAG, "widget refresh failed", e)
        }
    }

    private companion object {
        const val TAG = "WidgetUpdater"
    }
}
