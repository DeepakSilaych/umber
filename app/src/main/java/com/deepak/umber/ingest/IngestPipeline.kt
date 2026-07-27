package com.deepak.umber.ingest

import com.deepak.umber.data.db.AppDatabase
import com.deepak.umber.data.db.RawMessageEntity
import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.SourceType
import com.deepak.umber.location.LocationCache
import com.deepak.umber.ml.ClassifyInput
import com.deepak.umber.ml.Classifier
import com.deepak.umber.parse.MessageFingerprint
import com.deepak.umber.parse.ParseResult
import com.deepak.umber.parse.SenderFilter
import com.deepak.umber.parse.SmsParser
import java.time.Instant
import java.time.ZoneId

sealed interface IngestOutcome {
    data class Inserted(val txnId: Long) : IngestOutcome
    data object DuplicateMessage : IngestOutcome
    data object DuplicateTransaction : IngestOutcome
    data class Rejected(val reason: String) : IngestOutcome
}

/**
 * Tracks how many times a given date-only signature has been seen *within one import run*.
 *
 * Without this, importing a statement containing three identical ₹500 mandate debits on one day
 * would insert the first and discard the other two as duplicates of it. With it, the Nth identical
 * row is only skipped when at least N such transactions already exist — which also makes
 * re-importing the same file a clean no-op.
 */
class ImportSession {
    private val seen = HashMap<String, Int>()

    fun occurrence(key: String): Int {
        val next = (seen[key] ?: 0) + 1
        seen[key] = next
        return next
    }
}

/**
 * The single path from "an observation arrived" to "a categorised row exists".
 *
 * Live SMS, historical SMS backfill, bank-statement CSV and ledger-CSV restore all funnel through
 * here, so deduplication and classification behave identically regardless of origin. That matters
 * most for the mixed case the user actually hits: importing a statement that overlaps months of
 * SMS already captured.
 */
