package com.deepak.umber.parse

import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.abs

data class ParsedTxn(
    val amountPaise: Long,
    val direction: Direction,
    val channel: Channel,
    val accountTail: String?,
    val merchantRaw: String?,
    val merchantNorm: String,
    val vpaHandle: String?,
    val refNo: String?,
    val balancePaise: Long?,
    val occurredAt: Long,
)

sealed interface ParseResult {
    data class Parsed(val txn: ParsedTxn) : ParseResult
    data class Rejected(val reason: String) : ParseResult
}

/**
 * Rule-based extraction of transaction fields from Indian bank / UPI SMS.
 *
 * Deliberately *not* machine learning. Bank SMS are templated, so regex is both more accurate and
 * far easier to debug than a sequence model — and when it's wrong you can see exactly why. ML is
 * reserved for the genuinely fuzzy problem (merchant -> category).
 *
 * Every parsed row records [VERSION]. Bump it when extraction changes so old messages can be
 * re-parsed from `raw_message` without needing the original SMS to still be on the device.
 *
 * Pure JVM by design: no Android imports, so the whole thing is unit-testable.
 */
object SmsParser {

    /**
     * Bumped to 2: reference extraction, negated-debit rejection and merchant patterns all changed.
     * The bump is what makes `IngestPipeline.reparseRejected` replay everything version 1 threw
     * away — including the mandate payments lost to the "MANDATE" reference collision.
     */
    const val VERSION = 2

    // ---------------------------------------------------------------- rejection

    private val REJECT_OTP = Regex(
        """\botp\b|one[\s-]?time[\s-]?password|verification code|\bcvv\b""",
        RegexOption.IGNORE_CASE,
    )

