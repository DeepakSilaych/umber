package com.deepak.umber.remote

import android.content.Context

/**
 * The cloud flavour's sync client.
 *
 * Currently a stub reporting itself disabled, so this build behaves identically to the privacy one
 * until a server exists. Deliberate: the flavour split and the wire contract land and are reviewable
 * before any transport, credentials or consent flow is written.
 */
object RemoteSyncFactory {
    fun create(context: Context): RemoteSync? = NotYetConfigured
}

private object NotYetConfigured : RemoteSync {
    override val isEnabled: Boolean = false
    override suspend fun sync(): SyncOutcome = SyncOutcome.Disabled
}
