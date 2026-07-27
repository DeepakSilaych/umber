package com.deepak.umber.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Direction
import kotlinx.coroutines.flow.Flow

data class CategoryTotal(val category: String, val totalPaise: Long, val txnCount: Int)

/**
 * A transaction plus where it came from.
 *
 * `source` is nullable because a rebuild drops the stored rows for file imports, leaving those
 * transactions without a parent message. The UI shows "imported" in that case rather than pretending
 * the provenance is unknown.
 */
data class TxnWithSource(
    @Embedded val txn: TxnEntity,
    val source: String?,
)

data class DayTotal(val day: String, val totalPaise: Long)

data class MerchantDebitRow(val merchantNorm: String?, val category: String, val paise: Long)

data class MerchantCreditRow(val merchantNorm: String, val paise: Long)

/** Spend for one account or card, identified by its masked tail. */
data class AccountTotal(
    val accountTail: String?,
    val totalPaise: Long,
    val txnCount: Int,
    /** How many of those went through a card, which is what distinguishes a card from an account. */
    val cardCount: Int,
)

@Dao
interface RawMessageDao {

    /** IGNORE, not REPLACE: the unique fingerprint index makes re-ingest a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(msg: RawMessageEntity): Long

    @Query("SELECT * FROM raw_message WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun byFingerprint(fingerprint: String): RawMessageEntity?

    @Query("UPDATE raw_message SET parsed = :parsed, rejectReason = :reason, parserVersion = :version WHERE id = :id")
    suspend fun markProcessed(id: Long, parsed: Boolean, reason: String?, version: Int)

    /**
     * Messages a newer parser might now understand.
     *
     * Restricted to previous *rejects*. Re-parsing an already-extracted message would mean deleting
     * and recreating its transaction row, which would throw away any category the user had
     * corrected on it — a bad trade for a marginally better merchant string.
     */
    @Query("SELECT * FROM raw_message WHERE parsed = 0 AND parserVersion < :version ORDER BY receivedAt ASC")
    suspend fun rejectedNeedingReparse(version: Int): List<RawMessageEntity>

    /** Marks already-extracted rows as seen by the new parser so they aren't rescanned. */
    @Query("UPDATE raw_message SET parserVersion = :version WHERE parsed = 1 AND parserVersion < :version")
    suspend fun bumpParsedVersion(version: Int)

    @Query("SELECT * FROM raw_message ORDER BY receivedAt ASC")
    suspend fun all(): List<RawMessageEntity>

    @Query("UPDATE raw_message SET parsed = 0, rejectReason = NULL, parserVersion = 0")
    suspend fun resetProcessing()

    /**
     * Drops the stored rows for file imports.
     *
     * Their bodies are flattened spreadsheet lines, not something the SMS parser can replay, so a
     * rebuild cannot regenerate their transactions. Leaving the rows behind would be worse than
     * deleting them: the unique fingerprint index would then reject the very re-import needed to
     * restore that data, losing it permanently.
     */
    @Query("DELETE FROM raw_message WHERE source IN ('STATEMENT', 'LEDGER_CSV')")
    suspend fun deleteFileImports(): Int

    @Query("SELECT COUNT(*) FROM raw_message")
    fun countAll(): Flow<Int>

    /** COALESCE keeps the projected column non-null so it maps onto CategoryTotal.category. */
    @Query(
        """
        SELECT COALESCE(rejectReason, 'unknown') AS category,
               0 AS totalPaise,
               COUNT(*) AS txnCount
        FROM raw_message
        WHERE parsed = 0 AND rejectReason IS NOT NULL
        GROUP BY rejectReason
        ORDER BY txnCount DESC
        """,
    )
    fun rejectBreakdown(): Flow<List<CategoryTotal>>
}

