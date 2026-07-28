package com.deepak.umber

import android.app.Application
import android.content.Context
import com.deepak.umber.data.db.AppDatabase
import com.deepak.umber.data.repo.UmberRepository
import com.deepak.umber.ingest.IngestPipeline
import com.deepak.umber.ingest.SmsBackfill
import com.deepak.umber.location.LocationCache
import com.deepak.umber.ml.Classifier
import com.deepak.umber.remote.RemoteSync
import com.deepak.umber.remote.RemoteSyncFactory
import com.deepak.umber.widget.WidgetUpdater
import com.deepak.umber.work.RollupWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container.
 *
 * The graph is a dozen objects deep with no scoping beyond "application", so a DI framework would
 * add build-time cost and indirection for nothing. It's also reachable from a `BroadcastReceiver`
 * and a Glance widget, both of which only have a `Context` — a plain field on `Application` is the
 * simplest thing that works there.
 */
class AppContainer(context: Context) {

    /** SupervisorJob: one failed ingest must not cancel the scope the receiver depends on. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase = AppDatabase.get(context)

    val locationCache = LocationCache(context, database.lastLocation(), appScope)

    val classifier = Classifier(
        memoryDao = database.merchantMemory(),
        modelDao = database.modelState(),
        txnDao = database.txns(),
    )

    /**
     * Null in the privacy flavour, which has no INTERNET permission and therefore no way to reach
     * a server. Everything downstream depends on the interface, not on which build it is.
     */
    val remoteSync: RemoteSync? = RemoteSyncFactory.create(context)

    val ingest = IngestPipeline(database, classifier, locationCache)

    val repository = UmberRepository(database, classifier, ingest)

    val backfill = SmsBackfill(context, ingest)

    val widgetUpdater = WidgetUpdater(context)
}

class UmberApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        RollupWorker.schedule(this)
    }
}
