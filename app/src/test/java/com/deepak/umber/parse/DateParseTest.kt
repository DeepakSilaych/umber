package com.deepak.umber.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DateParseTest {

    @Test
    fun `accepts the formats indian banks actually emit`() {
        val expected = LocalDate.of(2026, 5, 8)
        listOf("08-05-2026", "08/05/2026", "8-5-26", "08-May-2026", "08-may-26", "2026-05-08", "08.05.2026")
            .forEach { assertEquals("failed on $it", expected, DateParse.date(it)) }
    }

    @Test
    fun `ignores a trailing time component`() {
        assertEquals(LocalDate.of(2026, 5, 8), DateParse.date("08/05/2026 14:32:11"))
    }

    @Test
    fun `rejects junk`() {
        assertNull(DateParse.date("not a date"))
        assertNull(DateParse.date(""))
        assertNull(DateParse.date(null))
    }

    @Test
    fun `parses rupee strings to paise`() {
        assertEquals(10_000L, DateParse.paise("100.00"))
        assertEquals(1_23_456_78L, DateParse.paise("1,23,456.78"))
        assertEquals(50_000L, DateParse.paise("Rs. 500"))
        assertEquals(89_900L, DateParse.paise("INR 899.00"))
        assertEquals(10_000L, DateParse.paise("₹100"))
    }

    /** Statements encode direction in the amount itself — as a sign, parentheses or a Dr marker. */
    @Test
    fun `negatives are preserved in every notation banks use`() {
        assertEquals(-10_000L, DateParse.paise("-100.00"))
        assertEquals(-10_000L, DateParse.paise("(100.00)"))
        assertEquals(-10_000L, DateParse.paise("100.00 Dr"))
        assertEquals(10_000L, DateParse.paise("100.00 Cr"))
    }

    @Test
    fun `blank and placeholder cells are not zero amounts`() {
        assertNull(DateParse.paise(""))
        assertNull(DateParse.paise("  "))
        assertNull(DateParse.paise("-"))
        assertNull(DateParse.paise(null))
    }
}
