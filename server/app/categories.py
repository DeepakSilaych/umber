"""Canonical category taxonomy — mirrors Categories.kt in the Android app exactly.

Order matters on the Android side (it's the classifier's label encoding); the server doesn't
train a model, but keeps the same order and exact strings so a category round-trips identically
in both directions.
"""

FOOD = "Food & Dining"
GROCERIES = "Groceries"
TRANSPORT = "Transport"
SHOPPING = "Shopping"
BILLS = "Bills & Utilities"
ENTERTAINMENT = "Entertainment"
HEALTH = "Health"
EDUCATION = "Education"
RENT = "Rent & Housing"
INVESTMENTS = "Investments"
TRANSFERS = "Transfers"
CASH = "Cash"
TRAVEL = "Travel"
INCOME = "Income"
OTHER = "Other"

ALL = [
    FOOD,
    GROCERIES,
    TRANSPORT,
    SHOPPING,
    BILLS,
    ENTERTAINMENT,
    HEALTH,
    EDUCATION,
    RENT,
    INVESTMENTS,
    TRANSFERS,
    CASH,
    TRAVEL,
    INCOME,
    OTHER,
]

ALL_SET = frozenset(ALL)


def is_valid(category: str) -> bool:
    return category in ALL_SET


DIRECTIONS = ("DEBIT", "CREDIT")

CHANNELS = (
    "UPI",
    "CARD",
    "ATM",
    "IMPS",
    "NEFT",
    "RTGS",
    "NETBANKING",
    "AUTOPAY",
    "WALLET",
    "UNKNOWN",
)

# Priority order, highest trust first. A user's own correction always wins; MEMORY sits at the
# same tier as USER because a merchant-memory match only exists because a user confirmed that
# merchant once. See docs/SYNC.md "Conflict rules".
CATEGORY_SOURCE_TIER = {
    "USER": 0,
    "MEMORY": 0,
    "DASHBOARD": 1,
    "REMOTE": 2,
    "MODEL": 3,
    "SEED": 3,
    "NONE": 3,
}

CATEGORY_SOURCES = tuple(CATEGORY_SOURCE_TIER.keys())


def beats(candidate_source: str, candidate_updated_at: int, existing_source: str, existing_updated_at: int) -> bool:
    """True if a row with candidate_source/updated_at should overwrite one with existing_source/updated_at."""
    candidate_tier = CATEGORY_SOURCE_TIER.get(candidate_source, 3)
    existing_tier = CATEGORY_SOURCE_TIER.get(existing_source, 3)
    if candidate_tier != existing_tier:
        return candidate_tier < existing_tier
    return candidate_updated_at >= existing_updated_at
