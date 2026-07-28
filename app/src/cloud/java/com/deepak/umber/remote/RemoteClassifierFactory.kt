package com.deepak.umber.remote

import android.content.Context

/**
 * The cloud flavour's remote classifier.
 *
 * Currently a placeholder that reports itself disabled, so this build behaves exactly like the
 * privacy one until an endpoint is actually wired up. That is deliberate: the flavour split lands
 * first and is verifiable on its own, before any transport, credentials or consent flow exist.
 *
 * When it is implemented it must not classify anything until the user has explicitly opted in and
 * been told, in the app, that the full text of their bank messages leaves the device.
 */
object RemoteClassifierFactory {
    fun create(context: Context): RemoteClassifier? = NotYetConfigured
}

private object NotYetConfigured : RemoteClassifier {
    override val isEnabled: Boolean = false
    override suspend fun classify(request: RemoteClassifyRequest): RemoteVerdict? = null
}