    private val REJECT_PROMO = Regex(
        """pre[\s-]?approved|apply now|click here|hurry|limited period|offer ends|""" +
            """t&c apply|download the app|you have won|congratulations|lowest interest|""" +
            """upgrade your|activate now|refer and earn""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Future tense, requests, and failures: real-looking, but nothing has moved.
     *
     * The negated forms matter as much as the future ones. A bank reporting a *failed* mandate
     * ("Your account is **not debited** with Rs 500.00 ... due to cbs rejection") is
     * word-for-word a successful debit apart from the negation, so without this it books as a real
     * spend.
     */
    private val REJECT_NOT_YET = Regex(
        """will be (?:debited|deducted|credited|charged|transferred|reversed|refunded|blocked|processed)|""" +
            """is due|due on|due date|""" +
            """(?:collect|payment|money) request|has requested|requesting|""" +
            """(?:failed|declined|unsuccessful|not processed|could not be processed)|""" +
            """\bnot (?:debited|credited|deducted|charged)\b|\brejection\b|""" +
            """to (?:authorise|authorize|approve)|scheduled""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Informational messages with no movement of money.
     *
     * Balance enquiries need no rule of their own — they carry no debit/credit verb, so direction
     * detection rejects them anyway.
     */
    private val REJECT_INFO_ONLY = Regex(
        """mini statement|statement is ready|statement has been generated|e-statement""",
        RegexOption.IGNORE_CASE,
    )

    // ---------------------------------------------------------------- amounts

    private val AMOUNT_PREFIXED = Regex(
        """(?:rs\.?|inr|₹)\s*\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Amount written before the currency token ("500.00 INR").
     *
     * The leading lookbehind is load-bearing: without it, "A/c XX1234 Rs.500.00" matches on the
     * masked account tail ("1234" followed by a currency token) and — because the tail appears
     * earlier in the string — that spurious hit wins the earliest-amount rule and ₹1,234 gets
     * booked instead of ₹500.
     */
    private val AMOUNT_SUFFIXED = Regex(
        """(?<![xX*\d])([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*(?:rs\.?|inr|rupees)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Amounts with no currency marker at all, e.g. SBI's "A/C X8912 debited by 120.0".
     *
     * Anchored to a transaction verb rather than matching bare numbers anywhere, which would
     * happily read a reference number or an account tail as an amount.
     */
    private val AMOUNT_BARE = Regex(
        """(?:debited|credited|debit|credit|spent|paid|withdrawn|sent)\s*(?:by|for|with|of|:)?\s*""" +
            """([0-9][0-9,]*(?:\.[0-9]{1,2})?)(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Matched against the text immediately preceding an amount to tell a balance from a spend.
     *
     * The trailing optional currency token matters: the gap between "Avl Bal" and the digits
     * contains "Rs.", so without it every balance would be misread as a transaction amount.
     */
    private val BALANCE_CONTEXT = Regex(
        """(?:avl|avail(?:able)?|closing|current|a\/c|total)?\s*(?:bal|balance|limit)""" +
            """\s*(?:is|of|:)?\s*(?:rs\.?|inr|₹)?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    // ---------------------------------------------------------------- direction

    private val DEBIT_WORDS = listOf(
        "debited", "debit", "spent", "paid", "withdrawn", "withdrawal", "purchased",
        "purchase", "deducted", "charged", "sent", "transferred", "trf to", "used for",
        "used at", "was used", "using your", "payment of", "txn of",
        // Telecom and wallet top-ups report success without ever saying "debited". The phrases are
        // deliberately specific: bare "recharge"/"topup" also appear in marketing SMS
        // ("Get 12GB data topup at just Rs161 | Recharge now"), which must not book as spending.
        "recharge of", "top-up of", "topup of", "top-up amounting", "topup amounting",
    )

    private val CREDIT_WORDS = listOf(
        "credited", "credit", "received", "deposited", "refund", "reversed",
        "cashback", "added to", "has been credited",
    )

    /**
     * `Credit Card` / `Debit Card` contain direction keywords that mean nothing about direction.
     * Masking them before the earliest-keyword scan avoids classifying every credit-card spend as
     * income.
     */
    private val CARD_PHRASE = Regex("""(credit|debit)\s*card""", RegexOption.IGNORE_CASE)
    private val CREDIT_LIMIT = Regex("""credit\s*limit""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- other fields

    private val ACCOUNT_LABELLED = Regex(
        """(?:a\/c|a\.c|ac|acct|account|card)\s*(?:no\.?|number|ending(?:\s*with)?)?\s*""" +
            """[:#\-]?\s*[xX*]{0,8}\s*(\d{3,6})""",
        RegexOption.IGNORE_CASE,
    )

    private val ACCOUNT_MASKED = Regex("""[xX*]{2,}\s?(\d{3,6})\b""")

    /**
     * Reference / RRN / UTR.
     *
     * A bare `upi` has to stay a trigger — "UPI:512345678902" is how ICICI writes the RRN — but it
     * also matches the extremely common phrase "UPI Mandate" and captures the word "Mandate".
     * Since a reference doubles as a dedupe key, that one mis-capture makes every recurring mandate
     * payment look like a duplicate of the first and silently discards the rest.
     *
     * The fix is validation, not a narrower trigger: every match is scanned in order and the first
     * one passing [isPlausibleRef] wins, so "Mandate" is skipped and the real
     * "Retrieval Ref No.512345678901" later in the same message is found.
     */
    private val REF = Regex(
        """(?:(?:retrieval\s+)?ref(?:erence)?|rrn|utr|txn(?:\s*id)?|upi(?:\s*ref(?:\s*no)?)?|transaction\s*id)""" +
            """\s*(?:no\.?|id|#)?\s*[:.\-]?\s*([A-Za-z0-9]{6,25})""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A usable reference is mostly digits. Requiring four guards the dedupe key against ordinary
     * words picked up from the surrounding sentence; when nothing qualifies the reference is left
     * null and dedupe falls back to the time window, which fails safe by keeping the transaction.
     */
    private const val MIN_REF_DIGITS = 4

    private fun isPlausibleRef(candidate: String): Boolean =
        candidate.count { it.isDigit() } >= MIN_REF_DIGITS

    private fun extractRef(body: String): String? =
        REF.findAll(body)
            .map { it.groupValues[1] }
            .firstOrNull { isPlausibleRef(it) }
            ?.uppercase()

    private val VPA_ANY = Regex("""\b([a-z0-9][a-z0-9._\-]{1,}@[a-z]{2,})\b""", RegexOption.IGNORE_CASE)

    /**
     * Ordered merchant extractors — first hit wins, so the most specific patterns come first.
     * The trailing lookahead stops a name from swallowing the rest of the sentence.
     */
    private const val NAME_END = """(?=\s+(?:on|ref|refno|retrieval|upi|rs|inr|txn|avl|avail|bal|dt|date|not|via|thru|for|from|as|per|standing|credited|debited|is|has|call|sms)\b|[.,;:\n]|$)"""
    private const val NAME_BODY = """[A-Za-z][A-Za-z0-9 &.'\-]{2,40}?"""

    /**
     * Ordered merchant extractors — first plausible hit wins, so the most specific templates come
     * before the generic prepositions.
     */
    private val MERCHANT_PATTERNS = listOf(
        Regex("""\b(?:to|towards)\s+vpa\s+([a-z0-9._\-]{2,}@[a-z]{2,})""", RegexOption.IGNORE_CASE),
        VPA_ANY,
        // Card-network descriptor: "... on 06-May-26 VSI*YOUTUBEGO. Bal Rs. 863.13". The merchant
        // is a bare token behind a scheme prefix, with no preposition to anchor on.
        Regex("""\b[A-Z]{2,6}\*([A-Za-z][A-Za-z0-9 &.'\-]{1,40}?)(?=[.,;:\n]|$)"""),
        // ICICI's UPI debit form: "Acct XX9012 debited for Rs 99.00 on 08-May-26; VENDCO credited."
        // Anchored to a semicolon specifically — a comma would also match "Dear Customer, Acct
        // XX9012 is credited ...", capturing the user's own account as the payee.
        Regex(""";\s*($NAME_BODY)\s+credited\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:trf to|transferred to|transfer to|paid to|sent to|payment to|to)\s+($NAME_BODY)$NAME_END""", RegexOption.IGNORE_CASE),
        Regex("""\bat\s+($NAME_BODY)$NAME_END""", RegexOption.IGNORE_CASE),
        Regex("""\binfo\s*[:\-]\s*([A-Za-z0-9][A-Za-z0-9 &.'\-/]{2,40}?)(?=[.,;\n]|$)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:towards|for)\s+($NAME_BODY)$NAME_END""", RegexOption.IGNORE_CASE),
        // Credit-card bill payments name no merchant, only the instrument being paid off.
        Regex("""\bfor your\s+((?:[A-Za-z]+\s+){0,3}credit card)\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Only meaningful on credits, where "from X" names the payer.
     *
     * On a debit the same phrase names the *user's own* account ("Rs 500 debited from ICICI Bank
     * Savings Account XX9012"), so applying it in both directions would file every outgoing payment
     * under the user's own bank.
     */
    private val MERCHANT_FROM = Regex("""\bfrom\s+($NAME_BODY)$NAME_END""", RegexOption.IGNORE_CASE)

    /** Labels that precede the real name: "to Merchant Youtube", "to Payee ABC". */
    private val MERCHANT_LABEL = Regex("""^(?:merchant|payee|vpa|beneficiary|your)\s*:?\s+""", RegexOption.IGNORE_CASE)

    /** A candidate that is really just the amount, e.g. "for Rs 99.00 on ...". */
    private val CURRENCY_ONLY = Regex("""^(?:rs|inr|₹)\b""", RegexOption.IGNORE_CASE)

    /**
     * The user's own account is not a payee.
     *
     * "…transferred to your Savings Account XXX9012" matches the generic `to <name>` pattern
     * perfectly, and after the "your" label is stripped it looks like an ordinary merchant.
     */
    private val OWN_ACCOUNT = Regex(
        """^(?:savings|current|salary|joint|nre|nro)?\s*(?:a/?c|account|acct)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val THREE_LETTERS = Regex("""[A-Za-z]{3}""")

    /**
     * Boilerplate that follows the useful part of the message. Cutting here stops "to" in
     * "Not you? Call to 18001234" from being read as a payee.
     */
    private val TRAILER = Regex(
        """\b(?:not you\?|if not you|if this was not|to block|to report|to dispute|""" +
            """call \d|sms block|helpline|customer care|do not share)""",
        RegexOption.IGNORE_CASE,
    )

    private val DATE_TOKEN = Regex(
        """\b(?:on|dt|date)\s*[:\-]?\s*""" +
            """(\d{1,2}[-/ ][A-Za-z]{3}[-/ ]\d{2,4}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|\d{2}[A-Za-z]{3}\d{2,4})""",
        RegexOption.IGNORE_CASE,
    )

    private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
        "d-M-yy", "d-M-yyyy", "d/M/yy", "d/M/yyyy",
        "d-MMM-yy", "d-MMM-yyyy", "d MMM yy", "d MMM yyyy",
        "d/MMM/yy", "d/MMM/yyyy", "ddMMMyy", "ddMMMyyyy",
    ).map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
    }

    // ---------------------------------------------------------------- entry point

    fun parse(
        sender: String,
        body: String,
        receivedAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ParseResult {
        if (SenderFilter.isBlocked(sender)) return ParseResult.Rejected("personal sender")
        if (body.isBlank()) return ParseResult.Rejected("empty body")

        if (REJECT_OTP.containsMatchIn(body)) return ParseResult.Rejected("otp")
        if (REJECT_PROMO.containsMatchIn(body)) return ParseResult.Rejected("promotional")
        if (REJECT_NOT_YET.containsMatchIn(body)) return ParseResult.Rejected("not a completed txn")
        if (REJECT_INFO_ONLY.containsMatchIn(body)) return ParseResult.Rejected("informational")

        val amounts = extractAmounts(body)
        if (amounts.isEmpty()) return ParseResult.Rejected("no amount")

        val txnAmount = amounts.firstOrNull { !it.isBalance }
            ?: return ParseResult.Rejected("only balance amounts")

        val direction = detectDirection(body)
            ?: return ParseResult.Rejected("no direction keyword")

        val senderClass = SenderFilter.classify(sender)
        val accountTail = extractAccountTail(body)

        // An unrecognised sender needs corroboration before we believe it. A real bank SMS
        // essentially always names the account or carries a reference number.
        val refNo = extractRef(body)

        if (senderClass == SenderFilter.SenderClass.UNKNOWN &&
            accountTail == null &&
            refNo == null
        ) {
            return ParseResult.Rejected("unknown sender, weak signal")
        }

        val merchantRaw = extractMerchant(body, direction)
        val balance = amounts.firstOrNull { it.isBalance }?.paise

        return ParseResult.Parsed(
            ParsedTxn(
                amountPaise = txnAmount.paise,
                direction = direction,
                channel = detectChannel(body),
                accountTail = accountTail,
                merchantRaw = merchantRaw,
                merchantNorm = Normalize.merchant(merchantRaw),
                vpaHandle = Normalize.vpaHandle(merchantRaw),
                refNo = refNo,
                balancePaise = balance,
                occurredAt = resolveOccurredAt(body, receivedAt, zone),
            ),
        )
    }

    // ---------------------------------------------------------------- helpers

    private data class AmountHit(val paise: Long, val start: Int, val isBalance: Boolean)

    /**
     * Finds every currency amount and marks which ones are balances.
     *
     * A "balance" is any amount whose immediately preceding text ends with a balance phrase. Doing
     * it positionally rather than with one big regex means "Rs.500 debited, Avl Bal Rs.12,300"
     * yields the spend first and the balance second regardless of template ordering.
     */
    private fun extractAmounts(body: String): List<AmountHit> {
        val hits = ArrayList<AmountHit>(4)

        /**
         * [digitsAt] is the offset of the number itself, not of the whole match. Using a single
         * consistent anchor lets the three patterns be de-duplicated against each other, and makes
         * the balance-context window identical regardless of which pattern found the amount.
         */
        fun add(valueText: String, digitsAt: Int) {
            if (hits.any { it.start == digitsAt }) return
            val paise = toPaise(valueText) ?: return
            val prefix = body.substring(maxOf(0, digitsAt - 32), digitsAt)
            hits.add(AmountHit(paise, digitsAt, BALANCE_CONTEXT.containsMatchIn(prefix)))
        }

        fun scan(pattern: Regex) {
            pattern.findAll(body).forEach { match ->
                val group = match.groups[1] ?: return@forEach
                add(group.value, group.range.first)
            }
        }

        scan(AMOUNT_PREFIXED)
        scan(AMOUNT_SUFFIXED)
        scan(AMOUNT_BARE)

        return hits.sortedBy { it.start }
    }

    private fun toPaise(text: String): Long? = try {
        BigDecimal(text.replace(",", ""))
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
            .takeIf { it > 0 }
    } catch (e: NumberFormatException) {
        null
    }

    /**
     * Whichever of debit/credit appears *earliest* wins.
     *
     * This matters for UPI templates like "Rs.500 debited from A/c XX1 and credited to abc@ybl" —
     * both words are present, but the one describing the user's own account comes first.
     */
    private fun detectDirection(body: String): Direction? {
        val masked = CREDIT_LIMIT.replace(CARD_PHRASE.replace(body) { "card" }) { "limit" }.lowercase()

        val debitAt = DEBIT_WORDS.mapNotNull { w -> masked.indexOf(w).takeIf { it >= 0 } }.minOrNull()
        val creditAt = CREDIT_WORDS.mapNotNull { w -> masked.indexOf(w).takeIf { it >= 0 } }.minOrNull()

        return when {
            debitAt == null && creditAt == null -> null
            creditAt == null -> Direction.DEBIT
            debitAt == null -> Direction.CREDIT
            debitAt <= creditAt -> Direction.DEBIT
            else -> Direction.CREDIT
        }
    }

    private fun extractAccountTail(body: String): String? {
        val labelled = ACCOUNT_LABELLED.find(body)?.groupValues?.get(1)
        val masked = ACCOUNT_MASKED.find(body)?.groupValues?.get(1)
        val digits = labelled ?: masked ?: return null
        return digits.takeLast(4)
    }

    private fun extractMerchant(body: String, direction: Direction): String? {
        val cut = TRAILER.find(body)?.range?.first ?: body.length
        val head = body.substring(0, cut)

        val patterns = if (direction == Direction.CREDIT) {
            MERCHANT_PATTERNS + MERCHANT_FROM
        } else {
            MERCHANT_PATTERNS
        }

        for (pattern in patterns) {
            val hit = pattern.find(head)?.groupValues?.get(1) ?: continue
            val cleaned = MERCHANT_LABEL.replace(hit.trim(), "").trim().trim('.', ',', '-', ' ')
            if (isPlausibleMerchant(cleaned)) return cleaned
        }
        return null
    }

    /**
     * Rejects captures that matched structurally but carry no identity — a bare amount, or a
     * fragment too short to be a name. Falling through to the next pattern beats recording
     * "Rs 99.00" as a merchant the user then has to categorise.
     */
    private fun isPlausibleMerchant(candidate: String): Boolean {
        if (candidate.length < 3) return false
        if (CURRENCY_ONLY.containsMatchIn(candidate)) return false
        if (OWN_ACCOUNT.containsMatchIn(candidate)) return false
        if (!THREE_LETTERS.containsMatchIn(candidate)) return false
        return Normalize.merchant(candidate).isNotEmpty()
    }

    /** Word-anchored so a merchant name containing "atm" doesn't turn a UPI payment into a withdrawal. */
    private val ATM_WORD = Regex("""\batm\b""", RegexOption.IGNORE_CASE)

    private fun detectChannel(body: String): Channel {
        val b = body.lowercase()
        return when {
            ATM_WORD.containsMatchIn(b) -> Channel.ATM
            b.contains("upi") || VPA_ANY.containsMatchIn(b) -> Channel.UPI
            b.contains("mandate") || b.contains("autopay") || b.contains("standing instruction") -> Channel.AUTOPAY
            b.contains("card") -> Channel.CARD
            b.contains("imps") -> Channel.IMPS
            b.contains("neft") -> Channel.NEFT
            b.contains("rtgs") -> Channel.RTGS
            b.contains("net banking") || b.contains("netbanking") -> Channel.NETBANKING
            b.contains("wallet") -> Channel.WALLET
            else -> Channel.UNKNOWN
        }
    }

    /**
     * Prefers the SMS arrival time, which carries a real clock reading.
     *
     * The in-body date only wins when it disagrees with the arrival date by more than a day — which
     * happens on delayed carrier delivery and on credit-card statements. In that case we have no
     * time-of-day, so noon is used as a neutral anchor.
     */
    private fun resolveOccurredAt(body: String, receivedAt: Long, zone: ZoneId): Long {
        val token = DATE_TOKEN.find(body)?.groupValues?.get(1) ?: return receivedAt
        val parsed = tryParseDate(token) ?: return receivedAt

        val receivedDate = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        val driftDays = abs(parsed.toEpochDay() - receivedDate.toEpochDay())

        // A future date is always a parse artefact (or a typo in the template). Never trust it.
        if (parsed.isAfter(receivedDate)) return receivedAt

        return if (driftDays > 1) {
            parsed.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        } else {
            receivedAt
        }
    }

    private fun tryParseDate(token: String): LocalDate? {
        val cleaned = token.trim()
        for (fmt in DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, fmt)
            } catch (e: Exception) {
                // Wrong format for this token; try the next one.
            }
        }
        return null
    }
}
