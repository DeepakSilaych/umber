package com.deepak.umber.io

import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class StatementParserTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun parse(text: String) = StatementParser.parse(text, zone)

    /** HDFC-style: separate withdrawal/deposit columns and a preamble above the header. */
    private val hdfcStyle = """
        Account Statement
        Account No: XXXXXXXX1234
        Period: 01/05/2026 to 31/05/2026

        Date,Narration,Chq/Ref No,Withdrawal Amt,Deposit Amt,Closing Balance
        02/05/2026,UPI/512345678901/PAYMENT TO ARJUN MEHTA,512345678901,500.00,,9500.00
        05/05/2026,UPI/512345678906/NETFLIX COM,512345678906,499.00,,9001.00
        07/05/2026,UPI/512345678903/FROM RAVI KUMAR,512345678903,,10000.00,19001.00
    """.trimIndent()

    @Test
    fun `finds the header row beneath account preamble`() {
        val result = parse(hdfcStyle)
        assertNull(result.problem)
        assertEquals(3, result.rows.size)
    }

    @Test
    fun `separate debit and credit columns set direction`() {
        val rows = parse(hdfcStyle).rows
        assertEquals(Direction.DEBIT, rows[0].record.direction)
        assertEquals(Direction.DEBIT, rows[1].record.direction)
        assertEquals(Direction.CREDIT, rows[2].record.direction)
    }

    @Test
    fun `amounts and balances land in paise`() {
        val first = parse(hdfcStyle).rows[0].record
        assertEquals(50_000L, first.amountPaise)
        assertEquals(9_50_000L, first.balancePaise)
    }

    /** The whole point of the reference column: matching statement rows against SMS. */
    @Test
    fun `reference is captured for cross source dedupe`() {
        assertEquals("512345678901", parse(hdfcStyle).rows[0].record.refNo)
    }

    @Test
    fun `merchant is pulled from a slash delimited narration`() {
        val rows = parse(hdfcStyle).rows
        assertEquals("arjun mehta", rows[0].record.merchantNorm)
        assertEquals("netflix com", rows[1].record.merchantNorm)
    }

    @Test
    fun `channel is inferred from the narration`() {
        assertEquals(Channel.UPI, parse(hdfcStyle).rows[0].record.channel)
    }

    /** A statement gives a date but no clock time — the flag that selects day-level dedupe. */
    @Test
    fun `statement rows are marked as lacking an exact time`() {
        assertTrue(parse(hdfcStyle).rows.all { !it.record.hasExactTime })
    }

    /** ICICI-style: one signed amount column, tab separated. */
    @Test
    fun `single signed amount column encodes direction`() {
        val text = "Txn Date\tTransaction Remarks\tAmount\tBalance\n" +
            "08-05-2026\tUPI/512345678902/VENDCO\t-99.00\t10261.13\n" +
            "08-05-2026\tSALARY CREDIT\t50000.00\t60261.13"

        val rows = parse(text).rows
        assertEquals(2, rows.size)
        assertEquals(Direction.DEBIT, rows[0].record.direction)
        assertEquals(9_900L, rows[0].record.amountPaise)
        assertEquals(Direction.CREDIT, rows[1].record.direction)
        assertEquals(50_00_000L, rows[1].record.amountPaise)
    }

    @Test
    fun `dr and cr suffixes encode direction`() {
        val text = "Date,Particulars,Amount\n" +
            "08-05-2026,POS PURCHASE ACME,250.00 Dr\n" +
            "08-05-2026,REFUND ACME,250.00 Cr"

        val rows = parse(text).rows
        assertEquals(Direction.DEBIT, rows[0].record.direction)
        assertEquals(Direction.CREDIT, rows[1].record.direction)
    }

    /**
     * Account numbers in a narration are the same shape as references. Taking one as a reference
     * would merge unrelated transactions, so extraction is anchored to a scheme marker.
     */
    @Test
    fun `unanchored long numbers are not treated as references`() {
        val text = "Date,Narration,Amount\n08-05-2026,TRANSFER TO 123456789012 ACME,-100.00"
        assertNull(parse(text).rows[0].record.refNo)
    }

    @Test
    fun `rows with no usable date or amount are skipped not failed`() {
        val text = "Date,Narration,Amount\n" +
            "08-05-2026,GOOD ROW,-100.00\n" +
            "TOTAL,,\n" +
            ",,\n" +
            "not-a-date,JUNK,-50.00"

        val result = parse(text)
        assertEquals(1, result.rows.size)
        assertEquals(2, result.skipped)
        assertNull(result.problem)
    }

    @Test
    fun `zero amount rows are skipped`() {
        val text = "Date,Narration,Amount\n08-05-2026,NIL ENTRY,0.00"
        assertEquals(0, parse(text).rows.size)
    }

    @Test
    fun `reports a clear problem when the file is not a statement`() {
        assertNotNull(parse("hello world\nthis is not a statement").problem)
        assertNotNull(parse("").problem)
    }

    /** "withdrawal amount" must not be beaten by a substring match on "amount". */
    @Test
    fun `specific column names win over generic ones`() {
        val text = "Date,Narration,Withdrawal Amount,Deposit Amount\n08-05-2026,ACME,500.00,"
        val rows = parse(text).rows
        assertEquals(1, rows.size)
        assertEquals(Direction.DEBIT, rows[0].record.direction)
        assertEquals(50_000L, rows[0].record.amountPaise)
    }
}
