package com.deepak.umber.data.model

enum class SourceType {
    SMS,
    NOTIFICATION,

    /** A row from an imported bank statement (CSV/TSV export). Date-only, no clock time. */
    STATEMENT,

    /** A row from this app's own CSV ledger export — a round-trip, not a new observation. */
    LEDGER_CSV,

    MANUAL,
}

enum class Direction { DEBIT, CREDIT }

enum class Channel { UPI, CARD, ATM, IMPS, NEFT, RTGS, NETBANKING, AUTOPAY, WALLET, UNKNOWN }

/** Where a transaction's category came from. Drives the "trust" of the label. */
enum class CategorySource {
    /** User explicitly picked it. Ground truth. */
    USER,

    /** Exact merchant match against a previously user-confirmed merchant. */
    MEMORY,

    /** On-device logistic regression, above the confidence threshold. */
    MODEL,

    /** Bundled seed lexicon (cold start). */
    SEED,

    /** Nothing matched. */
    NONE,
}

/**
 * How much to trust the location attached to a transaction. Derived purely from the age of the
 * cached fix at ingest time — a stale fix is worse than useless if it's presented as fact.
 */
enum class LocConfidence { HIGH, MEDIUM, LOW, NONE }
