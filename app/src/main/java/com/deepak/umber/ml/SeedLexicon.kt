package com.deepak.umber.ml

import com.deepak.umber.data.model.Categories

/**
 * Cold-start knowledge: a hand-written merchant substring -> category map, weighted toward Indian
 * merchants and UPI payee names.
 *
 * This exists so that day one isn't a wall of "Other". It is deliberately the *lowest* priority
 * signal — user corrections and the trained model both override it — and it is never used as a
 * training label, because seeding the model with its own guesses would just amplify the lexicon's
 * blind spots instead of learning the user's actual habits.
 *
 * Matching is longest-pattern-first, so `credit card payment` beats `card` and `pizza hut` beats
 * `pizza`.
 */
object SeedLexicon {

    private val RAW: List<Pair<String, String>> = listOf(
        // ---- Food & Dining
        "swiggy" to Categories.FOOD,
        "zomato" to Categories.FOOD,
        "eternal" to Categories.FOOD,
        "dominos" to Categories.FOOD,
        "pizza hut" to Categories.FOOD,
        "mcdonald" to Categories.FOOD,
        "burger king" to Categories.FOOD,
        "kfc" to Categories.FOOD,
        "subway" to Categories.FOOD,
        "starbucks" to Categories.FOOD,
        "third wave" to Categories.FOOD,
        "blue tokai" to Categories.FOOD,
        "chaayos" to Categories.FOOD,
        "chai point" to Categories.FOOD,
        "haldiram" to Categories.FOOD,
        "bikanervala" to Categories.FOOD,
        "barbeque nation" to Categories.FOOD,
        "behrouz" to Categories.FOOD,
        "ovenstory" to Categories.FOOD,
        "faasos" to Categories.FOOD,
        "freshmenu" to Categories.FOOD,
        "wow momo" to Categories.FOOD,
        "biryani" to Categories.FOOD,
        "restaurant" to Categories.FOOD,
        "bakery" to Categories.FOOD,
        "cafe" to Categories.FOOD,
        "dhaba" to Categories.FOOD,
        "sweets" to Categories.FOOD,
        "canteen" to Categories.FOOD,
        "foods" to Categories.FOOD,

        // ---- Groceries
        "bigbasket" to Categories.GROCERIES,
        "blinkit" to Categories.GROCERIES,
        "grofers" to Categories.GROCERIES,
        "zepto" to Categories.GROCERIES,
        "instamart" to Categories.GROCERIES,
        "dmart" to Categories.GROCERIES,
        "jiomart" to Categories.GROCERIES,
        "reliance fresh" to Categories.GROCERIES,
        "natures basket" to Categories.GROCERIES,
        "spencer" to Categories.GROCERIES,
        "licious" to Categories.GROCERIES,
        "freshtohome" to Categories.GROCERIES,
        "country delight" to Categories.GROCERIES,
        "supermarket" to Categories.GROCERIES,
        "super market" to Categories.GROCERIES,
        "provision" to Categories.GROCERIES,
        "kirana" to Categories.GROCERIES,
        "general store" to Categories.GROCERIES,
        "vegetable" to Categories.GROCERIES,
        "dairy" to Categories.GROCERIES,

        // ---- Transport
        "uber" to Categories.TRANSPORT,
        "olacabs" to Categories.TRANSPORT,
        "ola money" to Categories.TRANSPORT,
        "rapido" to Categories.TRANSPORT,
        "namma yatri" to Categories.TRANSPORT,
        "blusmart" to Categories.TRANSPORT,
        "yulu" to Categories.TRANSPORT,
        "quickride" to Categories.TRANSPORT,
        "fastag" to Categories.TRANSPORT,
        "toll plaza" to Categories.TRANSPORT,
        "parking" to Categories.TRANSPORT,
        "indian oil" to Categories.TRANSPORT,
        "bharat petroleum" to Categories.TRANSPORT,
        "hindustan petroleum" to Categories.TRANSPORT,
        "petrol" to Categories.TRANSPORT,
        "fuel" to Categories.TRANSPORT,
        "metro rail" to Categories.TRANSPORT,
        "bmtc" to Categories.TRANSPORT,
        "dmrc" to Categories.TRANSPORT,

        // ---- Travel
        "makemytrip" to Categories.TRAVEL,
        "goibibo" to Categories.TRAVEL,
        "cleartrip" to Categories.TRAVEL,
        "easemytrip" to Categories.TRAVEL,
        "ixigo" to Categories.TRAVEL,
        "yatra" to Categories.TRAVEL,
        "irctc" to Categories.TRAVEL,
        "redbus" to Categories.TRAVEL,
        "indigo" to Categories.TRAVEL,
        "air india" to Categories.TRAVEL,
        "vistara" to Categories.TRAVEL,
        "spicejet" to Categories.TRAVEL,
        "akasa" to Categories.TRAVEL,
        "airbnb" to Categories.TRAVEL,
        "booking com" to Categories.TRAVEL,
        "oyo" to Categories.TRAVEL,
        "resort" to Categories.TRAVEL,

        // ---- Shopping
        "amazon" to Categories.SHOPPING,
        "flipkart" to Categories.SHOPPING,
        "myntra" to Categories.SHOPPING,
        "ajio" to Categories.SHOPPING,
        "meesho" to Categories.SHOPPING,
        "nykaa" to Categories.SHOPPING,
        "tatacliq" to Categories.SHOPPING,
        "snapdeal" to Categories.SHOPPING,
        "lenskart" to Categories.SHOPPING,
        "decathlon" to Categories.SHOPPING,
        "croma" to Categories.SHOPPING,
        "reliance digital" to Categories.SHOPPING,
        "vijay sales" to Categories.SHOPPING,
        "shoppers stop" to Categories.SHOPPING,
        "pantaloons" to Categories.SHOPPING,
        "lifestyle" to Categories.SHOPPING,
        "westside" to Categories.SHOPPING,
        "ikea" to Categories.SHOPPING,
        "zara" to Categories.SHOPPING,
        "boat lifestyle" to Categories.SHOPPING,

        // ---- Bills & Utilities
        "airtel" to Categories.BILLS,
        "jio " to Categories.BILLS,
        "reliance jio" to Categories.BILLS,
        "vodafone" to Categories.BILLS,
        "bsnl" to Categories.BILLS,
        "act fibernet" to Categories.BILLS,
        "hathway" to Categories.BILLS,
        "tata play" to Categories.BILLS,
        "dish tv" to Categories.BILLS,
        "electricity" to Categories.BILLS,
        "bescom" to Categories.BILLS,
        "bses" to Categories.BILLS,
        "tata power" to Categories.BILLS,
        "adani electricity" to Categories.BILLS,
        "torrent power" to Categories.BILLS,
        "mahavitaran" to Categories.BILLS,
        "indane" to Categories.BILLS,
        "hp gas" to Categories.BILLS,
        "gas agency" to Categories.BILLS,
        "water board" to Categories.BILLS,
        "broadband" to Categories.BILLS,
        "recharge" to Categories.BILLS,
        "postpaid" to Categories.BILLS,
        "municipal" to Categories.BILLS,

        // ---- Entertainment
        "netflix" to Categories.ENTERTAINMENT,
        "hotstar" to Categories.ENTERTAINMENT,
        "disney" to Categories.ENTERTAINMENT,
        "prime video" to Categories.ENTERTAINMENT,
        "spotify" to Categories.ENTERTAINMENT,
        "youtube" to Categories.ENTERTAINMENT,
        "sony liv" to Categories.ENTERTAINMENT,
        "zee5" to Categories.ENTERTAINMENT,
        "jiocinema" to Categories.ENTERTAINMENT,
        "bookmyshow" to Categories.ENTERTAINMENT,
        "pvr" to Categories.ENTERTAINMENT,
        "inox" to Categories.ENTERTAINMENT,
        "cinepolis" to Categories.ENTERTAINMENT,
        "steam games" to Categories.ENTERTAINMENT,
        "playstation" to Categories.ENTERTAINMENT,

        // ---- Health
        "pharmeasy" to Categories.HEALTH,
        "netmeds" to Categories.HEALTH,
        "tata 1mg" to Categories.HEALTH,
        "1mg" to Categories.HEALTH,
        "medplus" to Categories.HEALTH,
        "apollo" to Categories.HEALTH,
        "practo" to Categories.HEALTH,
        "cult fit" to Categories.HEALTH,
        "cultfit" to Categories.HEALTH,
        "hospital" to Categories.HEALTH,
        "clinic" to Categories.HEALTH,
        "diagnostic" to Categories.HEALTH,
        "pathology" to Categories.HEALTH,
        "pharmacy" to Categories.HEALTH,
        "medical" to Categories.HEALTH,
        "dental" to Categories.HEALTH,
        "fitness" to Categories.HEALTH,

        // ---- Education
        "byjus" to Categories.EDUCATION,
        "unacademy" to Categories.EDUCATION,
        "vedantu" to Categories.EDUCATION,
        "coursera" to Categories.EDUCATION,
        "udemy" to Categories.EDUCATION,
        "upgrad" to Categories.EDUCATION,
        "physics wallah" to Categories.EDUCATION,
        "university" to Categories.EDUCATION,
        "college" to Categories.EDUCATION,
        "institute" to Categories.EDUCATION,
        "coaching" to Categories.EDUCATION,
        "tuition" to Categories.EDUCATION,
        "academy" to Categories.EDUCATION,

        // ---- Rent & Housing
        "nobroker" to Categories.RENT,
        "house rent" to Categories.RENT,
        "rent payment" to Categories.RENT,
        "landlord" to Categories.RENT,
        "society maintenance" to Categories.RENT,
        "apartment" to Categories.RENT,
        "mygate" to Categories.RENT,

        // ---- Investments
        "zerodha" to Categories.INVESTMENTS,
        "groww" to Categories.INVESTMENTS,
        "upstox" to Categories.INVESTMENTS,
        "angel one" to Categories.INVESTMENTS,
        "angelbroking" to Categories.INVESTMENTS,
        "kuvera" to Categories.INVESTMENTS,
        "smallcase" to Categories.INVESTMENTS,
        "indmoney" to Categories.INVESTMENTS,
        "mutual fund" to Categories.INVESTMENTS,
        "sip installment" to Categories.INVESTMENTS,
        "nps contribution" to Categories.INVESTMENTS,
        "wazirx" to Categories.INVESTMENTS,
        "coindcx" to Categories.INVESTMENTS,
        "securities" to Categories.INVESTMENTS,
        "insurance" to Categories.INVESTMENTS,
        "policybazaar" to Categories.INVESTMENTS,

        // ---- Transfers
        "credit card payment" to Categories.TRANSFERS,
        "card payment" to Categories.TRANSFERS,
        // Paying off a card bill moves money between the user's own instruments, not to a merchant.
        "credit card" to Categories.TRANSFERS,
        "own account" to Categories.TRANSFERS,
        "self transfer" to Categories.TRANSFERS,
        "cred club" to Categories.TRANSFERS,

        // ---- Cash
        "cash withdrawal" to Categories.CASH,
        "atm withdrawal" to Categories.CASH,

        // ---- Income
        "salary" to Categories.INCOME,
        "interest credit" to Categories.INCOME,
        "dividend" to Categories.INCOME,
    )

    /**
     * Longest first, so a specific pattern is always tested before a substring of it.
     * Computed once at class-init rather than per lookup.
     */
    private val ORDERED: List<Pair<String, String>> = RAW.sortedByDescending { it.first.length }

    fun lookup(merchantNorm: String?): String? {
        if (merchantNorm.isNullOrBlank()) return null
        val m = merchantNorm.lowercase()
        for ((pattern, category) in ORDERED) {
            if (m.contains(pattern)) return category
        }
        return null
    }

    /** Also matched against the raw SMS body when the merchant name itself is uninformative. */
    fun lookupInBody(body: String): String? {
        val b = body.lowercase()
        for ((pattern, category) in ORDERED) {
            if (pattern.length >= 5 && b.contains(pattern)) return category
        }
        return null
    }
}
