package com.deepak.umber.io

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {

    @Test
    fun `parses simple rows`() {
        val rows = Csv.parse("a,b,c\n1,2,3")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    /** Bank narrations are full of commas — "PAYMENT TO ACME, MUMBAI" is one field, not two. */
    @Test
    fun `quoted field keeps embedded delimiter`() {
        val rows = Csv.parse("""date,"PAYMENT TO ACME, MUMBAI",100""")
        assertEquals(listOf("date", "PAYMENT TO ACME, MUMBAI", "100"), rows[0])
    }

    @Test
    fun `escaped quotes are unescaped`() {
        val rows = Csv.parse("\"he said \"\"hi\"\"\",2")
        assertEquals(listOf("he said \"hi\"", "2"), rows[0])
    }

    @Test
    fun `quoted field may span newlines`() {
        val rows = Csv.parse("a,\"line one\nline two\",c")
        assertEquals(3, rows[0].size)
        assertEquals("line one\nline two", rows[0][1])
    }

    @Test
    fun `crlf is one row terminator`() {
        assertEquals(2, Csv.parse("a,b\r\nc,d").size)
    }

    /** Bank exports are riddled with spacer lines; they must not become empty transactions. */
    @Test
    fun `blank rows are dropped`() {
        assertEquals(2, Csv.parse("a,b\n\n\n,,\nc,d").size)
    }

    @Test
    fun `detects tab and semicolon delimited files`() {
        assertEquals('\t', Csv.detectDelimiter("a\tb\tc\n1\t2\t3"))
        assertEquals(';', Csv.detectDelimiter("a;b;c\n1;2;3"))
        assertEquals(',', Csv.detectDelimiter("a,b,c\n1,2,3"))
    }

    /**
     * A one-column title row above a comma-separated table must not swing delimiter detection —
     * hence the median across several lines rather than a look at the first.
     */
    @Test
    fun `preamble line does not confuse delimiter detection`() {
        val text = "Statement of account\nDate,Narration,Amount\n01/05/2026,X,10"
        assertEquals(',', Csv.detectDelimiter(text))
    }

    @Test
    fun `escape quotes only when needed`() {
        assertEquals("plain", Csv.escape("plain"))
        assertEquals("\"has,comma\"", Csv.escape("has,comma"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""))
    }

    @Test
    fun `row round trips through parse`() {
        val original = listOf("2026-05-08 14:00", "DEBIT", "99.00", "ACME, INC")
        assertEquals(original, Csv.parse(Csv.row(original))[0])
    }
}
