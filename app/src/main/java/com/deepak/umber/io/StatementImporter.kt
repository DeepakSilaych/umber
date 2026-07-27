package com.deepak.umber.io

import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import com.deepak.umber.data.model.SourceType
import com.deepak.umber.ingest.ImportSession
import com.deepak.umber.ingest.IngestOutcome
import com.deepak.umber.ingest.IngestPipeline
import com.deepak.umber.ingest.TxnRecord
import com.deepak.umber.ingest.txnRecord
import com.deepak.umber.parse.DateParse
import java.time.ZoneId
import kotlin.math.abs

data class ImportReport(
    val rows: Int,
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
    val problem: String? = null,
) {
    val ok: Boolean get() = problem == null
}

/** A parsed statement row plus the original line, kept for provenance. */
data class StatementRow(val record: TxnRecord, val rawLine: String)

data class StatementParseResult(
    val rows: List<StatementRow>,
    val totalDataRows: Int,
    val skipped: Int,
    val problem: String? = null,
)

/**
 * Turns a bank statement CSV/TSV into transaction records.
 *
 * There is no standard schema — every Indian bank invents its own column names, throws a few rows
 * of account preamble above the header, and disagrees about whether debit and credit are separate
 * columns or one signed column. So rather than a per-bank template library, the header row is
 * located by scoring and columns are matched on keywords.
 *
 * Pure and Android-free, so the messy part is unit-testable without a device.
 *
 * PDF statements are deliberately out of scope: they need a rendering library (megabytes of
 * dependency for an app that ships no network permission) and are usually password-protected.
 * Every major Indian bank offers CSV or Excel export alongside the PDF.
 */
object StatementParser {

    private val DATE_KEYS = listOf("value date", "txn date", "transaction date", "tran date", "posting date", "date")
    private val NARRATION_KEYS = listOf("narration", "description", "particulars", "transaction remarks", "transaction details", "remarks", "details")
    private val DEBIT_KEYS = listOf("withdrawal amt", "withdrawal amount", "debit amount", "withdrawal", "debit", "paid out", "dr")
    private val CREDIT_KEYS = listOf("deposit amt", "deposit amount", "credit amount", "deposit", "credit", "paid in", "cr")
    private val AMOUNT_KEYS = listOf("transaction amount", "txn amount", "amount")
    private val BALANCE_KEYS = listOf("closing balance", "available balance", "running balance", "balance")
    private val REF_KEYS = listOf("chq/ref no", "ref no./cheque no", "reference no", "transaction id", "txn id", "utr no", "reference", "ref no", "cheque no", "utr")

    /**
     * References embedded in a narration, anchored to a scheme marker.
     *
     * Anchoring is essential: narrations also contain account numbers of similar length, and
     * mistaking one for a reference would merge unrelated transactions — a far worse outcome than
     * simply not finding a reference.
     */
    private val NARRATION_REF = Regex(
        """(?i)\b(?:UPI|IMPS|NEFT|RTGS|UTR|RRN|MMT|ACH)[\s/:\-]{1,3}([A-Z0-9]{8,22})""",
    )

    private const val HEADER_SEARCH_DEPTH = 30

