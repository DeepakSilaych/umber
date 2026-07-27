package com.deepak.umber.data.repo

import kotlin.math.min

/** Money paid out to one counterparty, with the category it was filed under. */
data class MerchantDebit(val merchantNorm: String?, val category: String, val paise: Long)

/** Money received back from one counterparty. Income is excluded before this point. */
data class MerchantCredit(val merchantNorm: String, val paise: Long)

data class NettingResult(
    /** Everything paid out, before any settlement. */
    val grossPaise: Long,
    /** The part that came back from someone you had paid. */
    val reimbursedPaise: Long,
    /** What the spending actually cost: [grossPaise] − [reimbursedPaise]. */
    val netPaise: Long,
    /** Per-category totals, already net of reimbursements. */
    val netByCategory: Map<String, Long>,
)

/**
 * Nets reimbursements against spending, per counterparty.
 *
 * The problem: paying a friend ₹1,000 and being paid back ₹1,000 is not ₹1,000 of expense plus
 * ₹1,000 of income — it is ₹0. Counting both inflates spending *and* fakes income, and the
 * distortion compounds with every split bill.
 *
 * The rule is deliberately conservative: a credit only offsets spending when it comes from a
 * counterparty you actually paid **within the same window**, and it can never offset more than you
 * paid them. Money from someone you never paid is income, not a refund, so it is left alone.
 *
 * Two things keep salary out of it. The counterparty match does the heavy lifting — a paycheque
 * from an employer can only offset money paid *to* that employer, which nobody does. On top of
 * that, the caller drops credits the user has deliberately filed as income. Marking a paycheque as
 * a refund would silently erase real expenditure, which is the worst failure this calculation can
 * have, so it is guarded twice.
 *
 * Pure, so the arithmetic is unit-testable without a database.
 */
object Netting {

    fun apply(debits: List<MerchantDebit>, credits: List<MerchantCredit>): NettingResult {
        val gross = debits.sumOf { it.paise }

        val netByCategory = HashMap<String, Long>()
        for (debit in debits) {
            netByCategory[debit.category] = (netByCategory[debit.category] ?: 0L) + debit.paise
        }

        val creditByMerchant = credits
            .groupBy { it.merchantNorm }
            .mapValues { (_, rows) -> rows.sumOf { it.paise } }

        var reimbursed = 0L

        // A null merchant can't be matched to anyone, so those debits never offset.
        val debitsByMerchant = debits
            .filter { !it.merchantNorm.isNullOrBlank() }
            .groupBy { it.merchantNorm!! }

        for ((merchant, rows) in debitsByMerchant) {
            val credited = creditByMerchant[merchant] ?: continue
            val paidOut = rows.sumOf { it.paise }
            val offset = min(paidOut, credited)
            if (offset <= 0L) continue

            reimbursed += offset

            // Spread the offset across the categories this counterparty's spending was filed
            // under, in proportion to each. Integer division loses at most a few paise, so the
            // remainder is assigned to the largest row rather than silently dropped.
            var distributed = 0L
            val ordered = rows.sortedByDescending { it.paise }
            ordered.forEachIndexed { index, row ->
                val share = if (index == ordered.lastIndex) {
                    offset - distributed
                } else {
                    offset * row.paise / paidOut
                }
                distributed += share
                netByCategory[row.category] = (netByCategory[row.category] ?: 0L) - share
            }
        }

        return NettingResult(
            grossPaise = gross,
            reimbursedPaise = reimbursed,
            netPaise = gross - reimbursed,
            // A category can only be driven to zero, never negative: being paid back more than you
            // spent in a category is a data artefact, not negative spending.
            netByCategory = netByCategory.mapValues { (_, v) -> v.coerceAtLeast(0L) },
        )
    }
}
