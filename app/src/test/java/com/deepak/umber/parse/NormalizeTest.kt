package com.deepak.umber.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NormalizeTest {

    /**
     * The whole point of normalisation: merchant memory is an exact-string lookup, so every variant
     * that fails to collapse here becomes a separate row the user has to categorise again.
     */
    @Test
    fun `spelling variants of one merchant collapse to the same key`() {
        val variants = listOf(
            "SWIGGY",
            "Swiggy",
            "SWIGGY LIMITED",
            "Swiggy Pvt Ltd",
            "swiggy@ybl",
            "swiggy  ",
        )

        val keys = variants.map { Normalize.merchant(it) }.toSet()
        assertEquals(setOf("swiggy"), keys)
    }

    @Test
    fun `corporate suffixes are stripped repeatedly`() {
        assertEquals("amazon pay", Normalize.merchant("Amazon Pay India Pvt Ltd"))
        assertEquals("reliance retail", Normalize.merchant("RELIANCE RETAIL LIMITED"))
    }

    /**
     * The PSP handle identifies the payment app, not the merchant. Folding it into the key would
     * split one merchant across every app the user happens to pay through.
     */
    @Test
    fun `vpa handle is excluded from the merchant key but kept separately`() {
        assertEquals("bluetokai", Normalize.merchant("bluetokai@okhdfcbank"))
        assertEquals("bluetokai", Normalize.merchant("bluetokai@ybl"))

        assertEquals("okhdfcbank", Normalize.vpaHandle("bluetokai@okhdfcbank"))
        assertEquals("ybl", Normalize.vpaHandle("bluetokai@ybl"))
        assertNull(Normalize.vpaHandle("SWIGGY"))
    }

    @Test
    fun `trailing order numbers are dropped`() {
        assertEquals("zepto order", Normalize.merchant("ZEPTO*Order 8812"))
    }

    /** A phone-number VPA is not a generalisable name, but it is a stable payee. Keep it. */
    @Test
    fun `numeric vpa is preserved`() {
        assertEquals("9876543210", Normalize.merchant("9876543210@ybl"))
    }

    /**
     * Banks are inconsistent about honorifics, and both merchant memory and reimbursement netting
     * key on this exact string — so "MR DEEPAK KUMAR" and "DEEPAK KUMAR" must not become two
     * different counterparties.
     */
    @Test
    fun `honorifics are stripped from personal payees`() {
        assertEquals("deepak kumar", Normalize.merchant("MR DEEPAK KUMAR"))
        assertEquals("deepak kumar", Normalize.merchant("Deepak Kumar"))
        assertEquals("anita rao", Normalize.merchant("SMT ANITA RAO"))
    }

    /** An honorific with nothing after it is all the payee we have; keep it. */
    @Test
    fun `a bare honorific is not stripped away to nothing`() {
        assertEquals("mr", Normalize.merchant("MR"))
    }

    @Test
    fun `blank input yields an empty key`() {
        assertEquals("", Normalize.merchant(null))
        assertEquals("", Normalize.merchant("   "))
    }

    @Test
    fun `distinct merchants stay distinct`() {
        assertNotEquals(Normalize.merchant("ZOMATO"), Normalize.merchant("SWIGGY"))
    }

    @Test
    fun `display casing is for humans only`() {
        assertEquals("Blue Tokai Coffee", Normalize.display("BLUE TOKAI COFFEE"))
    }
}
