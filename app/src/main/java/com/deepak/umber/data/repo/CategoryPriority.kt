package com.deepak.umber.data.repo

import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.remote.RemoteTxn

/**
 * Implements `docs/SYNC.md`'s conflict table for merging a transaction pulled from the sync server
 * into the local row.
 *
 * | Origin                             | `category_source` | Beats                          |
 * |-------------------------------------|--------------------|----------------------------------|
 * | User tapped a category in the app   | `USER`             | everything                      |
 * | Merchant-memory match                | `MEMORY`           | everything, same tier as `USER` |
 * | User edited in the dashboard        | `DASHBOARD`        | everything except `USER`/`MEMORY` |
 * | Server LLM classification           | `REMOTE`           | `MODEL`, `SEED`, `NONE`         |
 * | On-device model / lexicon           | `MODEL`,`SEED`,`NONE` | nothing                       |
 *
 * The doc's table only lists `USER` explicitly; `MEMORY` is folded in at the same tier because a
 * memory entry is only ever created by a prior user confirmation (see
 * [UmberRepository.confirmCategory]) — it's the user's decision wearing a different
 * `category_source`, not an independent source of truth. This is the documented amendment described
 * alongside the sync work, not a guess.
 *
 * This exists client-side as a defensive guard, not the primary enforcement mechanism — the server
 * is expected to apply the same table when it merges pushes and runs its classification job (see
 * SYNC.md's "Server-side classification" step 4). But belt-and-suspenders here is what makes the
 * doc's promise literally true on the phone too: "the phone never overwrites a USER row with a
 * REMOTE one," even if a stale or out-of-order response ever made that a real possibility.
 */
object CategoryPriority {

    private fun tier(source: CategorySource): Int = when (source) {
        CategorySource.USER, CategorySource.MEMORY -> 3
        CategorySource.DASHBOARD -> 2
        CategorySource.REMOTE -> 1
        CategorySource.MODEL, CategorySource.SEED, CategorySource.NONE -> 0
    }

    /**
     * True when [local]'s category should be kept as-is rather than replaced by [remote].
     *
     * A tier win is unconditional regardless of timestamps — that's what stops a stale or
     * out-of-order `REMOTE` update from ever undoing a decision the user made by hand. Ties within a
     * tier break by `updatedAt`, last write wins, per SYNC.md.
     */
    fun localWins(local: TxnEntity, remote: RemoteTxn): Boolean {
        val localTier = tier(local.categorySource)
        val remoteTier = tier(remote.categorySource)
        return if (localTier != remoteTier) localTier > remoteTier else local.updatedAt >= remote.updatedAt
    }
}
