package com.deepak.umber.data.repo

import com.deepak.umber.data.db.AppDatabase
import com.deepak.umber.data.db.AccountTotal
import com.deepak.umber.data.db.CategoryTotal
import com.deepak.umber.data.model.Categories
import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.db.TxnWithSource
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Direction
import com.deepak.umber.ingest.IngestPipeline
import com.deepak.umber.io.ImportReport
import com.deepak.umber.io.LedgerCsv
import com.deepak.umber.io.StatementImporter
import com.deepak.umber.ml.Classifier
import com.deepak.umber.ml.ModelStats
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The three rolling windows the widget and home screen are built around.
 *
 * Rolling, not calendar-aligned: "last 24 hours" is more useful for in-the-moment awareness than
 * "today", which resets to zero at midnight exactly when you stop being able to change the outcome.
 */
enum class SpendWindow(val label: String, val durationMs: Long) {
    LAST_24H("Last 24 hours", 24 * 60 * 60 * 1000L),
    LAST_7D("Last 7 days", 7 * 24 * 60 * 60 * 1000L),
    LAST_30D("Last 30 days", 30L * 24 * 60 * 60 * 1000L),
}

data class WindowSummary(
    val window: SpendWindow,
    /** What the spending actually cost, after reimbursements. This is the headline figure. */
    val spentPaise: Long,
    /** Everything paid out, before reimbursements. */
    val grossSpentPaise: Long,
    /** The part of [grossSpentPaise] that came back from someone you had paid. */
    val reimbursedPaise: Long,
    /** All money in, including salary. */
    val receivedPaise: Long,
    /** The part of [receivedPaise] that is genuine income rather than money coming back. */
    val incomePaise: Long,
    val txnCount: Int,
    val topCategory: CategoryTotal?,
)

