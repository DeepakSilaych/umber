package com.deepak.umber.remote

/**
 * Push/pull of the parsed ledger with the Umber server. See `docs/SYNC.md` for the wire contract.
 *
 * Only the `cloud` flavour has an implementation; the `privacy` flavour's factory returns null and
 * this type is unreachable there at runtime.
 *
 * **Raw SMS is never part of this.** Only the parsed row travels — amount, merchant, category,
 * account tail, reference. Account numbers, balances and the verbatim bank message stay in
 * `raw_message` on the device. The server therefore cannot re-parse, which is the deliberate cost
 * of that boundary: extraction fixes ship in the app, not centrally.
 */
interface RemoteSync {

    /** False until the user has opted in and configured an endpoint. */
    val isEnabled: Boolean

    /**
     * One round trip: push rows changed since the last success, apply whatever comes back.
     *
     * Never throws for an unreachable server — sync is not load-bearing, and every failure has to
     * degrade to "the phone behaves exactly like the privacy build".
     */
    suspend fun sync(): SyncOutcome
}

sealed interface SyncOutcome {
    data class Success(val pushed: Int, val pulled: Int, val cursor: Long) : SyncOutcome

    /** Transport failed. Worth retrying on the next run. */
    data class Unreachable(val detail: String) : SyncOutcome

    /**
     * Credentials rejected. Distinct from [Unreachable] because retrying is pointless — sync
     * disables itself and surfaces this in Settings instead of looping forever.
     */
    data object Unauthorised : SyncOutcome

    data object Disabled : SyncOutcome
}
