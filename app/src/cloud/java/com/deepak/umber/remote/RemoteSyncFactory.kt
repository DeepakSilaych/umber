package com.deepak.umber.remote

import android.content.Context
import com.deepak.umber.data.repo.UmberRepository

/**
 * The cloud flavour's sync client.
 *
 * Talks to the Umber server described in `docs/SYNC.md` once the user opts in. Disabled by
 * default — [RemoteSync.status] starts with `enabled = false`, and [HttpRemoteSync] never opens a
 * connection until Settings calls [RemoteSync.enable], per README's "will say so in its own UI
 * before doing anything."
 */
object RemoteSyncFactory {
    fun create(context: Context, repository: UmberRepository): RemoteSync =
        HttpRemoteSync(context.applicationContext, repository)
}
