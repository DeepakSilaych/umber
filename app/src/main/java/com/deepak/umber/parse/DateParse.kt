package com.deepak.umber.parse

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Shared date and money parsing.
 *
 * Extracted from [SmsParser] because statement CSVs need exactly the same tolerance: Indian banks
 * write dates as `08-05-2026`, `08/05/26`, `08-May-26` and `2026-05-08` more or less
 * interchangeably, sometimes within one institution.
 */
object DateParse {

    private val FORMATS: List<DateTimeFormatter> = listOf(
        "d-M-yy", "d-M-yyyy", "d/M/yy", "d/M/yyyy",
        "d-MMM-yy", "d-MMM-yyyy", "d MMM yy", "d MMM yyyy",
        "d/MMM/yy", "d/MMM/yyyy", "ddMMMyy", "ddMMMyyyy",
        "yyyy-M-d", "yyyy/M/d", "d.M.yy", "d.M.yyyy",
    ).map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
    }

    /**
     * Excel stores dates as a day count from 1899-12-30.
     *
     * The odd epoch is the well-known 1900 leap-year bug: Excel believes 1900 was a leap year, and
     * anchoring two days before 1900-01-01 cancels it out for every date after March 1900 — which
     * is every date a bank statement will ever contain.
     */
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /** ~1954 to ~2119. Narrow enough that a plausible amount is unlikely to be read as a date. */
    private val SERIAL_RANGE = 20_000L..80_000L

    fun excelSerial(serial: Long): LocalDate? =
        if (serial in SERIAL_RANGE) EXCEL_EPOCH.plusDays(serial) else null

    fun date(token: String?): LocalDate? {
        val cleaned = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // Statement cells often carry a time component; the date is the leading token.
        val head = cleaned.substringBefore(' ').ifEmpty { cleaned }
        for (candidate in listOf(head, cleaned)) {
            for (fmt in FORMATS) {
                try {
                    return LocalDate.parse(candidate, fmt)
                } catch (e: Exception) {
                    // Wrong format for this token; keep trying.
                }
            }
        }

        // xlsx date cells arrive as bare serial numbers when the workbook stores them as real
        // dates rather than text.
        cleaned.substringBefore('.').toLongOrNull()?.let { serial ->
            excelSerial(serial)?.let { return it }
        }

        return null
    }

    /**
     * Rupee string to integer paise.
     *
     * Tolerates currency symbols, thousands separators, whitespace, trailing `Dr`/`Cr` markers and
     * parenthesised negatives — all of which appear in real exports. Returns null when there is no
     * number at all, and preserves sign so a single-amount column can encode direction.
     */
    fun paise(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        var s = text.trim()

        val parenthesised = s.startsWith("(") && s.endsWith(")")
        if (parenthesised) s = s.substring(1, s.length - 1)

        val trailingDr = s.uppercase().endsWith("DR")
        s = s.replace(Regex("""(?i)\b[dc]r\b"""), "")
            // No trailing \b after the optional dot: in "Rs. 500" a boundary between "." and " "
            // does not exist, so requiring one leaves the dot behind and ".500" parses as ₹0.50.
            .replace(Regex("""(?i)\brs\.?|\binr\b|\brupees\b|[₹$]"""), "")
            .replace(",", "")
            .replace(" ", "")
            .trim()
            // Any separator stranded by the strip above is not a decimal point.
            .trimStart('.', ':')

        if (s.isEmpty() || s == "-" || s == ".") return null

        return try {
            var value = BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
            if (parenthesised || trailingDr) value = -kotlin.math.abs(value)
            value
        } catch (e: NumberFormatException) {
            null
        }
    }
}