class UmberRepository(
    private val db: AppDatabase,
    private val classifier: Classifier,
    private val ingest: IngestPipeline,
) {

    // ------------------------------------------------------------------ reads

    fun recentTxns(limit: Int = 100): Flow<List<TxnEntity>> = db.txns().recent(limit)

    fun history(query: String, limit: Int = 500): Flow<List<TxnWithSource>> =
        db.txns().search(query.trim(), limit)

    fun needingReview(limit: Int = 200): Flow<List<TxnEntity>> = db.txns().needingReview(limit)

    fun needingReviewCount(): Flow<Int> = db.txns().needingReviewCount()

    fun confirmedCount(): Flow<Int> = db.txns().confirmedCount()

    fun knownMerchantCount(): Flow<Int> = db.merchantMemory().count()

    fun messageCount(): Flow<Int> = db.rawMessages().countAll()

    fun rejectBreakdown(): Flow<List<CategoryTotal>> = db.rawMessages().rejectBreakdown()

    /**
     * Runs reimbursement netting for a window.
     *
     * Kept private and computed on demand rather than stored: it depends on the window boundaries,
     * which move continuously, so any cached value would be stale within minutes.
     */
    private suspend fun netting(from: Long, to: Long): NettingResult {
        val debits = db.txns().debitsByMerchant(from, to)
            .map { MerchantDebit(it.merchantNorm, it.category, it.paise) }
        val refunds = db.txns().refundsByMerchant(from, to, Categories.INCOME)
            .map { MerchantCredit(it.merchantNorm, it.paise) }
        return Netting.apply(debits, refunds)
    }

    suspend fun summary(window: SpendWindow, now: Long = System.currentTimeMillis()): WindowSummary {
        val from = now - window.durationMs
        val net = netting(from, now)

        val top = net.netByCategory
            .filterValues { it > 0 }
            .maxByOrNull { it.value }
            ?.let { CategoryTotal(it.key, it.value, 0) }

        return WindowSummary(
            window = window,
            spentPaise = net.netPaise,
            grossSpentPaise = net.grossPaise,
            reimbursedPaise = net.reimbursedPaise,
            receivedPaise = db.txns().sumIn(Direction.CREDIT, from, now),
            incomePaise = db.txns().sumCategoryIn(Direction.CREDIT, Categories.INCOME, from, now),
            txnCount = db.txns().countIn(Direction.DEBIT, from, now),
            topCategory = top,
        )
    }

    /** Spend per account or card, biggest first, for the window. */
    suspend fun accountTotals(
        window: SpendWindow,
        now: Long = System.currentTimeMillis(),
    ): List<AccountTotal> = db.txns().spendByAccount(now - window.durationMs, now)

    suspend fun allSummaries(now: Long = System.currentTimeMillis()): List<WindowSummary> =
        SpendWindow.entries.map { summary(it, now) }

    /** Category breakdown, net of reimbursements so it reconciles with the headline figure. */
    suspend fun topCategories(
        window: SpendWindow,
        limit: Int = 5,
        now: Long = System.currentTimeMillis(),
    ): List<CategoryTotal> {
        val from = now - window.durationMs
        val counts = db.txns().topCategories(Direction.DEBIT, from, now, Int.MAX_VALUE)
            .associate { it.category to it.txnCount }

        return netting(from, now).netByCategory
            .filterValues { it > 0 }
            .map { (category, paise) -> CategoryTotal(category, paise, counts[category] ?: 0) }
            .sortedByDescending { it.totalPaise }
            .take(limit)
    }

    /**
     * Daily spend for the last [days] days, oldest first, gap-filled with zeroes.
     *
     * The gap-fill matters for the sparkline: SQL only returns days that had transactions, so
     * plotting the raw rows would silently compress quiet stretches and misrepresent the shape.
     */
    suspend fun dailySeries(
        days: Int = 30,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.minusDays((days - 1).toLong())
        val fromMs = start.atStartOfDay(zone).toInstant().toEpochMilli()

        val rows = db.txns().dailyTotals(Direction.DEBIT, fromMs, now)
        val byDay = rows.associate { it.day to it.totalPaise }

        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        return (0 until days).map { offset ->
            val day: LocalDate = start.plusDays(offset.toLong())
            byDay[day.format(fmt)] ?: 0L
        }
    }

    suspend fun modelStats(): ModelStats = classifier.stats()

    // ----------------------------------------------------------------- writes

    /**
     * Records a user's category decision — the only place ground truth enters the system.
     *
     * Order matters: the row is updated first so the UI reacts immediately, then the classifier
     * learns. [applyToPastFromSameMerchant] retro-labels history, which is what makes categorising
     * one Swiggy charge feel like it fixed all of them.
     */
    suspend fun confirmCategory(
        txn: TxnEntity,
        category: String,
        applyToPastFromSameMerchant: Boolean = true,
    ) {
        db.txns().updateCategory(
            id = txn.id,
            category = category,
            source = CategorySource.USER,
            confidence = 1f,
            needsReview = false,
        )

        val merchant = txn.merchantNorm
        if (applyToPastFromSameMerchant && !merchant.isNullOrBlank()) {
            db.txns().relabelMerchant(merchant, category)
        }

        // Re-read so the classifier trains on the row as it now stands.
        val updated = db.txns().byId(txn.id) ?: txn.copy(category = category)
        classifier.learn(updated, category)
    }

    suspend fun retrainModel(): ModelStats = classifier.retrainFromScratch()

    suspend fun reparseRejected(): Int = ingest.reparseRejected()

    // -------------------------------------------------------------- csv / files

    suspend fun exportLedgerCsv(): String = LedgerCsv.export(db.txns().allForExport())

    suspend fun importLedgerCsv(text: String, origin: String): ImportReport =
        LedgerCsv.import(text, origin, ingest)

    suspend fun importStatement(bytes: ByteArray, origin: String): ImportReport =
        StatementImporter(ingest).import(bytes, origin)

    suspend fun rebuildLedger(): Int = ingest.rebuildFromRawMessages()

    fun transactionCount(): Flow<Int> = db.txns().totalCount()
}
