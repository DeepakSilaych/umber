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
import com.deepak.umber.remote.RemoteTxn
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * The periods the widget and home screen are built around.
 *
 * Calendar-aligned apart from the first, because "this week" and "last week" are how people
 * actually reason about their own spending — a rolling 7-day figure silently reshuffles its
 * boundaries every day and can never be compared against anything.
 *
 * Weeks start Monday (ISO), which matches [java.time.DayOfWeek] ordering and Indian convention.
 * [LAST_24H] stays rolling on purpose: it answers "what have I just spent", which a figure that
 * resets at midnight cannot.
 */
enum class SpendWindow(val label: String, val shortLabel: String) {
    LAST_24H("Last 24 hours", "Last 24h"),
    THIS_WEEK("This week", "This week"),
    LAST_WEEK("Last week", "Last week"),
    THIS_MONTH("This month", "This month"),
    LAST_MONTH("Last month", "Last month"),
    ;

    /**
     * Inclusive start and end instants for this period, evaluated against [now].
     *
     * Completed periods end at the instant the next one begins, minus a millisecond — anything
     * looser would double-count a transaction that lands exactly on a boundary.
     */
    fun range(now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun startOf(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

        return when (this) {
            LAST_24H -> (now - 24 * 60 * 60 * 1000L) to now

            THIS_WEEK -> startOf(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) to now

            LAST_WEEK -> {
                val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                startOf(thisMonday.minusWeeks(1)) to (startOf(thisMonday) - 1)
            }

            THIS_MONTH -> startOf(today.withDayOfMonth(1)) to now

            LAST_MONTH -> {
                val firstOfThis = today.withDayOfMonth(1)
                startOf(firstOfThis.minusMonths(1)) to (startOf(firstOfThis) - 1)
            }
        }
    }
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

/**
 * Everything the home-screen widget renders, gathered in one call.
 *
 * Assembled in the repository rather than the widget so `provideGlance` has a single failure point
 * to guard: a query that throws there leaves a permanently broken widget on the home screen with no
 * obvious way to retry.
 */
data class WidgetSnapshot(
    val summaries: Map<SpendWindow, WindowSummary>,
    val daily: List<Long>,
    val topCategories: List<CategoryTotal>,
    val reviewCount: Int,
    val totalCount: Int,
) {
    /** "Nothing spent" and "nothing imported" deserve very different messages. */
    val isEmpty: Boolean get() = totalCount == 0
}

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
        val (from, to) = window.range(now)
        val net = netting(from, to)

        val top = net.netByCategory
            .filterValues { it > 0 }
            .maxByOrNull { it.value }
            ?.let { CategoryTotal(it.key, it.value, 0) }

        return WindowSummary(
            window = window,
            spentPaise = net.netPaise,
            grossSpentPaise = net.grossPaise,
            reimbursedPaise = net.reimbursedPaise,
            receivedPaise = db.txns().sumIn(Direction.CREDIT, from, to),
            incomePaise = db.txns().sumCategoryIn(Direction.CREDIT, Categories.INCOME, from, to),
            txnCount = db.txns().countIn(Direction.DEBIT, from, to),
            topCategory = top,
        )
    }

    /** Net spend between two instants, after reimbursement netting. */
    suspend fun netSpendBetween(from: Long, to: Long): Long = netting(from, to).netPaise

    suspend fun widgetSnapshot(now: Long = System.currentTimeMillis()): WidgetSnapshot {
        return WidgetSnapshot(
            summaries = SpendWindow.entries.associateWith { summary(it, now) },
            daily = dailySeries(days = 30, now = now),
            topCategories = topCategories(SpendWindow.THIS_MONTH, limit = 3, now = now),
            reviewCount = db.txns().needingReviewCountNow(),
            totalCount = db.txns().totalCountNow(),
        )
    }

    /** Spend per account or card, biggest first, for the window. */
    suspend fun accountTotals(
        window: SpendWindow,
        now: Long = System.currentTimeMillis(),
    ): List<AccountTotal> = window.range(now).let { (from, to) -> db.txns().spendByAccount(from, to) }

    suspend fun allSummaries(now: Long = System.currentTimeMillis()): List<WindowSummary> =
        SpendWindow.entries.map { summary(it, now) }

