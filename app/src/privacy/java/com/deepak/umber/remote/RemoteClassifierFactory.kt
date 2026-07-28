package com.deepak.umber.remote

import android.content.Context

/**
 * The privacy flavour has no remote classifier, and cannot have one.
 *
 * This build ships without the `INTERNET` permission, so any implementation here would fail at the
 * OS boundary regardless of what the code tried to do. Returning null keeps that fact honest in the
 * type system: nothing downstream can accidentally acquire a network path.
 */
object RemoteClassifierFactory {
    fun create(context: Context): RemoteClassifier? = null
}
