package com.deepak.umber.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for `docs/SYNC.md`. snake_case on the wire via `@SerialName`; everything else in this
 * flavour uses the camelCase Kotlin names ([TxnEntity], [RemoteTxn]).
 */

@Serializable
internal data class RegisterDeviceRequest(
    @SerialName("setup_key") val setupKey: String,
    val label: String? = null,
)

@Serializable
internal data class RegisterDeviceResponse(
    @SerialName("device_id") val deviceId: String,
    val token: String,
)

@Serializable
internal data class SyncRequest(
    @SerialName("device_id") val deviceId: String,
    val since: Long,
    val transactions: List<WireTxn>,
)

@Serializable
internal data class SyncResponse(
    val cursor: Long,
    val applied: Int = 0,
    val rejected: List<RejectedTxn> = emptyList(),
    val transactions: List<WireTxn> = emptyList(),
)

@Serializable
internal data class RejectedTxn(
    @SerialName("client_id") val clientId: String,
    val reason: String? = null,
)

@Serializable
internal data class WireTxn(
    @SerialName("client_id") val clientId: String,
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("amount_paise") val amountPaise: Long,
    val direction: String,
    val channel: String? = null,
    @SerialName("merchant_norm") val merchantNorm: String? = null,
    @SerialName("merchant_raw") val merchantRaw: String? = null,
    @SerialName("account_tail") val accountTail: String? = null,
    val reference: String? = null,
    @SerialName("balance_paise") val balancePaise: Long? = null,
    val category: String,
    @SerialName("category_source") val categorySource: String,
    @SerialName("updated_at") val updatedAt: Long,
)
