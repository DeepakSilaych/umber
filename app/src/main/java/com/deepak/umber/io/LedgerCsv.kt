package com.deepak.umber.io

import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.Categories
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import com.deepak.umber.data.model.SourceType
import com.deepak.umber.ingest.ImportSession
import com.deepak.umber.ingest.IngestOutcome
import com.deepak.umber.ingest.IngestPipeline
import com.deepak.umber.ingest.txnRecord
import com.deepak.umber.parse.DateParse
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's own CSV ledger — a portable, spreadsheet-friendly round-trip of the transaction table.
 *
 * Scope is the *ledger*, not a full backup: raw SMS bodies are deliberately excluded. An export is
 * a file the user is likely to open, mail to themselves or hand to an accountant, and shipping the
 * verbatim text of every bank message into that is a privacy leak the app otherwise works hard to
 * avoid. Everything needed to reconstruct spending — including confirmed categories, which are
 * re-fed to the classifier on import — is preserved.
 */
object LedgerCsv {

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private const val COL_WHEN = "occurred_at"
    private const val COL_DIRECTION = "direction"
    private const val COL_AMOUNT = "amount_inr"
    private const val COL_MERCHANT = "merchant"
    private const val COL_CATEGORY = "category"
    private const val COL_CATEGORY_SOURCE = "category_source"
    private const val COL_CHANNEL = "channel"
    private const val COL_ACCOUNT = "account_tail"
    private const val COL_REFERENCE = "reference"
    private const val COL_BALANCE = "balance_inr"
    private const val COL_LAT = "latitude"
    private const val COL_LON = "longitude"

    private val HEADER = listOf(
        COL_WHEN, COL_DIRECTION, COL_AMOUNT, COL_MERCHANT, COL_CATEGORY, COL_CATEGORY_SOURCE,
        COL_CHANNEL, COL_ACCOUNT, COL_REFERENCE, COL_BALANCE, COL_LAT, COL_LON,
    )

    // ---------------------------------------------------------------- export

    fun export(txns: List<TxnEntity>, zone: ZoneId = ZoneId.systemDefault()): String {
        val out = StringBuilder()
        out.append(Csv.row(HEADER)).append('\n')

        for (t in txns) {
            out.append(
                Csv.row(
                    listOf(
                        Instant.ofEpochMilli(t.occurredAt).atZone(zone).format(TIMESTAMP),
                        t.direction.name,
                        // Plain decimal rupees, not a formatted currency string — this column has
                        // to be summable in a spreadsheet without cleanup.
                        rupees(t.amountPaise),
                        t.merchantRaw ?: t.merchantNorm.orEmpty(),
                        t.category,
                        t.categorySource.name,
                        t.channel.name,
                        t.accountTail.orEmpty(),
                        t.refNo.orEmpty(),
                        t.balancePaise?.let { rupees(it) }.orEmpty(),
                        t.lat?.toString().orEmpty(),
                        t.lon?.toString().orEmpty(),
                    ),
                ),
            ).append('\n')
        }

        return out.toString()
    }

    private fun rupees(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val abs = kotlin.math.abs(paise)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    // ---------------------------------------------------------------- import

    suspend fun import(
        text: String,
        origin: String,
        pipeline: IngestPipeline,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ImportReport {
        if (text.isBlank()) return ImportReport(0, 0, 0, 0, "File is empty")

        val rows = Csv.parse(text, Csv.detectDelimiter(text))
        if (rows.size < 2) return ImportReport(0, 0, 0, 0, "No data rows")

        // Column lookup is by name, so a file with reordered or extra columns still imports.
        val header = rows.first().map { it.lowercase().trim() }
        fun col(name: String) = header.indexOf(name).takeIf { it >= 0 }

        val whenCol = col(COL_WHEN) ?: return ImportReport(0, 0, 0, 0, "Missing '$COL_WHEN' column")
        val amountCol = col(COL_AMOUNT) ?: return ImportReport(0, 0, 0, 0, "Missing '$COL_AMOUNT' column")
        val directionCol = col(COL_DIRECTION)
        val merchantCol = col(COL_MERCHANT)
        val categoryCol = col(COL_CATEGORY)
        val categorySourceCol = col(COL_CATEGORY_SOURCE)
        val channelCol = col(COL_CHANNEL)
        val accountCol = col(COL_ACCOUNT)
        val referenceCol = col(COL_REFERENCE)
        val balanceCol = col(COL_BALANCE)

        val session = ImportSession()
        var imported = 0
        var duplicates = 0
        var skipped = 0

        for (row in rows.drop(1)) {
            val stamp = row.getOrNull(whenCol)
            val amount = DateParse.paise(row.getOrNull(amountCol))
            if (stamp.isNullOrBlank() || amount == null || amount == 0L) {
                skipped++
                continue
            }

            val stampParts = parseTimestamp(stamp, zone)
            if (stampParts == null) {
                skipped++
                continue
            }
            val (occurredAt, exact) = stampParts

            val direction = row.getOrNull(directionCol ?: -1)
                ?.let { runCatching { Direction.valueOf(it.uppercase()) }.getOrNull() }
                ?: if (amount < 0) Direction.DEBIT else Direction.CREDIT

            val category = row.getOrNull(categoryCol ?: -1)?.takeIf { Categories.isValid(it) }
            val categorySource = row.getOrNull(categorySourceCol ?: -1)
                ?.let { runCatching { CategorySource.valueOf(it.uppercase()) }.getOrNull() }

            val record = txnRecord(
                amountPaise = kotlin.math.abs(amount),
                direction = direction,
                occurredAt = occurredAt,
                hasExactTime = exact,
                merchantRaw = row.getOrNull(merchantCol ?: -1)?.takeIf { it.isNotBlank() },
                refNo = row.getOrNull(referenceCol ?: -1)?.takeIf { it.isNotBlank() },
                accountTail = row.getOrNull(accountCol ?: -1)?.takeIf { it.isNotBlank() },
                channel = row.getOrNull(channelCol ?: -1)
                    ?.let { runCatching { Channel.valueOf(it.uppercase()) }.getOrNull() }
                    ?: Channel.UNKNOWN,
                balancePaise = balanceCol?.let { DateParse.paise(row.getOrNull(it)) },
                presetCategory = category,
                presetCategorySource = categorySource,
            )

            when (pipeline.ingestRecord(SourceType.LEDGER_CSV, origin, row.joinToString(" | "), record, session)) {
                is IngestOutcome.Inserted -> imported++
                IngestOutcome.DuplicateMessage, IngestOutcome.DuplicateTransaction -> duplicates++
                is IngestOutcome.Rejected -> skipped++
            }
        }

        return ImportReport(rows.size - 1, imported, duplicates, skipped)
    }

    /**
     * Returns the instant plus whether a clock time was actually present.
     *
     * That flag decides the dedupe strategy downstream, so a date-only row from a hand-edited
     * spreadsheet must not masquerade as a precisely-timed observation.
     */
    private fun parseTimestamp(text: String, zone: ZoneId): Pair<Long, Boolean>? {
        runCatching { LocalDateTime.parse(text.trim(), TIMESTAMP) }.getOrNull()?.let {
            return it.atZone(zone).toInstant().toEpochMilli() to true
        }
        runCatching { LocalDateTime.parse(text.trim().replace(' ', 'T')) }.getOrNull()?.let {
            return it.atZone(zone).toInstant().toEpochMilli() to true
        }
        DateParse.date(text)?.let {
            return it.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() to false
        }
        return null
    }
}
