package com.deepak.umber.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MessageFingerprintTest {

    private val base = 1_752_300_000_000L // arbitrary fixed instant

    /**
     * The property that makes backfill safe to re-run: the receiver and the SMS provider disagree
     * on the exact timestamp of the same message, so the fingerprint must tolerate that skew.
     */
    @Test
    fun `timestamp skew within a day does not change the fingerprint`() {
        val a = MessageFingerprint.of("VM-HDFCBK", "Rs.100 debited", base)
        val b = MessageFingerprint.of("VM-HDFCBK", "Rs.100 debited", base + 90_000L)
        assertEquals(a, b)
    }

    @Test
    fun `sender casing and surrounding whitespace are ignored`() {
        val a = MessageFingerprint.of("VM-HDFCBK", "Rs.100 debited", base)
        val b = MessageFingerprint.of("  vm-hdfcbk ", "  Rs.100 debited  ", base)
        assertEquals(a, b)
    }

    @Test
    fun `different bodies produce different fingerprints`() {
        val a = MessageFingerprint.of("VM-HDFCBK", "Rs.100 debited", base)
        val b = MessageFingerprint.of("VM-HDFCBK", "Rs.200 debited", base)
        assertNotEquals(a, b)
    }

    @Test
    fun `same text on a different day is a different message`() {
        val a = MessageFingerprint.of("VM-HDFCBK", "Rs.100 debited", base)
        val b = MessageFingerprint.of("VM-HDFCBK", "Rs.100 debited", base + 3 * 24 * 60 * 60 * 1000L)
        assertNotEquals(a, b)
    }

    @Test
    fun `fingerprint is a hex sha256`() {
        val fp = MessageFingerprint.of("VM-HDFCBK", "Rs.100 debited", base)
        assertEquals(64, fp.length)
        assertEquals(true, fp.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
