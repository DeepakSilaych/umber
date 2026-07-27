package com.deepak.umber.ingest

import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import com.deepak.umber.parse.Normalize
import com.deepak.umber.parse.ParsedTxn

/**
 * A transaction observation, independent of where it came from.
 *
 * SMS, statement rows and ledger-CSV rows all normalise to this so that dedupe, classification and
 * persistence have exactly one implementation. Anything source-specific has to be resolved before
 * this point.
 */
data class TxnRecord(
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

    /**
     * False when the source gave a date but no clock time — every statement row, and CSV exports
     * from other tools.
     *
     * This is what selects the dedupe strategy: a date-only row cannot be matched against an SMS
     * by a minutes-wide time window, because its timestamp is a fabricated midnight.
     */
    val hasExactTime: Boolean,

    /** Preset category, used only by ledger-CSV round-trips. Null means "classify it". */
    val presetCategory: String? = null,
    val presetCategorySource: CategorySource? = null,
) {
    companion object {
        fun from(parsed: ParsedTxn): TxnRecord = TxnRecord(
            amountPaise = parsed.amountPaise,
            direction = parsed.direction,
            channel = parsed.channel,
            accountTail = parsed.accountTail,
            merchantRaw = parsed.merchantRaw,
            merchantNorm = parsed.merchantNorm,
            vpaHandle = parsed.vpaHandle,
            // Normalised here too, so an SMS reference and the same reference embedded in a
            // statement narration collapse to one key.
            refNo = RefKey.normalize(parsed.refNo),
            balancePaise = parsed.balancePaise,
            occurredAt = parsed.occurredAt,
            hasExactTime = true,
        )
    }
}

/**
 * Canonical form of a reference number for cross-source matching.
 *
 * The same UPI RRN is written `UPI:512345678902` in an ICICI SMS and `UPI/512345678902/PAYMENT`
 * in the statement narration for that transaction. Stripping to bare alphanumerics and upper-casing
 * is what lets the two recognise each other — without it, importing a statement would silently
 * double every transaction already captured from SMS.
 */
object RefKey {
    private val NON_ALNUM = Regex("""[^A-Za-z0-9]""")

    fun normalize(ref: String?): String? {
        if (ref.isNullOrBlank()) return null
        val key = NON_ALNUM.replace(ref, "").uppercase()
        return key.takeIf { it.length >= 6 && it.count { c -> c.isDigit() } >= 4 }
    }
}

/** Convenience for building a record from statement/CSV fields. */
fun txnRecord(
    amountPaise: Long,
    direction: Direction,
    occurredAt: Long,
    hasExactTime: Boolean,
    merchantRaw: String?,
    refNo: String?,
    accountTail: String? = null,
    channel: Channel = Channel.UNKNOWN,
    balancePaise: Long? = null,
    presetCategory: String? = null,
    presetCategorySource: CategorySource? = null,
): TxnRecord = TxnRecord(
    amountPaise = amountPaise,
    direction = direction,
    channel = channel,
    accountTail = accountTail,
    merchantRaw = merchantRaw,
    merchantNorm = Normalize.merchant(merchantRaw),
    vpaHandle = Normalize.vpaHandle(merchantRaw),
    refNo = RefKey.normalize(refNo),
    balancePaise = balancePaise,
    occurredAt = occurredAt,
    hasExactTime = hasExactTime,
    presetCategory = presetCategory,
    presetCategorySource = presetCategorySource,
)
