package com.deepak.umber.io

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Reads `.xlsx` workbooks without a spreadsheet library.
 *
 * An xlsx *is* a zip of XML, so the whole job is `ZipInputStream` plus SAX — both in the platform.
 * Pulling in Apache POI would add several megabytes to an app that ships no network permission and
 * whose entire pitch is that it stays small and local.
 *
 * Only what a bank statement uses is supported: the first worksheet, shared strings, inline
 * strings, and numeric cells. Formulas resolve to their cached values, which is what banks emit.
 *
 * The legacy binary `.xls` format is *not* supported — it is an entirely different container
 * (BIFF), and reimplementing it is not worth it when every bank offers xlsx or CSV as well.
 */
object XlsxReader {

    /** Zip local-file-header magic. Cheap way to tell an xlsx from a CSV before doing any work. */
    fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    /** True for the legacy binary format, so the caller can give a useful message instead of failing. */
    fun looksLikeLegacyXls(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

    /**
     * Returns the first worksheet as rows of plain strings, or null if this isn't a readable xlsx.
     */
    fun read(bytes: ByteArray): List<List<String>>? {
        if (!looksLikeZip(bytes)) return null

        var sharedXml: String? = null
        var sheetXml: String? = null
        var sheetName: String? = null

        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name

                    when {
                        name == "xl/sharedStrings.xml" -> sharedXml = zip.readBytes().decodeToString()

                        // Sheets are not ordered inside the archive, so keep the
                        // lexicographically-first — "sheet1.xml" beats "sheet10.xml".
                        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") -> {
                            if (sheetName == null || name < sheetName!!) {
                                sheetName = name
                                sheetXml = zip.readBytes().decodeToString()
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            return null
        }

        val sheet = sheetXml ?: return null
        val shared = sharedXml?.let { parseSharedStrings(it) } ?: emptyList()
        return runCatching { parseSheet(sheet, shared) }.getOrNull()
    }

    private fun parser() = SAXParserFactory.newInstance().apply { isNamespaceAware = false }.newSAXParser()

    /**
     * `sharedStrings.xml` holds every distinct string once; cells reference them by index.
     *
     * A single entry can be split across several `<r>` runs when parts of the text are styled
     * differently, so all `<t>` inside one `<si>` are concatenated rather than taking the first.
     */
    fun parseSharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inItem = false
        var inText = false

        parser().parse(
            ByteArrayInputStream(xml.toByteArray()),
            object : DefaultHandler() {
                override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                    when (qName) {
                        "si" -> { inItem = true; current.setLength(0) }
                        "t" -> inText = true
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inItem && inText) current.appendRange(ch, start, start + length)
                }

                override fun endElement(uri: String?, local: String?, qName: String) {
                    when (qName) {
                        "t" -> inText = false
                        "si" -> { out.add(current.toString()); inItem = false }
                    }
                }
            },
        )

        return out
    }

    fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()

        var cellType: String? = null
        var cellColumn = 0
        val value = StringBuilder()
        var inValue = false

        parser().parse(
            ByteArrayInputStream(xml.toByteArray()),
            object : DefaultHandler() {
                override fun startElement(uri: String?, local: String?, qName: String, attrs: Attributes?) {
                    when (qName) {
                        "row" -> row = ArrayList()
                        "c" -> {
                            cellType = attrs?.getValue("t")
                            // Empty cells are omitted from the XML entirely, so the column index is
                            // read from the reference and gaps are padded — otherwise every row
                            // with a blank cell would shift its columns left.
                            cellColumn = columnIndex(attrs?.getValue("r"))
                            value.setLength(0)
                        }
                        // <v> is a value, <t> covers inline strings.
                        "v", "t" -> { inValue = true; }
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inValue) value.appendRange(ch, start, start + length)
                }

                override fun endElement(uri: String?, local: String?, qName: String) {
                    when (qName) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val raw = value.toString()
                            val text = if (cellType == "s") {
                                raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            } else {
                                raw
                            }
                            while (row.size < cellColumn) row.add("")
                            if (row.size == cellColumn) row.add(text) else row[cellColumn] = text
                        }
                        "row" -> if (row.any { it.isNotBlank() }) rows.add(row)
                    }
                }
            },
        )

        return rows
    }

    /** `"BC12"` -> 54. Letters are base-26 with no zero, so A=1 before shifting to 0-based. */
    fun columnIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return 0
        var index = 0
        for (c in ref) {
            if (!c.isLetter()) break
            index = index * 26 + (c.uppercaseChar() - 'A' + 1)
        }
        return (index - 1).coerceAtLeast(0)
    }
}
