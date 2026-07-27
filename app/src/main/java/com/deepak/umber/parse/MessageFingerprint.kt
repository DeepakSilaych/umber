package com.deepak.umber.parse

import java.security.MessageDigest

/**
 * Stable identity for a raw message, used as the unique key that makes ingestion idempotent.
 *
 * The timestamp is bucketed to the day rather than used exactly. The live broadcast receiver and
 * the SMS content provider report slightly different times for the same message, so an exact-time
 * fingerprint would let a backfill re-insert everything the receiver had already captured.
 *
 * The residual risk — two byte-identical messages from the same sender on the same day being
 * collapsed into one — is negligible in practice: bank templates embed a reference number or a
 * balance, so genuinely repeated text means a genuinely duplicate notification.
 */
object MessageFingerprint {

    private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

    fun of(sender: String, body: String, receivedAt: Long): String {
        val material = "${sender.trim().lowercase()}|${body.trim()}|${receivedAt / MS_PER_DAY}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
        return buildString(digest.size * 2) {
            for (byte in digest) append("%02x".format(byte))
        }
    }
}
