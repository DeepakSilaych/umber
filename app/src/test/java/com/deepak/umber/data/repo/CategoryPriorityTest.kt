package com.deepak.umber.data.repo

import com.deepak.umber.data.db.TxnEntity
import com.deepak.umber.data.model.CategorySource
import com.deepak.umber.data.model.Channel
import com.deepak.umber.data.model.Direction
import com.deepak.umber.remote.RemoteTxn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `docs/SYNC.md`'s conflict table, exercised directly: a user's own correction (`USER`/`MEMORY`)
 * must never be downgraded by a lower-priority remote source, regardless of timestamps, and ties
 * within a tier must fall to whichever side wrote more recently.
 */
class CategoryPriorityTest {

    private fun txn(source: CategorySource, updatedAt: Long) = TxnEntity(
        rawMessageId = 1L,
        clientId = "local",
        amountPaise = 100L,
        direction = Direction.DEBIT,
        channel = Channel.UPI,
        accountTail = null,
        merchantRaw = "SWIGGY",
        merchantNorm = "swiggy",
        vpaHandle = null,
        refNo = null,
        balancePaise = null,
        occurredAt = 1_000L,
        category = "Food & Dining",
        categorySource = source,
        confidence = 1f,
        needsReview = false,
        parserVersion = 1,
        createdAt = 1_000L,
        updatedAt = updatedAt,
    )

    private fun remote(source: CategorySource, updatedAt: Long) = RemoteTxn(
        clientId = "local",
        occurredAt = 1_000L,
        amountPaise = 100L,
        direction = Direction.DEBIT,
        channel = Channel.UPI,
        merchantNorm = "swiggy",
        merchantRaw = "SWIGGY",
        accountTail = null,
        refNo = null,
        balancePaise = null,
        category = "Other",
        categorySource = source,
        updatedAt = updatedAt,
    )

    @Test
    fun `a USER category is never downgraded by a REMOTE one, even a newer one`() {
        val local = txn(CategorySource.USER, updatedAt = 1_000L)
        val incoming = remote(CategorySource.REMOTE, updatedAt = 9_999_999L)

        assertTrue(CategoryPriority.localWins(local, incoming))
    }

    @Test
    fun `a MEMORY category is never downgraded by a MODEL one`() {
        val local = txn(CategorySource.MEMORY, updatedAt = 1_000L)
        val incoming = remote(CategorySource.MODEL, updatedAt = 9_999_999L)

        assertTrue(CategoryPriority.localWins(local, incoming))
    }

    @Test
    fun `DASHBOARD beats REMOTE and MODEL regardless of timestamp`() {
        val local = txn(CategorySource.DASHBOARD, updatedAt = 1L)
        assertTrue(CategoryPriority.localWins(local, remote(CategorySource.REMOTE, updatedAt = 9_999L)))
        assertTrue(CategoryPriority.localWins(local, remote(CategorySource.MODEL, updatedAt = 9_999L)))
    }

    @Test
    fun `DASHBOARD loses to a USER or MEMORY correction`() {
        val local = txn(CategorySource.DASHBOARD, updatedAt = 9_999L)
        assertFalse(CategoryPriority.localWins(local, remote(CategorySource.USER, updatedAt = 1L)))
        assertFalse(CategoryPriority.localWins(local, remote(CategorySource.MEMORY, updatedAt = 1L)))
    }

    @Test
    fun `REMOTE beats the on-device layers MODEL, SEED and NONE`() {
        val local = txn(CategorySource.MODEL, updatedAt = 9_999L)
        assertFalse(CategoryPriority.localWins(local, remote(CategorySource.REMOTE, updatedAt = 1L)))
    }

    @Test
    fun `USER and MEMORY are the same tier, so ties break by updatedAt`() {
        val olderUser = txn(CategorySource.USER, updatedAt = 1_000L)
        val newerMemory = remote(CategorySource.MEMORY, updatedAt = 2_000L)
        assertFalse("a newer same-tier write should win", CategoryPriority.localWins(olderUser, newerMemory))

        val newerUser = txn(CategorySource.USER, updatedAt = 2_000L)
        val olderMemory = remote(CategorySource.MEMORY, updatedAt = 1_000L)
        assertTrue("an older same-tier write should lose", CategoryPriority.localWins(newerUser, olderMemory))
    }

    @Test
    fun `within the bottom tier, ties also break by updatedAt`() {
        val olderSeed = txn(CategorySource.SEED, updatedAt = 1_000L)
        assertFalse(CategoryPriority.localWins(olderSeed, remote(CategorySource.NONE, updatedAt = 2_000L)))
    }
}