    /** Category breakdown, net of reimbursements so it reconciles with the headline figure. */
    suspend fun topCategories(
        window: SpendWindow,
        limit: Int = 5,
        now: Long = System.currentTimeMillis(),
    ): List<CategoryTotal> {
        val (from, to) = window.range(now)
        val counts = db.txns().topCategories(Direction.DEBIT, from, to, Int.MAX_VALUE)
            .associate { it.category to it.txnCount }

        return netting(from, to).netByCategory
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
        val now = System.currentTimeMillis()

        db.txns().updateCategory(
            id = txn.id,
            category = category,
            source = CategorySource.USER,
            confidence = 1f,
            needsReview = false,
            updatedAt = now,
        )

        val merchant = txn.merchantNorm
        if (applyToPastFromSameMerchant && !merchant.isNullOrBlank()) {
            db.txns().relabelMerchant(merchant, category, now)
        }

        // Re-read so the classifier trains on the row as it now stands.
        val updated = db.txns().byId(txn.id) ?: txn.copy(category = category, updatedAt = now)
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

    // ------------------------------------------------------------------- sync
    //
    // Only ever called from the `cloud` flavour's RemoteSync implementation. Kept here rather than
    // behind raw TxnDao calls so the sync worker goes through the same invariant-centralising layer
    // as everything else, per docs/ARCHITECTURE.md.

    /** Local rows changed since their last successful push — SYNC.md's push payload. */
    suspend fun pendingSync(limit: Int = 500): List<TxnEntity> = db.txns().pendingSync(limit)

    /** Marks a row as pushed as of [updatedAt] (the value that was actually sent). */
    suspend fun markSynced(clientId: String, updatedAt: Long) = db.txns().markSynced(clientId, updatedAt)

    /**
     * Applies a transaction pulled from the sync server — a dashboard edit or server-side LLM
     * classification. Inserted if new (matched by `clientId`), otherwise merged into the existing
     * row.
     *
     * The category fields go through [CategoryPriority] first: a user's own decision (`USER` or
     * `MEMORY`) is never downgraded by a lower-priority remote source. Every other field always
     * takes the server's version — the server is the merge authority for anything beyond category
     * once two devices are in play, and in practice those fields don't change after extraction
     * anyway.
     */
    suspend fun applyRemoteTxn(remote: RemoteTxn) {
        val now = System.currentTimeMillis()
        val existing = db.txns().byClientId(remote.clientId)

        if (existing == null) {
            db.txns().insert(
                TxnEntity(
                    // No local raw message backs a server-originated row. 0 is a safe sentinel: it's
                    // never issued by RawMessageDao's autoGenerate primary key (which starts at 1),
                    // and TxnDao.search's LEFT JOIN already treats an unmatched rawMessageId as "no
                    // source" — the same case a rebuild's dropped file-import rows hit.
                    rawMessageId = 0L,
                    clientId = remote.clientId,
                    amountPaise = remote.amountPaise,
                    direction = remote.direction,
                    channel = remote.channel,
                    accountTail = remote.accountTail,
                    merchantRaw = remote.merchantRaw,
                    merchantNorm = remote.merchantNorm,
                    vpaHandle = null,
                    refNo = remote.refNo,
                    balancePaise = remote.balancePaise,
                    occurredAt = remote.occurredAt,
                    category = remote.category,
                    categorySource = remote.categorySource,
                    confidence = 1f,
                    needsReview = false,
                    parserVersion = 0,
                    createdAt = remote.updatedAt,
                    updatedAt = remote.updatedAt,
                    syncedAt = now,
                ),
            )
            return
        }

        val keepLocalCategory = CategoryPriority.localWins(existing, remote)
        db.txns().applySyncedFields(
            id = existing.id,
            amountPaise = remote.amountPaise,
            direction = remote.direction,
            channel = remote.channel,
            merchantRaw = remote.merchantRaw,
            merchantNorm = remote.merchantNorm,
            accountTail = remote.accountTail,
            refNo = remote.refNo,
            balancePaise = remote.balancePaise,
            occurredAt = remote.occurredAt,
            category = if (keepLocalCategory) existing.category else remote.category,
            categorySource = if (keepLocalCategory) existing.categorySource else remote.categorySource,
            needsReview = if (keepLocalCategory) existing.needsReview else false,
            updatedAt = if (keepLocalCategory) existing.updatedAt else remote.updatedAt,
            syncedAt = now,
        )
    }
}
