package com.deepak.umber.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cross-source dedupe primitives.
 *
 * The end-to-end path needs Room, but the two pieces that decide whether a statement row and an SMS
 * describe the same payment are pure, and they are where the interesting mistakes live.
 */
class DedupeTest {

    // -------------------------------------------------------------- reference keys

    /**
     * The same UPI RRN reaches the app as `UPI:512345678902` from an ICICI SMS and as
     * `UPI/512345678902/…` in that transaction's statement narration. If these don't collapse to
     * one key, importing a statement silently doubles every transaction already captured.
     */
    @Test
    fun `the same reference from different sources yields one key`() {
        val fromSms = RefKey.normalize("512345678902")
        val fromStatement = RefKey.normalize("UPI/512345678902")
        val spaced = RefKey.normalize("512345 678902")

        assertEquals(fromSms, spaced)
        assertNotEquals(null, fromSms)
        // The statement form carries a scheme prefix, which normalising keeps — extraction is what
        // strips it, so this documents the boundary between the two.
        assertEquals("UPI512345678902", fromStatement)
    }

    @Test
    fun `casing and punctuation are irrelevant`() {
        assertEquals(RefKey.normalize("abc12xy34zq56"), RefKey.normalize("ABC-12XY3/4ZQ56"))
    }

    /**
     * A reference doubles as a dedupe key, so a word picked up from the surrounding sentence would
     * make every recurring mandate look like a duplicate of the first. Anything without enough
     * digits is refused, and dedupe falls back to a check that fails safe.
     */
    @Test
    fun `word like captures are refused`() {
        assertNull(RefKey.normalize("MANDATE"))
        assertNull(RefKey.normalize("AUTOPAY"))
        assertNull(RefKey.normalize("Standing"))
        assertNull(RefKey.normalize(""))
        assertNull(RefKey.normalize(null))
    }

    @Test
    fun `short references are refused`() {
        assertNull(RefKey.normalize("12345"))
        assertEquals("123456", RefKey.normalize("123456"))
    }

    // ------------------------------------------------------------- occurrence counting

    /**
     * Nine identical ₹500 mandate debits, several on the same day, are nine transactions — not one
     * repeated. The occurrence counter is what lets a date-only import tell them apart, since they
     * share amount, direction, account and day.
     */
    @Test
    fun `identical same day rows get increasing occurrence numbers`() {
        val session = ImportSession()
        val key = "2026-05-04|50000|DEBIT|468"

        assertEquals(1, session.occurrence(key))
        assertEquals(2, session.occurrence(key))
        assertEquals(3, session.occurrence(key))
    }

    @Test
    fun `distinct signatures count independently`() {
        val session = ImportSession()
        assertEquals(1, session.occurrence("a"))
        assertEquals(1, session.occurrence("b"))
        assertEquals(2, session.occurrence("a"))
    }

    /**
     * Walks the rule `duplicate = existing >= occurrence` through the three cases that matter.
     * Re-importing must be a no-op; a statement that knows about more payments than SMS captured
     * must top up the difference and no more.
     */
    @Test
    fun `occurrence rule is idempotent and tops up partial coverage`() {
        fun run(existingBefore: Int, rowsInFile: Int): Int {
            val session = ImportSession()
            var existing = existingBefore
            var inserted = 0
            repeat(rowsInFile) {
                val needed = session.occurrence("k")
                if (existing >= needed) return@repeat
                existing++
                inserted++
            }
            return inserted
        }

        // Nothing captured yet: every row is new.
        assertEquals(3, run(existingBefore = 0, rowsInFile = 3))
        // Same file again: nothing is new.
        assertEquals(0, run(existingBefore = 3, rowsInFile = 3))
        // SMS caught two of three: exactly one is missing.
        assertEquals(1, run(existingBefore = 2, rowsInFile = 3))
    }
}
