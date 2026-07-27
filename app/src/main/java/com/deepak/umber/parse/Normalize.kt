package com.deepak.umber.parse

/**
 * Merchant string normalisation.
 *
 * The goal is that "SWIGGY LIMITED", "Swiggy*Order 8812" and "swiggy@ybl" all collapse to the same
 * key, because the merchant-memory lookup is an exact string match and every collision we fail to
 * collapse here becomes a separate thing the user has to categorise by hand.
 */
object Normalize {

    /** Corporate suffixes that carry no signal but wreck exact matching. */
    private val SUFFIXES = listOf(
        "private limited", "pvt limited", "pvt ltd", "pvt. ltd", "private ltd",
        "limited", "ltd", "pvt", "private", "llp", "inc", "corp", "co",
        "india", "in", "bangalore", "bengaluru", "mumbai", "delhi", "hyderabad", "pune", "chennai",
    )

    /**
     * Honorifics on personal payees.
     *
     * Banks are inconsistent about them, so the same person arrives as both "DEEPAK SILAYCH" and
     * "MR DEEPAK SILAYCH". Since merchant memory and reimbursement netting both key on this exact
     * string, an unstripped honorific splits one counterparty into two.
     */
    private val PREFIXES = listOf("mr", "mrs", "ms", "dr", "shri", "smt", "kum", "sri")

    private val PUNCT = Regex("""[^a-z0-9@. ]""")
    private val MULTISPACE = Regex("""\s+""")

    /** Trailing order/reference numbers glued onto merchant names, e.g. "amazon 3241". */
    private val TRAILING_DIGITS = Regex("""\s+\d{3,}$""")

    /** A UPI VPA, e.g. `swiggy.stores@icici` or `9876543210@ybl`. */
    private val VPA = Regex("""^([a-z0-9._-]{2,})@([a-z]{2,})$""")

    /**
     * Produces the canonical merchant key.
     *
     * For a VPA, only the local part is kept — the handle (`@ybl`, `@okhdfcbank`) identifies the
     * payment app, not the merchant, so folding it in would split one merchant across every PSP the
     * user happens to pay through.
     */
    fun merchant(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.lowercase().trim()

        VPA.find(s)?.let { s = it.groupValues[1] }

        s = s.replace('*', ' ').replace('_', ' ').replace('-', ' ')
        s = PUNCT.replace(s, " ")
        s = MULTISPACE.replace(s, " ").trim()

        // Strip suffixes repeatedly: "foo pvt ltd india" needs three passes.
        var changed = true
        while (changed) {
            changed = false
            for (suffix in SUFFIXES) {
                if (s.endsWith(" $suffix")) {
                    s = s.removeSuffix(" $suffix").trim()
                    changed = true
                }
            }
        }

        // Only strip an honorific when something remains — "MR" alone is all the payee we have.
        for (prefix in PREFIXES) {
            if (s.startsWith("$prefix ") && s.length > prefix.length + 1) {
                s = s.removePrefix("$prefix ").trim()
                break
            }
        }

        s = TRAILING_DIGITS.replace(s, "").trim()
        s = MULTISPACE.replace(s, " ")

        // A key that is nothing but digits (a bare phone-number VPA) is not a merchant identity we
        // can generalise from, but it IS a stable payee, so keep it rather than dropping it.
        return s.take(64)
    }

    /** The PSP handle of a VPA, or null. Weak signal, but free. */
    fun vpaHandle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val m = VPA.find(raw.lowercase().trim()) ?: return null
        return m.groupValues[2]
    }

    /** Human-facing title case, used for display only — never as a lookup key. */
    fun display(raw: String?): String {
        val key = merchant(raw)
        if (key.isEmpty()) return "Unknown"
        return key.split(' ').joinToString(" ") { word ->
            if (word.length <= 2) word.uppercase()
            else word.replaceFirstChar { it.uppercase() }
        }
    }
}
