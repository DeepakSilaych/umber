package com.deepak.umber.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderFilterTest {

    @Test
    fun `dlt headers resolve to the bank entity code`() {
        assertEquals(SenderFilter.SenderClass.BANK, SenderFilter.classify("VM-HDFCBK"))
        assertEquals(SenderFilter.SenderClass.BANK, SenderFilter.classify("AD-SBIUPI"))
        assertEquals(SenderFilter.SenderClass.BANK, SenderFilter.classify("JD-ICICIB"))
        assertEquals(SenderFilter.SenderClass.BANK, SenderFilter.classify("hdfcbk"))
    }

    /** DLT headers can carry a trailing category suffix, so the code isn't always segment two. */
    @Test
    fun `trailing category suffix does not hide the bank code`() {
        assertEquals(SenderFilter.SenderClass.BANK, SenderFilter.classify("AD-HDFCBK-S"))
    }

    @Test
    fun `payment apps are recognised separately from banks`() {
        assertEquals(SenderFilter.SenderClass.PSP, SenderFilter.classify("VM-PHONPE"))
    }

    /**
     * The one hard reject. A friend's "sent you 500" parses perfectly and would otherwise land in
     * the ledger as a real transaction.
     */
    @Test
    fun `personal mobile numbers are blocked`() {
        assertTrue(SenderFilter.isBlocked("919876543210"))
        assertTrue(SenderFilter.isBlocked("+919876543210"))
        assertTrue(SenderFilter.isBlocked("9876543210"))
        assertEquals(SenderFilter.SenderClass.PERSONAL, SenderFilter.classify("09876543210"))
    }

    @Test
    fun `alphanumeric senders are never treated as personal`() {
        assertFalse(SenderFilter.isBlocked("VM-HDFCBK"))
        assertFalse(SenderFilter.isBlocked("AX-XYZABC"))
    }

    @Test
    fun `unrecognised headers fall through as unknown rather than being blocked`() {
        assertEquals(SenderFilter.SenderClass.UNKNOWN, SenderFilter.classify("AX-XYZABC"))
        assertFalse(SenderFilter.isBlocked("AX-XYZABC"))
    }
}
