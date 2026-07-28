package com.deepak.umber.remote

import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction

/**
 * What gets sent off the device for classification.
 *
 * **This carries the full message body**, which means account numbers, balances and everything else
 * the bank wrote. That is a deliberate product decision — accuracy over minimisation — and it is
 * why the cloud flavour states plainly in its own UI what leaves the phone. An implementation that
 * uploaded this quietly would be indefensible.
 *
 * The `privacy` flavour never constructs one of these: its factory returns null and the type is
 * unreachable at runtime.
 */
data class RemoteClassifyRequest(
    val merchantNorm: String?,
    val vpaHandle: String?,
    val channel: Channel,
    val direction: Direction,
    val amountPaise: Long,
    val occurredAt: Long,
    /** The original SMS, verbatim. */
    val rawBody: String,
)

data class RemoteVerdict(
    val category: String,
    val confidence: Float,
)

/**
 * Classification performed off-device.
 *
 * Deliberately narrow: one suspending call that may fail. Callers treat a null result as "no
 * opinion" and fall back to the local three-layer classifier, so the network is never load-bearing
 * — a flat battery, a dead server or a rate limit degrades accuracy rather than breaking ingest.
 *
 * Results are expected to be cached per merchant by the caller. A merchant's category does not
 * change, so a given name should cost one call in the app's entire lifetime.
 */
interface RemoteClassifier {

    /** False when the user hasn't opted in, or no endpoint is configured. */
    val isEnabled: Boolean

    suspend fun classify(request: RemoteClassifyRequest): RemoteVerdict?
}

// RemoteClassifierFactory is defined once per flavour, under this same package name. The rest of
// the app depends only on the interface above and never on which build it is running in.
