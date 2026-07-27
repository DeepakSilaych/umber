package com.deepak.umber.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.umber.UmberApp
import java.util.concurrent.TimeUnit

/**
 * Keeps the widget honest as time passes.
 *
 * Rolling windows have no fixed boundary: "last 24 hours" shrinks continuously as old transactions
 * age out. Without a periodic nudge the widget would keep showing yesterday's total until the next
 * transaction happened to arrive — which is exactly when the number matters least.
 */
class RollupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? UmberApp ?: return Result.success()
        app.container.widgetUpdater.refresh()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "rollup"

        /**
         * 20 minutes is a compromise: fine enough that the 24h figure never looks obviously stale,
         * coarse enough that the OS won't start deferring it for battery. WorkManager's minimum
         * period is 15 minutes anyway.
         */
        private const val INTERVAL_MINUTES = 20L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RollupWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP, not UPDATE: re-enqueueing on every launch would reset the period and
                // effectively never let it run on a device that's opened often.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
