package com.deepak.umber.remote

import android.content.Context

/**
 * The privacy flavour has no sync, and cannot have one.
 *
 * This build ships without `INTERNET`, so any implementation would fail at the OS boundary whatever
 * the code attempted. Returning null keeps that a fact of the type system rather than a convention:
 * nothing downstream can accidentally acquire a network path.
 */
object RemoteSyncFactory {
    fun create(context: Context): RemoteSync? = null
}
