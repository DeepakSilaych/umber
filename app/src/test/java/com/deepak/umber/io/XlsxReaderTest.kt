package com.deepak.umber.io

import com.deepak.umber.data.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    // ------------------------------------------------------------- format sniffing

    @Test
    fun `detects zip and legacy xls containers`() {
        assertTrue(XlsxReader.looksLikeZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(XlsxReader.looksLikeZip("Date,Amount".toByteArray()))

        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0)
        assertTrue(XlsxReader.looksLikeLegacyXls(ole))
        assertFalse(XlsxReader.looksLikeLegacyXls("Date,Amount".toByteArray()))
    }

    @Test
    fun `non zip input is refused rather than misread`() {
        assertNull(XlsxReader.read("Date,Narration,Amount".toByteArray()))
    }

    // ------------------------------------------------------------------- cell refs

    @Test
    fun `column letters convert to zero based indices`() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(1, XlsxReader.columnIndex("B12"))
        assertEquals(25, XlsxReader.columnIndex("Z3"))
        assertEquals(26, XlsxReader.columnIndex("AA1"))
        assertEquals(54, XlsxReader.columnIndex("BC12"))
    }

    // -------------------------------------------------------------- shared strings

    /** One logical string can be split across styled runs; all of them belong to that entry. */
    @Test
    fun `runs within one shared string are concatenated`() {
        val xml = """
            <sst><si><t>Date</t></si><si><r><t>With</t></r><r><t>drawal</t></r></si></sst>
        """.trimIndent()

        assertEquals(listOf("Date", "Withdrawal"), XlsxReader.parseSharedStrings(xml))
    }

    // ---------------------------------------------------------------------- sheets

    /**
     * Empty cells are omitted from the XML entirely. Without honouring the `r` reference the row
     * would shift left and every column after the gap would be misread.
     */
    @Test
    fun `omitted cells are padded from the cell reference`() {
        val sheet = """
            <worksheet><sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="s"><v>1</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()

        val rows = XlsxReader.parseSheet(sheet, listOf("Date", "Amount"))
        assertEquals(listOf("Date", "", "Amount"), rows[0])
    }

    @Test
    fun `numeric and inline string cells are read`() {
        val sheet = """
            <worksheet><sheetData>
              <row r="1"><c r="A1"><v>45784</v></c><c r="B1" t="inlineStr"><is><t>ACME</t></is></c><c r="C1"><v>-500.5</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()

        assertEquals(listOf("45784", "ACME", "-500.5"), XlsxReader.parseSheet(sheet, emptyList())[0])
    }

    // ------------------------------------------------------------- end to end xlsx

    /** Builds a real xlsx in memory, so the zip and XML layers are exercised together. */
    private fun buildWorkbook(sharedStrings: List<String>, sheetXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(
                buildString {
                    append("<sst>")
                    sharedStrings.forEach { append("<si><t>").append(it).append("</t></si>") }
                    append("</sst>")
                }.toByteArray(),
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `a statement workbook parses into transactions`() {
        val shared = listOf(
            "Date", "Narration", "Withdrawal Amt", "Deposit Amt",
            "UPI/512345678901/PAYMENT TO ARJUN MEHTA", "NEFT/700000000002/ACME PAYROLL",
        )
        val sheet = """
            <worksheet><sheetData>
              <row r="1">
                <c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c>
                <c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c>
              </row>
              <row r="2">
                <c r="A2" t="inlineStr"><is><t>02/05/2026</t></is></c>
                <c r="B2" t="s"><v>4</v></c><c r="C2"><v>500</v></c>
              </row>
              <row r="3">
                <c r="A3" t="inlineStr"><is><t>10/05/2026</t></is></c>
                <c r="B3" t="s"><v>5</v></c><c r="D3"><v>75000</v></c>
              </row>
            </sheetData></worksheet>
        """.trimIndent()

        val rows = XlsxReader.read(buildWorkbook(shared, sheet))
        assertEquals(3, rows?.size)

        val parsed = StatementParser.parseRows(rows!!, zone)
        assertNull(parsed.problem)
        assertEquals(2, parsed.rows.size)

        assertEquals(Direction.DEBIT, parsed.rows[0].record.direction)
        assertEquals(50_000L, parsed.rows[0].record.amountPaise)
        assertEquals("512345678901", parsed.rows[0].record.refNo)
        assertEquals("arjun mehta", parsed.rows[0].record.merchantNorm)

        assertEquals(Direction.CREDIT, parsed.rows[1].record.direction)
        assertEquals(75_00_000L, parsed.rows[1].record.amountPaise)
    }

    /**
     * Excel stores real dates as day counts from 1899-12-30, so a workbook whose date column is
     * formatted rather than text arrives as bare numbers.
     */
    @Test
    fun `excel date serials are understood`() {
        val shared = listOf("Date", "Narration", "Amount", "ACME STORE")
        val sheet = """
            <worksheet><sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
              <row r="2"><c r="A2"><v>46150</v></c><c r="B2" t="s"><v>3</v></c><c r="C2"><v>-250</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()

        val parsed = StatementParser.parseRows(XlsxReader.read(buildWorkbook(shared, sheet))!!, zone)
        assertEquals(1, parsed.rows.size)
        assertEquals(Direction.DEBIT, parsed.rows[0].record.direction)
        assertEquals(25_000L, parsed.rows[0].record.amountPaise)

        // Serial 46150 is 2026-05-08.
        val day = java.time.Instant.ofEpochMilli(parsed.rows[0].record.occurredAt)
            .atZone(zone).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 5, 8), day)
    }
}