    private val SCHEME_WORDS = setOf(
        "upi", "imps", "neft", "rtgs", "ach", "pos", "atm", "mmt", "inb", "nwd",
        "dr", "cr", "p2a", "p2m", "payment", "transfer", "txn",
    )

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): StatementParseResult {
        if (text.isBlank()) return StatementParseResult(emptyList(), 0, 0, "File is empty")
        return parseRows(Csv.parse(text, Csv.detectDelimiter(text)), zone)
    }

    /**
     * Shared by the CSV and xlsx paths.
     *
     * A spreadsheet and a CSV are the same grid of cells once decoded, so column detection and row
     * interpretation have exactly one implementation.
     */
    fun parseRows(rows: List<List<String>>, zone: ZoneId = ZoneId.systemDefault()): StatementParseResult {
        if (rows.isEmpty()) return StatementParseResult(emptyList(), 0, 0, "No rows found")

        val headerIndex = findHeaderRow(rows)
            ?: return StatementParseResult(emptyList(), 0, 0, "Couldn't find a header row with a date and an amount column")

        val header = rows[headerIndex].map { it.lowercase().trim() }
        val dateCol = header.indexOfKey(DATE_KEYS)
            ?: return StatementParseResult(emptyList(), 0, 0, "No date column")

        val narrationCol = header.indexOfKey(NARRATION_KEYS)
        val debitCol = header.indexOfKey(DEBIT_KEYS)
        val creditCol = header.indexOfKey(CREDIT_KEYS)
        val amountCol = header.indexOfKey(AMOUNT_KEYS)
        val balanceCol = header.indexOfKey(BALANCE_KEYS)
        val refCol = header.indexOfKey(REF_KEYS)

        if (debitCol == null && creditCol == null && amountCol == null) {
            return StatementParseResult(emptyList(), 0, 0, "No debit/credit or amount column")
        }

        val out = ArrayList<StatementRow>()
        var skipped = 0
        val dataRows = rows.drop(headerIndex + 1)

        for (row in dataRows) {
            val date = DateParse.date(row.getOrNull(dateCol))
            val signed = resolveAmount(row, debitCol, creditCol, amountCol)
            if (date == null || signed == null || signed == 0L) {
                skipped++
                continue
            }

            val narration = narrationCol?.let { row.getOrNull(it) }.orEmpty()
            val ref = refCol?.let { row.getOrNull(it) }?.takeIf { it.isNotBlank() && it != "-" }
                ?: NARRATION_REF.find(narration)?.groupValues?.get(1)

            out.add(
                StatementRow(
                    record = txnRecord(
                        amountPaise = abs(signed),
                        direction = if (signed < 0) Direction.DEBIT else Direction.CREDIT,
                        // Midday, not midnight: a statement gives no clock time, and anchoring at
                        // noon keeps the row inside the intended day under any timezone nudge.
                        occurredAt = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                        hasExactTime = false,
                        merchantRaw = merchantFrom(narration),
                        refNo = ref,
                        channel = channelFrom(narration),
                        balancePaise = balanceCol?.let { DateParse.paise(row.getOrNull(it)) },
                    ),
                    rawLine = row.joinToString(" | "),
                ),
            )
        }

        return StatementParseResult(out, dataRows.size, skipped)
    }

    /** Exact match first, so "amount" never wins over "withdrawal amount" by accident. */
    private fun List<String>.indexOfKey(keys: List<String>): Int? {
        keys.forEach { key -> indexOfFirst { it == key }.takeIf { it >= 0 }?.let { return it } }
        keys.forEach { key -> indexOfFirst { it.contains(key) }.takeIf { it >= 0 }?.let { return it } }
        return null
    }

    /**
     * Scores each row on how many known column keywords it contains and takes the best.
     *
     * Bank exports routinely put account holder, account number and statement period above the real
     * header, so the first row is usually the wrong answer.
     */
    private fun findHeaderRow(rows: List<List<String>>): Int? {
        val all = DATE_KEYS + NARRATION_KEYS + DEBIT_KEYS + CREDIT_KEYS + AMOUNT_KEYS + BALANCE_KEYS + REF_KEYS
        var best = -1
        var bestScore = 0

        rows.take(HEADER_SEARCH_DEPTH).forEachIndexed { index, row ->
            val cells = row.map { it.lowercase().trim() }
            val hasDate = cells.any { cell -> DATE_KEYS.any { cell.contains(it) } }
            val hasMoney = cells.any { cell -> (DEBIT_KEYS + CREDIT_KEYS + AMOUNT_KEYS).any { cell.contains(it) } }
            val score = cells.count { cell -> cell.isNotEmpty() && all.any { cell.contains(it) } }

            if (hasDate && hasMoney && score > bestScore) {
                bestScore = score
                best = index
            }
        }

        return best.takeIf { it >= 0 }
    }

    /** Negative is a debit. Separate debit/credit columns take precedence over a signed column. */
    private fun resolveAmount(row: List<String>, debitCol: Int?, creditCol: Int?, amountCol: Int?): Long? {
        debitCol?.let { DateParse.paise(row.getOrNull(it)) }?.takeIf { it != 0L }?.let { return -abs(it) }
        creditCol?.let { DateParse.paise(row.getOrNull(it)) }?.takeIf { it != 0L }?.let { return abs(it) }
        return amountCol?.let { DateParse.paise(row.getOrNull(it)) }?.takeIf { it != 0L }
    }

    /**
     * Pulls a merchant out of a slash-delimited narration such as
     * `UPI/512345678902/PAYMENT TO SWIGGY` by taking the most word-like segment.
     */
    private fun merchantFrom(narration: String): String? {
        if (narration.isBlank()) return null

        val segments = narration.split('/', '|', ':')
            .map { it.trim() }
            .filter { segment ->
                segment.length >= 3 &&
                    segment.count { it.isLetter() } >= 3 &&
                    segment.lowercase() !in SCHEME_WORDS
            }

        val best = segments.maxByOrNull { it.count { c -> c.isLetter() } } ?: return null
        return best
            .replace(Regex("""(?i)^payment (?:to|from)\s+"""), "")
            .trim()
            .take(60)
            .takeIf { it.isNotEmpty() }
    }

    private fun channelFrom(narration: String): Channel {
        val n = narration.lowercase()
        return when {
            n.contains("upi") -> Channel.UPI
            n.contains("imps") -> Channel.IMPS
            n.contains("neft") -> Channel.NEFT
            n.contains("rtgs") -> Channel.RTGS
            n.contains("atm") || n.contains("cash wdl") -> Channel.ATM
            n.contains("pos") || n.contains("card") -> Channel.CARD
            n.contains("ach") || n.contains("mandate") -> Channel.AUTOPAY
            else -> Channel.UNKNOWN
        }
    }
}

