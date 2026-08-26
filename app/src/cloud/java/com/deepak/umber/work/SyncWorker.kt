package com.deepak.umber.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.umber.UmberApp
import java.util.concurrent.TimeUnit

/**
 * Periodic push/pull with the sync server. Cloud-only — mirrors [RollupWorker]'s shape, the app's
 * only other periodic job.
 *
 * Never runs on the ingest path (`docs/SYNC.md`'s "Failure behaviour": "Sync runs on WorkManager
 * with a network constraint, not on the ingest path. A dead server must never delay a transaction
 * appearing on the widget."). `RemoteSync.sync()` absorbs every failure internally, so this worker
 * always reports success to WorkManager — an unreachable server is an expected outcome here, not a
 * work failure to retry-with-backoff.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? UmberApp ?: return Result.success()
        app.container.remoteSync?.sync()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "cloud_sync"

        /**
         * 45 minutes: in RollupWorker's spirit — frequent enough that a dashboard edit or a
         * server-side classification shows up on the phone same-day, coarse enough not to fight the
         * OS over battery. Sync is explicitly not latency-sensitive (SYNC.md), so there's no reason
         * to run it anywhere near RollupWorker's 20-minute widget-freshness interval.
         */
        private const val INTERVAL_MINUTES = 45L

        /** Enqueues the periodic job. Safe to call repeatedly — KEEP is a no-op if it already exists. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Called when Settings turns sync off, or when the server rejects the device's token. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
