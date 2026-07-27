package com.deepak.umber.parse

/**
 * First-stage triage on the SMS sender ID.
 *
 * Indian transactional SMS arrive from DLT header IDs like `VM-HDFCBK`, `AD-SBIUPI`, `JD-ICICIB` —
 * a two-char operator prefix plus a six-char entity code. We use this only to *cheaply discard*
 * obvious noise; a sender we don't recognise still gets handed to the parser, which is far stricter.
 *
 * The one hard reject is a personal mobile number. Friends texting "sent you 500" would otherwise
 * parse beautifully and pollute the ledger.
 */
object SenderFilter {

    enum class SenderClass { BANK, PSP, PERSONAL, UNKNOWN }

    private val BANK_CODES = setOf(
        "hdfcbk", "hdfcbn", "sbiinb", "sbiupi", "sbibnk", "sbicrd", "atmsbi",
        "icicib", "icicit", "axisbk", "axisbn", "kotakb", "kotak",
        "pnbsms", "pnbbnk", "bobtxn", "bobibn", "canbnk", "cbssbi",
        "unionb", "ubinet", "idfcfb", "yesbnk", "indusb", "aubank",
        "rblbnk", "fedbnk", "citibk", "hsbcin", "scbank", "idbibk",
        "bankin", "cbinbk", "iobchn", "ucobnk", "psbbnk", "dcbbnk",
        "equtas", "esafbk", "jkbank", "karbnk", "kvbank", "tmbank",
        "amexin", "onecrd", "slcebk",
    )

    private val PSP_CODES = setOf(
        "paytmb", "paytm", "phonpe", "phnpay", "gpayin", "bhimup", "npcibh",
        "amzonp", "amazon", "mobikw", "freechg", "cred", "slice", "jupitr",
        "fisdom", "razrpy", "cashfr",
    )

    /** 10-digit Indian mobile, optionally with a +91 / 0 prefix. */
    private val PERSONAL_NUMBER = Regex("""^(?:\+?91|0)?[6-9]\d{9}$""")

    private val NON_ALNUM = Regex("""[^a-z0-9]""")

    /**
     * Reduces `VM-HDFCBK` / `AD-HDFCBK-S` / `hdfcbk` to `hdfcbk`.
     *
     * DLT headers can carry a trailing category suffix (`-S`, `-T`, `-P`), so we look at every
     * hyphen-delimited segment rather than assuming the code is the second one.
     */
    fun normalizeSender(sender: String): String = NON_ALNUM.replace(sender.lowercase(), "")

    fun classify(sender: String): SenderClass {
        val raw = sender.trim()
        if (raw.isEmpty()) return SenderClass.UNKNOWN

        if (PERSONAL_NUMBER.matches(NON_ALNUM.replace(raw.lowercase(), ""))) {
            return SenderClass.PERSONAL
        }

        val segments = raw.lowercase().split('-', '_', '.').map { NON_ALNUM.replace(it, "") }
        val candidates = segments + listOf(normalizeSender(raw))

        for (c in candidates) {
            if (c.isEmpty()) continue
            if (BANK_CODES.contains(c)) return SenderClass.BANK
            if (PSP_CODES.contains(c)) return SenderClass.PSP
        }

        // Some banks append/prepend a digit or letter to the entity code. Fall back to substring
        // containment on the longest segment before giving up.
        val longest = candidates.maxByOrNull { it.length }.orEmpty()
        if (longest.length in 6..12) {
            for (code in BANK_CODES) if (longest.contains(code)) return SenderClass.BANK
            for (code in PSP_CODES) if (longest.contains(code)) return SenderClass.PSP
        }

        return SenderClass.UNKNOWN
    }

    /** Hard reject — never even attempt to parse. */
    fun isBlocked(sender: String): Boolean = classify(sender) == SenderClass.PERSONAL
}