/** Feeds [StatementParser] output through the shared ingest path so dedupe is identical. */
class StatementImporter(
    private val pipeline: IngestPipeline,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Accepts the file as bytes and decides how to decode it.
     *
     * Format is detected from magic bytes rather than the filename or MIME type, because banks
     * routinely serve a CSV named `.xls` and the document picker's reported type is unreliable.
     */
    suspend fun import(bytes: ByteArray, origin: String): ImportReport {
        if (bytes.isEmpty()) return ImportReport(0, 0, 0, 0, "File is empty")

        if (XlsxReader.looksLikeLegacyXls(bytes)) {
            return ImportReport(
                0, 0, 0, 0,
                "That's an old-format .xls file. Open it and 'Save as' .xlsx or CSV, then try again.",
            )
        }

        if (XlsxReader.looksLikeZip(bytes)) {
            val rows = XlsxReader.read(bytes)
                ?: return ImportReport(0, 0, 0, 0, "Couldn't read that spreadsheet")
            return ingest(StatementParser.parseRows(rows, zone), origin)
        }

        return import(String(bytes, Charsets.UTF_8).removePrefix("﻿"), origin)
    }

    suspend fun import(text: String, origin: String): ImportReport =
        ingest(StatementParser.parse(text, zone), origin)

    private suspend fun ingest(parsed: StatementParseResult, origin: String): ImportReport {
        if (parsed.problem != null) {
            return ImportReport(0, 0, 0, 0, parsed.problem)
        }

        val session = ImportSession()
        var imported = 0
        var duplicates = 0
        var skipped = parsed.skipped

        for (row in parsed.rows) {
            when (pipeline.ingestRecord(SourceType.STATEMENT, origin, row.rawLine, row.record, session)) {
                is IngestOutcome.Inserted -> imported++
                IngestOutcome.DuplicateMessage, IngestOutcome.DuplicateTransaction -> duplicates++
                is IngestOutcome.Rejected -> skipped++
            }
        }

        return ImportReport(parsed.totalDataRows, imported, duplicates, skipped)
    }
}
