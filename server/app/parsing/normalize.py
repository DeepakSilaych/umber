"""Merchant string normalisation, ported from
``app/src/main/java/com/deepak/umber/parse/Normalize.kt``.

The goal is that "SWIGGY LIMITED", "Swiggy*Order 8812" and "swiggy@ybl" all collapse to the same
key, because the merchant-memory lookup on the phone is an exact string match and every collision
this port fails to reproduce becomes a row that disagrees with the phone about which merchant a
transaction belongs to.
"""

from __future__ import annotations

import re

# Corporate suffixes that carry no signal but wreck exact matching.
_SUFFIXES = [
    "private limited", "pvt limited", "pvt ltd", "pvt. ltd", "private ltd",
    "limited", "ltd", "pvt", "private", "llp", "inc", "corp", "co",
    "india", "in", "bangalore", "bengaluru", "mumbai", "delhi", "hyderabad", "pune", "chennai",
]

# Honorifics on personal payees. Banks are inconsistent about them, so the same person arrives as
# both "DEEPAK SILAYCH" and "MR DEEPAK SILAYCH"; an unstripped honorific splits one counterparty
# into two.
_PREFIXES = ["mr", "mrs", "ms", "dr", "shri", "smt", "kum", "sri"]

_PUNCT = re.compile(r"[^a-z0-9@. ]")
_MULTISPACE = re.compile(r"\s+")

# Trailing order/reference numbers glued onto merchant names, e.g. "amazon 3241".
_TRAILING_DIGITS = re.compile(r"\s+\d{3,}$")

# A UPI VPA, e.g. `swiggy.stores@icici` or `9876543210@ybl`.
_VPA = re.compile(r"^([a-z0-9._-]{2,})@([a-z]{2,})$")


def normalize_merchant(raw: str | None) -> str:
    """Produces the canonical merchant key.

    For a VPA, only the local part is kept — the handle (``@ybl``, ``@okhdfcbank``) identifies the
    payment app, not the merchant, so folding it in would split one merchant across every PSP the
    user happens to pay through.
    """
    if raw is None or raw.strip() == "":
        return ""
    s = raw.lower().strip()

    m = _VPA.match(s)
    if m:
        s = m.group(1)

    s = s.replace("*", " ").replace("_", " ").replace("-", " ")
    s = _PUNCT.sub(" ", s)
    s = _MULTISPACE.sub(" ", s).strip()

    # Strip suffixes repeatedly: "foo pvt ltd india" needs three passes.
    changed = True
    while changed:
        changed = False
        for suffix in _SUFFIXES:
            if s.endswith(" " + suffix):
                s = s[: -(len(suffix) + 1)].strip()
                changed = True

    # Only strip an honorific when something remains — "MR" alone is all the payee we have.
    for prefix in _PREFIXES:
        if s.startswith(prefix + " ") and len(s) > len(prefix) + 1:
            s = s[len(prefix) + 1 :].strip()
            break

    s = _TRAILING_DIGITS.sub("", s).strip()
    s = _MULTISPACE.sub(" ", s)

    # A key that is nothing but digits (a bare phone-number VPA) is not a merchant identity we can
    # generalise from, but it IS a stable payee, so keep it rather than dropping it.
    return s[:64]


def vpa_handle(raw: str | None) -> str | None:
    """The PSP handle of a VPA, or None. Weak signal, but free."""
    if raw is None or raw.strip() == "":
        return None
    m = _VPA.match(raw.lower().strip())
    if not m:
        return None
    return m.group(2)


def display_merchant(raw: str | None) -> str:
    """Human-facing title case, used for display only — never as a lookup key."""
    key = normalize_merchant(raw)
    if key == "":
        return "Unknown"

    def _title(word: str) -> str:
        if len(word) <= 2:
            return word.upper()
        return word[0].upper() + word[1:]

    return " ".join(_title(w) for w in key.split(" "))