class IngestPipeline(
    private val db: AppDatabase,
    private val classifier: Classifier,
    private val locationCache: LocationCache,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    // ------------------------------------------------------------------- SMS

    suspend fun ingest(
        source: SourceType,
        sender: String,
        body: String,
        receivedAt: Long,
        session: ImportSession = ImportSession(),
    ): IngestOutcome {
        // Personal SMS is never persisted, not even as a rejected raw message. Storing a friend's
        // texts in order to decide they aren't transactions is a worse privacy trade than losing
        // the occasional edge case.
        if (SenderFilter.isBlocked(sender)) return IngestOutcome.Rejected("personal sender")

        val rawId = db.rawMessages().insert(
            RawMessageEntity(
                source = source,
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                fingerprint = MessageFingerprint.of(sender, body, receivedAt),
                parsed = false,
                rejectReason = null,
                parserVersion = 0,
            ),
        )
        if (rawId == -1L) return IngestOutcome.DuplicateMessage

        return when (val result = SmsParser.parse(sender, body, receivedAt)) {
            is ParseResult.Rejected -> {
                db.rawMessages().markProcessed(rawId, false, result.reason, SmsParser.VERSION)
                IngestOutcome.Rejected(result.reason)
            }

            is ParseResult.Parsed ->
                persist(rawId, TxnRecord.from(result.txn), body, session)
        }
    }

    // ------------------------------------------------- statements / ledger CSV

    /**
     * Ingests an already-structured observation (statement row, ledger CSV row).
     *
     * [rawText] is the original line, stored verbatim so an improved importer can be replayed the
     * same way `raw_message` lets a new SMS parser be replayed.
     */
    suspend fun ingestRecord(
        source: SourceType,
        origin: String,
        rawText: String,
        record: TxnRecord,
        session: ImportSession,
    ): IngestOutcome {
        val rawId = db.rawMessages().insert(
            RawMessageEntity(
                source = source,
                sender = origin,
                body = rawText,
                receivedAt = record.occurredAt,
                fingerprint = MessageFingerprint.of(origin, rawText, record.occurredAt),
                parsed = false,
                rejectReason = null,
                parserVersion = 0,
            ),
        )
        if (rawId == -1L) return IngestOutcome.DuplicateMessage

        return persist(rawId, record, rawText, session)
    }

    // --------------------------------------------------------------- shared

    private suspend fun persist(
        rawId: Long,
        record: TxnRecord,
        rawText: String,
        session: ImportSession,
    ): IngestOutcome {
        if (isDuplicate(record, session)) {
            db.rawMessages().markProcessed(rawId, false, "duplicate txn", SmsParser.VERSION)
            return IngestOutcome.DuplicateTransaction
        }

        val now = System.currentTimeMillis()

        // A statement row describes something that happened days ago; attaching today's location
        // to it would be actively misleading, so enrichment is limited to live observations.
        val location = if (record.hasExactTime) locationCache.snapshot(now) else LocationCache.EMPTY

        val category: String
        val categorySource: CategorySource
        val confidence: Float
        val needsReview: Boolean

        if (record.presetCategory != null) {
            category = record.presetCategory
            categorySource = record.presetCategorySource ?: CategorySource.USER
            confidence = 1f
            needsReview = false
        } else {
            val prediction = classifier.classify(
                ClassifyInput(
                    merchantNorm = record.merchantNorm,
                    vpaHandle = record.vpaHandle,
                    channel = record.channel,
                    direction = record.direction,
                    amountPaise = record.amountPaise,
                    occurredAt = record.occurredAt,
                    rawBody = rawText,
                ),
            )
            category = prediction.category
            categorySource = prediction.source
            confidence = prediction.confidence
            needsReview = prediction.needsReview
        }

        val txnId = db.txns().insert(
            TxnEntity(
                rawMessageId = rawId,
                amountPaise = record.amountPaise,
                direction = record.direction,
                channel = record.channel,
                accountTail = record.accountTail,
                merchantRaw = record.merchantRaw,
                merchantNorm = record.merchantNorm.takeIf { it.isNotBlank() },
                vpaHandle = record.vpaHandle,
                refNo = record.refNo,
                balancePaise = record.balancePaise,
                occurredAt = record.occurredAt,
                category = category,
                categorySource = categorySource,
                confidence = confidence,
                needsReview = needsReview,
                lat = location.lat,
                lon = location.lon,
                locAccuracyM = location.accuracyM,
                locAgeMs = location.ageMs,
                locConfidence = location.confidence,
                parserVersion = SmsParser.VERSION,
                createdAt = now,
            ),
        )

        // -1 means the unique refNo index rejected it — a duplicate that slipped past the
        // pre-check because of a race between concurrent ingests.
        if (txnId == -1L) {
            db.rawMessages().markProcessed(rawId, false, "duplicate refno", SmsParser.VERSION)
            return IngestOutcome.DuplicateTransaction
        }

        db.rawMessages().markProcessed(rawId, true, null, SmsParser.VERSION)

        // A restored ledger row carries the user's own decision; feed it back so a rebuilt install
        // recovers its merchant memory and model rather than starting cold.
        if (record.presetCategorySource == CategorySource.USER) {
            db.txns().byId(txnId)?.let { classifier.learn(it, category) }
        }

        return IngestOutcome.Inserted(txnId)
    }

    /**
     * Three-tier duplicate detection, chosen by what the source actually knows.
     *
     *  1. **Reference number** — authoritative in both directions. A match means duplicate; an
     *     unseen reference is positive proof of a *distinct* transaction and short-circuits the
     *     fuzzy checks entirely. This is what makes statement-versus-SMS reconciliation reliable,
     *     since both carry the same UPI RRN.
     *  2. **Time window** (live sources) — one payment produces both a bank SMS and a payment-app
     *     notification seconds apart.
     *  3. **Same-day occurrence count** (date-only sources) — see [ImportSession].
     */
    private suspend fun isDuplicate(record: TxnRecord, session: ImportSession): Boolean {
        record.refNo?.let { ref ->
            return db.txns().byRefNo(ref) != null
        }

        if (record.hasExactTime) {
            return db.txns().findNearDuplicate(
                amountPaise = record.amountPaise,
                direction = record.direction,
                accountTail = record.accountTail,
                from = record.occurredAt - DEDUPE_WINDOW_MS,
                to = record.occurredAt + DEDUPE_WINDOW_MS,
            ) != null
        }

        val day = Instant.ofEpochMilli(record.occurredAt).atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        val key = "$day|${record.amountPaise}|${record.direction}|${record.accountTail ?: "-"}"
        val needed = session.occurrence(key)
        val existing = db.txns().countSameDay(
            amountPaise = record.amountPaise,
            direction = record.direction,
            accountTail = record.accountTail,
            dayStart = dayStart,
            dayEnd = dayEnd,
        )

        return existing >= needed
    }

    /**
     * Re-runs the current parser over messages a previous version rejected.
     *
     * Only rejects are replayed — see `RawMessageDao.rejectedNeedingReparse` for why already-parsed
     * rows are left alone. Returns the number of transactions recovered.
     */
    suspend fun reparseRejected(): Int {
        val session = ImportSession()
        var recovered = 0

        for (msg in db.rawMessages().rejectedNeedingReparse(SmsParser.VERSION)) {
            if (msg.source == SourceType.STATEMENT || msg.source == SourceType.LEDGER_CSV) continue

            when (val result = SmsParser.parse(msg.sender, msg.body, msg.receivedAt)) {
                is ParseResult.Rejected ->
                    db.rawMessages().markProcessed(msg.id, false, result.reason, SmsParser.VERSION)

                is ParseResult.Parsed ->
                    if (persist(msg.id, TxnRecord.from(result.txn), msg.body, session) is IngestOutcome.Inserted) {
                        recovered++
                    }
            }
        }

        db.rawMessages().bumpParsedVersion(SmsParser.VERSION)
        return recovered
    }

    /**
     * Deletes every transaction and re-derives them from the stored raw messages.
     *
     * This is the repair path for rows extracted by an older parser: [reparseRejected] deliberately
     * leaves already-parsed transactions alone, so a bug like the "MANDATE" reference collision
     * stays baked into them forever otherwise.
     *
     * It is far less destructive than it looks. `merchant_memory` and the model live in separate
     * tables and are untouched, so every category the user confirmed is re-applied automatically by
     * the classifier's first layer as the rebuild proceeds.
     *
     * File imports cannot be replayed — their stored text is a flattened spreadsheet row, not an
     * SMS — so their stored rows are dropped outright. That deletion is what makes re-importing the
     * file work: keeping them would leave the fingerprint index rejecting the re-import and the
     * data gone for good.
     *
     * Returns the number of transactions rebuilt.
     */
    suspend fun rebuildFromRawMessages(): Int {
        db.txns().deleteAll()
        db.rawMessages().deleteFileImports()
        db.rawMessages().resetProcessing()

        val session = ImportSession()
        var rebuilt = 0

        for (msg in db.rawMessages().all()) {
            if (msg.source == SourceType.STATEMENT || msg.source == SourceType.LEDGER_CSV) continue

            when (val result = SmsParser.parse(msg.sender, msg.body, msg.receivedAt)) {
                is ParseResult.Rejected ->
                    db.rawMessages().markProcessed(msg.id, false, result.reason, SmsParser.VERSION)

                is ParseResult.Parsed ->
                    if (persist(msg.id, TxnRecord.from(result.txn), msg.body, session) is IngestOutcome.Inserted) {
                        rebuilt++
                    }
            }
        }

        return rebuilt
    }

    companion object {
        /**
         * Half-width of the dedupe window. Three minutes comfortably covers the lag between a bank
         * SMS and the corresponding payment-app notification without merging two genuinely separate
         * payments of the same amount.
         */
        private const val DEDUPE_WINDOW_MS = 3 * 60_000L
    }
}
