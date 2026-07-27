package com.deepak.umber.data.repo

import com.deepak.umber.data.model.Categories
import org.junit.Assert.assertEquals
import org.junit.Test

class NettingTest {

    private fun debit(merchant: String?, paise: Long, category: String = Categories.OTHER) =
        MerchantDebit(merchant, category, paise)

    private fun credit(merchant: String, paise: Long) = MerchantCredit(merchant, paise)

    /** The case that motivated all of this: pay a friend ₹1,000, get ₹1,000 back, spend nothing. */
    @Test
    fun `a full repayment cancels the expense`() {
        val result = Netting.apply(
            debits = listOf(debit("ravi", 1_00_000)),
            credits = listOf(credit("ravi", 1_00_000)),
        )

        assertEquals(1_00_000L, result.grossPaise)
        assertEquals(1_00_000L, result.reimbursedPaise)
        assertEquals(0L, result.netPaise)
    }

    @Test
    fun `a partial repayment leaves the difference`() {
        val result = Netting.apply(
            debits = listOf(debit("ravi", 1_00_000)),
            credits = listOf(credit("ravi", 40_000)),
        )

        assertEquals(60_000L, result.netPaise)
        assertEquals(40_000L, result.reimbursedPaise)
    }

    /**
     * Being paid back more than you paid does not create negative spending — it means the extra is
     * something else, and inventing a negative expense would corrupt every total above it.
     */
    @Test
    fun `an overpayment cannot push spending below zero`() {
        val result = Netting.apply(
            debits = listOf(debit("ravi", 50_000)),
            credits = listOf(credit("ravi", 2_00_000)),
        )

        assertEquals(0L, result.netPaise)
        assertEquals(50_000L, result.reimbursedPaise)
    }

    /**
     * The critical safety property. Salary is filtered out before netting, so it can never offset
     * spending — if it could, a month's pay would silently erase a month's expenses.
     */
    @Test
    fun `money from someone you never paid is not a reimbursement`() {
        val result = Netting.apply(
            debits = listOf(debit("swiggy", 45_000, Categories.FOOD)),
            credits = listOf(credit("acme payroll", 50_00_000)),
        )

        assertEquals(45_000L, result.netPaise)
        assertEquals(0L, result.reimbursedPaise)
    }

    @Test
    fun `each counterparty is netted independently`() {
        val result = Netting.apply(
            debits = listOf(debit("ravi", 1_00_000), debit("swiggy", 45_000, Categories.FOOD)),
            credits = listOf(credit("ravi", 1_00_000)),
        )

        assertEquals(1_45_000L, result.grossPaise)
        assertEquals(1_00_000L, result.reimbursedPaise)
        assertEquals(45_000L, result.netPaise)
        assertEquals(45_000L, result.netByCategory[Categories.FOOD])
    }

    /** A repayment reduces the category the original spending was filed under. */
    @Test
    fun `category totals reconcile with the net figure`() {
        val result = Netting.apply(
            debits = listOf(
                debit("ravi", 1_00_000, Categories.FOOD),
                debit("swiggy", 45_000, Categories.FOOD),
                debit("uber", 20_000, Categories.TRANSPORT),
            ),
            credits = listOf(credit("ravi", 60_000)),
        )

        assertEquals(85_000L, result.netByCategory[Categories.FOOD])
        assertEquals(20_000L, result.netByCategory[Categories.TRANSPORT])
        assertEquals(result.netPaise, result.netByCategory.values.sum())
    }

    /** Splitting an offset across categories must not lose paise to integer division. */
    @Test
    fun `offset distribution loses nothing to rounding`() {
        val result = Netting.apply(
            debits = listOf(
                debit("ravi", 33_333, Categories.FOOD),
                debit("ravi", 33_333, Categories.TRANSPORT),
                debit("ravi", 33_334, Categories.SHOPPING),
            ),
            credits = listOf(credit("ravi", 50_000)),
        )

        assertEquals(50_000L, result.netPaise)
        assertEquals(50_000L, result.netByCategory.values.sum())
    }

    /** An unidentified counterparty can't be matched to anyone, so it never offsets. */
    @Test
    fun `debits with no merchant are never netted`() {
        val result = Netting.apply(
            debits = listOf(debit(null, 75_000), debit("", 25_000)),
            credits = listOf(credit("ravi", 5_00_000)),
        )

        assertEquals(1_00_000L, result.netPaise)
        assertEquals(0L, result.reimbursedPaise)
    }

    @Test
    fun `empty input is zero not a crash`() {
        val result = Netting.apply(emptyList(), emptyList())
        assertEquals(0L, result.grossPaise)
        assertEquals(0L, result.netPaise)
        assertEquals(emptyMap<String, Long>(), result.netByCategory)
    }
}
