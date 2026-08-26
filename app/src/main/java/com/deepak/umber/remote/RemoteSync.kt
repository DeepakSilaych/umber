package com.deepak.umber.remote

import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import kotlinx.coroutines.flow.StateFlow

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

    /** Live status for Settings — whether sync is on, and what to tell the user about it. */
    val status: StateFlow<SyncStatus>

    /**
     * Debug-only override of the sync endpoint (e.g. `http://10.0.2.2:8000` from an emulator,
     * pointed at a locally-run server). Null uses the flavour's built-in default. Settings only
     * exposes this control in a debug build.
     */
    var baseUrlOverride: String?

    /**
     * One-time device registration (`POST /v1/devices/register`), using the setup key the user
     * pasted into Settings. Stores the returned device id and bearer token and flips [status] to
     * enabled on success — this is the only way sync turns on.
     */
    suspend fun enable(setupKey: String): RegisterOutcome

    /**
     * Turns sync off and cancels the periodic worker. Does not forget the stored device credentials,
     * so re-enabling doesn't need the setup key again unless the server has since forgotten this
     * device (which surfaces as [SyncOutcome.Unauthorised] and disables sync again).
     */
    fun disable()

    /**
     * One round trip: push rows changed since the last success, apply whatever comes back.
     *
     * Never throws for an unreachable server — sync is not load-bearing, and every failure has to
     * degrade to "the phone behaves exactly like the privacy build".
     */
    suspend fun sync(): SyncOutcome
}

/** What Settings shows about the current sync state. */
data class SyncStatus(
    val enabled: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    /** Human-readable, e.g. "authentication failed, check your setup key in Settings". Null when fine. */
    val lastError: String? = null,
    /** True once a device id/token exist locally, whether or not sync is currently enabled. */
    val hasCredentials: Boolean = false,
)

sealed interface RegisterOutcome {
    data object Success : RegisterOutcome
    data class Failed(val detail: String) : RegisterOutcome
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

/**
 * One row of the parsed ledger as it crosses the wire — see `docs/SYNC.md`. Field names here are
 * camelCase Kotlin; the `cloud` flavour's network layer maps to/from the snake_case JSON shape.
 */
data class RemoteTxn(
    val clientId: String,
    val occurredAt: Long,
    val amountPaise: Long,
    val direction: Direction,
    val channel: Channel,
    val merchantNorm: String?,
    val merchantRaw: String?,
    val accountTail: String?,
    val refNo: String?,
    val balancePaise: Long?,
    val category: String,
    val categorySource: CategorySource,
    val updatedAt: Long,
)
