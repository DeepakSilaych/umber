package com.deepak.umber.ml

import com.deepak.umber.data.model.Categories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeedLexiconTest {

    @Test
    fun `common indian merchants resolve on a cold start`() {
        assertEquals(Categories.FOOD, SeedLexicon.lookup("swiggy"))
        assertEquals(Categories.GROCERIES, SeedLexicon.lookup("zepto marketpla"))
        assertEquals(Categories.TRANSPORT, SeedLexicon.lookup("uber india"))
        assertEquals(Categories.ENTERTAINMENT, SeedLexicon.lookup("netflix com"))
        assertEquals(Categories.SHOPPING, SeedLexicon.lookup("amazon pay"))
        assertEquals(Categories.INCOME, SeedLexicon.lookup("salary"))
    }

    /** Longest pattern first, so a specific entry is never shadowed by a substring of itself. */
    @Test
    fun `more specific patterns win`() {
        assertEquals(Categories.GROCERIES, SeedLexicon.lookup("jiomart express"))
        assertEquals(Categories.HEALTH, SeedLexicon.lookup("tata 1mg healthcare"))
        assertEquals(Categories.TRANSFERS, SeedLexicon.lookup("credit card payment"))
    }

    @Test
    fun `unknown merchants return null rather than guessing`() {
        assertNull(SeedLexicon.lookup("qwertyzxcv traders"))
        assertNull(SeedLexicon.lookup(""))
        assertNull(SeedLexicon.lookup(null))
    }

    /**
     * Body matching is a last resort and only uses patterns of five characters or more — short
     * ones like "pvr" or "1mg" collide with ordinary words and reference numbers.
     */
    @Test
    fun `body matching ignores short patterns`() {
        assertNull(SeedLexicon.lookupInBody("Rs.300 debited at PVR on 12-07-25"))
        assertEquals(
            Categories.FOOD,
            SeedLexicon.lookupInBody("Rs.300 debited towards SWIGGY order on 12-07-25"),
        )
    }

    @Test
    fun `every mapped category is a real label`() {
        val mapped = listOf(
            "swiggy", "zepto", "uber", "netflix", "amazon", "airtel",
            "apollo", "byjus", "nobroker", "zerodha", "salary", "atm withdrawal",
        ).mapNotNull { SeedLexicon.lookup(it) }

        mapped.forEach { category ->
            assertEquals("$category is not in Categories.ALL", true, Categories.isValid(category))
        }
    }
}