@Dao
interface TxnDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: TxnEntity): Long

    /**
     * Time-window duplicate check for messages with no reference number.
     *
     * One UPI payment routinely produces both a bank SMS and a payment-app notification. They agree
     * on amount, direction and account tail but arrive seconds apart, so an exact-timestamp match
     * would never catch them.
     */
    @Query(
        """
        SELECT * FROM txn
        WHERE amountPaise = :amountPaise
          AND direction = :direction
          AND (:accountTail IS NULL OR accountTail IS NULL OR accountTail = :accountTail)
          AND occurredAt BETWEEN :from AND :to
        LIMIT 1
        """,
    )
    suspend fun findNearDuplicate(
        amountPaise: Long,
        direction: Direction,
        accountTail: String?,
        from: Long,
        to: Long,
    ): TxnEntity?

    @Query("SELECT * FROM txn WHERE refNo = :refNo LIMIT 1")
    suspend fun byRefNo(refNo: String): TxnEntity?

    /**
     * How many transactions already match this amount/direction/account on this calendar day.
     *
     * Used to reconcile date-only statement rows against SMS. A window comparison is useless there
     * — a statement row's timestamp is a fabricated midnight — so matching is done per day, and the
     * *count* is what distinguishes "already imported" from "genuinely paid twice today".
     */
    @Query(
        """
        SELECT COUNT(*) FROM txn
        WHERE amountPaise = :amountPaise
          AND direction = :direction
          AND (:accountTail IS NULL OR accountTail IS NULL OR accountTail = :accountTail)
          AND occurredAt BETWEEN :dayStart AND :dayEnd
        """,
    )
    suspend fun countSameDay(
        amountPaise: Long,
        direction: Direction,
        accountTail: String?,
        dayStart: Long,
        dayEnd: Long,
    ): Int

    @Query("SELECT * FROM txn ORDER BY occurredAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<TxnEntity>>

    /**
     * History with free-text search across merchant and category.
     *
     * An empty query returns everything, so the caller doesn't need a second query for the
     * unfiltered case. LIKE is case-insensitive for ASCII in SQLite, which is all merchant strings
     * ever are after normalisation.
     */
    @Query(
        """
        SELECT t.*, r.source AS source
        FROM txn t
        LEFT JOIN raw_message r ON r.id = t.rawMessageId
        WHERE :query = ''
           OR t.merchantNorm LIKE '%' || :query || '%'
           OR t.merchantRaw LIKE '%' || :query || '%'
           OR t.category LIKE '%' || :query || '%'
        ORDER BY t.occurredAt DESC
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int): Flow<List<TxnWithSource>>

    @Query("SELECT * FROM txn WHERE needsReview = 1 ORDER BY occurredAt DESC LIMIT :limit")
    fun needingReview(limit: Int): Flow<List<TxnEntity>>

    @Query("SELECT COUNT(*) FROM txn WHERE needsReview = 1")
    fun needingReviewCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(amountPaise), 0) FROM txn WHERE direction = :direction AND occurredAt BETWEEN :from AND :to")
    suspend fun sumIn(direction: Direction, from: Long, to: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(amountPaise), 0) FROM txn
        WHERE direction = :direction AND category = :category AND occurredAt BETWEEN :from AND :to
        """,
    )
    suspend fun sumCategoryIn(direction: Direction, category: String, from: Long, to: Long): Long

    @Query("SELECT COUNT(*) FROM txn WHERE direction = :direction AND occurredAt BETWEEN :from AND :to")
    suspend fun countIn(direction: Direction, from: Long, to: Long): Int

    @Query(
        """
        SELECT category, COALESCE(SUM(amountPaise), 0) AS totalPaise, COUNT(*) AS txnCount
        FROM txn
        WHERE direction = :direction AND occurredAt BETWEEN :from AND :to
        GROUP BY category
        ORDER BY totalPaise DESC
        LIMIT :limit
        """,
    )
    suspend fun topCategories(direction: Direction, from: Long, to: Long, limit: Int): List<CategoryTotal>

    /**
     * Per-day totals in the device's local timezone.
     *
     * `occurredAt` is epoch millis, so it is divided to seconds before `unixepoch`; `localtime`
     * makes the day boundaries match what the user actually experiences as "a day".
     */
    @Query(
        """
        SELECT strftime('%Y-%m-%d', occurredAt / 1000, 'unixepoch', 'localtime') AS day,
               COALESCE(SUM(amountPaise), 0) AS totalPaise
        FROM txn
        WHERE direction = :direction AND occurredAt BETWEEN :from AND :to
        GROUP BY day
        ORDER BY day ASC
        """,
    )
    suspend fun dailyTotals(direction: Direction, from: Long, to: Long): List<DayTotal>

    /** Outgoing money grouped by counterparty and category, for reimbursement netting. */
    @Query(
        """
        SELECT merchantNorm, category, COALESCE(SUM(amountPaise), 0) AS paise
        FROM txn
        WHERE direction = 'DEBIT' AND occurredAt BETWEEN :from AND :to
        GROUP BY merchantNorm, category
        """,
    )
    suspend fun debitsByMerchant(from: Long, to: Long): List<MerchantDebitRow>

    /**
     * Incoming money grouped by counterparty, eligible to offset spending.
     *
     * The real safeguard against a paycheque erasing expenses is the *merchant match* — money can
     * only offset money paid to that same counterparty, and nobody pays their employer. So only
     * credits the user has **deliberately** filed as income are excluded here.
     *
     * Excluding everything categorised Income would break the feature entirely: unclassified
     * credits default to Income, so a friend repaying you would be filtered out before it could
     * ever net.
     *
     * Rows without a merchant are dropped — they can't be matched to anyone.
     */
    @Query(
        """
        SELECT merchantNorm, COALESCE(SUM(amountPaise), 0) AS paise
        FROM txn
        WHERE direction = 'CREDIT'
          AND NOT (category = :incomeCategory AND categorySource IN ('USER', 'MEMORY'))
          AND merchantNorm IS NOT NULL AND merchantNorm <> ''
          AND occurredAt BETWEEN :from AND :to
        GROUP BY merchantNorm
        """,
    )
    suspend fun refundsByMerchant(from: Long, to: Long, incomeCategory: String): List<MerchantCreditRow>

    /** Spend per account/card in a window, biggest first. */
    @Query(
        """
        SELECT accountTail,
               COALESCE(SUM(amountPaise), 0) AS totalPaise,
               COUNT(*) AS txnCount,
               COALESCE(SUM(CASE WHEN channel = 'CARD' THEN 1 ELSE 0 END), 0) AS cardCount
        FROM txn
        WHERE direction = 'DEBIT' AND occurredAt BETWEEN :from AND :to
        GROUP BY accountTail
        ORDER BY totalPaise DESC
        """,
    )
    suspend fun spendByAccount(from: Long, to: Long): List<AccountTotal>

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun byId(id: Long): TxnEntity?

    @Query(
        """
        UPDATE txn
        SET category = :category, categorySource = :source, confidence = :confidence, needsReview = :needsReview
        WHERE id = :id
        """,
    )
    suspend fun updateCategory(
        id: Long,
        category: String,
        source: CategorySource,
        confidence: Float,
        needsReview: Boolean,
    )

    /**
     * Retro-applies a confirmed category to every past transaction from the same merchant.
     *
     * `categorySource != 'USER'` protects rows the user has already decided on — one
     * "apply to all" must never silently overwrite an earlier, deliberate choice.
     */
    @Query(
        """
        UPDATE txn
        SET category = :category, categorySource = 'MEMORY', confidence = 1.0, needsReview = 0
        WHERE merchantNorm = :merchantNorm AND categorySource != 'USER'
        """,
    )
    suspend fun relabelMerchant(merchantNorm: String, category: String): Int

    /** Replay buffer for online learning — see Classifier.learn. */
    @Query("SELECT * FROM txn WHERE categorySource = 'USER' ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomConfirmed(limit: Int): List<TxnEntity>

    @Query("SELECT * FROM txn WHERE categorySource = 'USER' ORDER BY occurredAt ASC")
    suspend fun allConfirmed(): List<TxnEntity>

    @Query("SELECT COUNT(*) FROM txn WHERE categorySource = 'USER'")
    fun confirmedCount(): Flow<Int>

    /** Whole ledger, oldest first, for CSV export. */
    @Query("SELECT * FROM txn ORDER BY occurredAt ASC")
    suspend fun allForExport(): List<TxnEntity>

    @Query("SELECT COUNT(*) FROM txn")
    fun totalCount(): Flow<Int>

    @Query("DELETE FROM txn")
    suspend fun deleteAll()
}

@Dao
interface MerchantMemoryDao {

    @Query("SELECT * FROM merchant_memory WHERE merchantNorm = :merchantNorm LIMIT 1")
    suspend fun get(merchantNorm: String): MerchantMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: MerchantMemoryEntity)

    @Query("SELECT COUNT(*) FROM merchant_memory")
    fun count(): Flow<Int>

    @Query("DELETE FROM merchant_memory WHERE merchantNorm = :merchantNorm")
    suspend fun forget(merchantNorm: String)
}

@Dao
interface ModelStateDao {

    @Query("SELECT * FROM model_state WHERE id = 0 LIMIT 1")
    suspend fun get(): ModelStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: ModelStateEntity)

    @Query("DELETE FROM model_state")
    suspend fun clear()
}

@Dao
interface LastLocationDao {

    @Query("SELECT * FROM last_location WHERE id = 0 LIMIT 1")
    suspend fun get(): LastLocationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(fix: LastLocationEntity)
}
