package com.deepak.umber.remote

import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction

/** `TxnEntity` -> the wire shape, for the push side of `POST /v1/sync`. */
internal fun TxnEntity.toWire(): WireTxn = WireTxn(
    clientId = clientId,
    occurredAt = occurredAt,
    amountPaise = amountPaise,
    direction = direction.name,
    channel = channel.name,
    merchantNorm = merchantNorm,
    merchantRaw = merchantRaw,
    accountTail = accountTail,
    reference = refNo,
    balancePaise = balancePaise,
    category = category,
    categorySource = categorySource.name,
    updatedAt = updatedAt,
)

/**
 * The wire shape -> [RemoteTxn], for the pull side.
 *
 * Enum names are decoded leniently — an unrecognised `direction`/`channel`/`category_source` (a
 * value a future server version sends that this build doesn't know yet) falls back to a safe
 * default rather than failing the whole sync, mirroring `Converters.kt`'s Room fallback policy for
 * exactly the same reason: an older client has to keep working against a newer server.
 */
internal fun WireTxn.toRemoteTxn(): RemoteTxn = RemoteTxn(
    clientId = clientId,
    occurredAt = occurredAt,
    amountPaise = amountPaise,
    direction = runCatching { Direction.valueOf(direction) }.getOrDefault(Direction.DEBIT),
    channel = channel?.let { runCatching { Channel.valueOf(it) }.getOrNull() } ?: Channel.UNKNOWN,
    merchantNorm = merchantNorm,
    merchantRaw = merchantRaw,
    accountTail = accountTail,
    refNo = reference,
    balancePaise = balancePaise,
    category = category,
    categorySource = runCatching { CategorySource.valueOf(categorySource) }.getOrDefault(CategorySource.NONE),
    updatedAt = updatedAt,
)
