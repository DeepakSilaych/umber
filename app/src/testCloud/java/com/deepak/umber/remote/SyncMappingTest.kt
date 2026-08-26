package com.deepak.umber.remote

import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The JSON <-> Kotlin mapping for `POST /v1/sync`, the part of the cloud flavour that's testable
 * without a running server: field names on the wire, round-tripping through kotlinx.serialization,
 * and the lenient-decode fallback for values this build doesn't recognise.
 */
class SyncMappingTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private fun sampleTxn() = TxnEntity(
        rawMessageId = 1L,
        clientId = "8f14e45f-0000-0000-0000-000000000000",
        amountPaise = 45000L,
        direction = Direction.DEBIT,
        channel = Channel.UPI,
        accountTail = "1234",
        merchantRaw = "SWIGGY",
        merchantNorm = "swiggy",
        vpaHandle = null,
        refNo = "512345678901",
        balancePaise = 950000L,
        occurredAt = 1_753_612_345_678L,
        category = "Food & Dining",
        categorySource = CategorySource.USER,
        confidence = 1f,
        needsReview = false,
        parserVersion = 1,
        createdAt = 1_753_612_345_678L,
        updatedAt = 1_753_612_400_000L,
    )

    // ---------------------------------------------------------------------- push

    @Test
    fun `TxnEntity maps to the wire field names in docs SYNC md`() {
        val wire = sampleTxn().toWire()

        assertEquals("8f14e45f-0000-0000-0000-000000000000", wire.clientId)
        assertEquals(1_753_612_345_678L, wire.occurredAt)
        assertEquals(45000L, wire.amountPaise)
        assertEquals("DEBIT", wire.direction)
        assertEquals("UPI", wire.channel)
        assertEquals("swiggy", wire.merchantNorm)
        assertEquals("SWIGGY", wire.merchantRaw)
        assertEquals("1234", wire.accountTail)
        // `refNo` on the entity, `reference` on the wire — the one field whose Kotlin and JSON
        // names genuinely differ, not just casing.
        assertEquals("512345678901", wire.reference)
        assertEquals(950000L, wire.balancePaise)
        assertEquals("Food & Dining", wire.category)
        assertEquals("USER", wire.categorySource)
        assertEquals(1_753_612_400_000L, wire.updatedAt)
    }

    @Test
    fun `push JSON uses the exact snake_case keys SYNC md documents`() {
        val body = SyncRequest(deviceId = "dev-1", since = 0L, transactions = listOf(sampleTxn().toWire()))
        val encoded = json.encodeToString(SyncRequest.serializer(), body)

        for (key in listOf(
            "\"device_id\"", "\"since\"", "\"transactions\"", "\"client_id\"", "\"occurred_at\"",
            "\"amount_paise\"", "\"merchant_norm\"", "\"merchant_raw\"", "\"account_tail\"",
            "\"reference\"", "\"balance_paise\"", "\"category_source\"", "\"updated_at\"",
        )) {
            assert(encoded.contains(key)) { "expected $key in $encoded" }
        }
    }

    // ---------------------------------------------------------------------- pull

    @Test
    fun `a pull response decodes and maps back to RemoteTxn`() {
        val responseJson = """
            {
              "cursor": 1753612500000,
              "applied": 1,
              "rejected": [],
              "transactions": [{
                "client_id": "8f14e45f-0000-0000-0000-000000000000",
                "occurred_at": 1753612345678,
                "amount_paise": 45000,
                "direction": "DEBIT",
                "channel": "UPI",
                "merchant_norm": "swiggy",
                "merchant_raw": "SWIGGY",
                "account_tail": "1234",
                "reference": "512345678901",
                "balance_paise": 950000,
                "category": "Food & Dining",
                "category_source": "DASHBOARD",
                "updated_at": 1753612400000
              }]
            }
        """.trimIndent()

        val response = json.decodeFromString(SyncResponse.serializer(), responseJson)
        assertEquals(1_753_612_500_000L, response.cursor)
        assertEquals(1, response.transactions.size)

        val remote = response.transactions.single().toRemoteTxn()
        assertEquals(CategorySource.DASHBOARD, remote.categorySource)
        assertEquals(Direction.DEBIT, remote.direction)
        assertEquals(Channel.UPI, remote.channel)
        assertEquals("512345678901", remote.refNo)
    }

    @Test
    fun `an unrecognised category_source falls back to NONE instead of crashing sync`() {
        val wire = WireTxn(
            clientId = "x",
            occurredAt = 0L,
            amountPaise = 100L,
            direction = "DEBIT",
            channel = "UPI",
            category = "Other",
            categorySource = "SOME_FUTURE_VALUE",
            updatedAt = 0L,
        )

        assertEquals(CategorySource.NONE, wire.toRemoteTxn().categorySource)
    }

    @Test
    fun `an unrecognised channel falls back to UNKNOWN, direction falls back to DEBIT`() {
        val wire = WireTxn(
            clientId = "x",
            occurredAt = 0L,
            amountPaise = 100L,
            direction = "SOME_FUTURE_DIRECTION",
            channel = "SOME_FUTURE_CHANNEL",
            category = "Other",
            categorySource = "NONE",
            updatedAt = 0L,
        )

        val remote = wire.toRemoteTxn()
        assertEquals(Direction.DEBIT, remote.direction)
        assertEquals(Channel.UNKNOWN, remote.channel)
    }

    @Test
    fun `a missing rejected list decodes to empty, not a crash`() {
        val minimal = """{"cursor": 1, "transactions": []}"""
        val response = json.decodeFromString(SyncResponse.serializer(), minimal)
        assertEquals(0, response.rejected.size)
        assertEquals(0, response.applied)
    }

    @Test
    fun `registration response round-trips device_id and token`() {
        val responseJson = """{"device_id": "abc-123", "token": "secret-token"}"""
        val response = json.decodeFromString(RegisterDeviceResponse.serializer(), responseJson)
        assertEquals("abc-123", response.deviceId)
        assertEquals("secret-token", response.token)
    }

    @Test
    fun `registration request omits label when null`() {
        val body = RegisterDeviceRequest(setupKey = "shh")
        val encoded = json.encodeToString(RegisterDeviceRequest.serializer(), body)
        assertEquals("""{"setup_key":"shh"}""", encoded)
        assertNull(body.label)
    }
}
