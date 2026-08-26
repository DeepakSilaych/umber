"""Date and money parsing, ported from ``app/src/main/java/com/deepak/umber/parse/DateParse.kt``.

Every format, ordering choice and numeric-cleanup rule below is a literal port of the Kotlin
source, not a reimplementation from first principles — the goal is byte-identical output from the
same input cell so a statement parsed on the server produces the same rows the phone would produce
from the same file (same dates, same paise, same sign).

Timezone: the Kotlin side anchors dates at noon in ``ZoneId.systemDefault()``, which on the user's
phone is Asia/Kolkata (this is a single-user, India-only app; the Kotlin test suite itself pins the
zone to ``Asia/Kolkata`` rather than relying on the test JVM's default). The server runs in a
container that defaults to UTC, so relying on *this* process's local zone would silently anchor
every statement row five and a half hours off from what the phone would have produced for the same
file, breaking same-day dedup. Asia/Kolkata is therefore hardcoded here rather than read from the
system, deliberately, rather than left to vary by deployment.
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from zoneinfo import ZoneInfo

_ZONE = ZoneInfo("Asia/Kolkata")

# Excel stores dates as a day count from 1899-12-30. The odd epoch is the well-known 1900
# leap-year bug: Excel believes 1900 was a leap year, and anchoring two days before 1900-01-01
# cancels it out for every date after March 1900 — which is every date a bank statement will ever
# contain.
_EXCEL_EPOCH = date(1899, 12, 30)

# ~1954 to ~2119. Narrow enough that a plausible amount is unlikely to be read as a date.
_SERIAL_MIN = 20_000
_SERIAL_MAX = 80_000

_MONTHS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}

# Each entry is (regex, group order) where group order is a tuple of which capture group holds
# day/month/year, and whether the month group is a name (looked up in _MONTHS) or a number.
# These mirror, one-for-one, the FORMATS list in DateParse.kt:
#   "d-M-yy", "d-M-yyyy", "d/M/yy", "d/M/yyyy",
#   "d-MMM-yy", "d-MMM-yyyy", "d MMM yy", "d MMM yyyy",
#   "d/MMM/yy", "d/MMM/yyyy", "ddMMMyy", "ddMMMyyyy",
#   "yyyy-M-d", "yyyy/M/d", "d.M.yy", "d.M.yyyy",
# A bare "yy" is a fixed two-digit field resolved against a base of 2000 (Java's default reduced-
# value base for a two-letter year pattern) — never a rolling "current year" pivot, so "26" is
# always 2026, not 1926.
_FORMATS: list[re.Pattern[str]] = []


def _numeric(day_month_year_sep: str, year_first: bool = False) -> re.Pattern[str]:
    sep = re.escape(day_month_year_sep)
    if year_first:
        return re.compile(rf"^(?P<y>\d{{4}}){sep}(?P<m>\d{{1,2}}){sep}(?P<d>\d{{1,2}})$")
    return re.compile(rf"^(?P<d>\d{{1,2}}){sep}(?P<m>\d{{1,2}}){sep}(?P<y2>\d{{2}})$")


def _numeric_full_year(sep: str) -> re.Pattern[str]:
    s = re.escape(sep)
    return re.compile(rf"^(?P<d>\d{{1,2}}){s}(?P<m>\d{{1,2}}){s}(?P<y>\d{{4}})$")


def _month_name(sep: str, year_width: str) -> re.Pattern[str]:
    s = re.escape(sep)
    yfield = r"(?P<y2>\d{2})" if year_width == "yy" else r"(?P<y>\d{4})"
    return re.compile(rf"^(?P<d>\d{{1,2}}){s}(?P<mon>[A-Za-z]{{3}}){s}{yfield}$")


def _month_name_no_sep(year_width: str) -> re.Pattern[str]:
    yfield = r"(?P<y2>\d{2})" if year_width == "yy" else r"(?P<y>\d{4})"
    return re.compile(rf"^(?P<d>\d{{2}})(?P<mon>[A-Za-z]{{3}}){yfield}$")


# Ordered exactly like Kotlin's FORMATS list; each format is (pattern, kind) where kind tells
# _apply_match how to read out day/month/year.
_FORMAT_SPECS: list[re.Pattern[str]] = [
    _numeric("-"),  # d-M-yy
    _numeric_full_year("-"),  # d-M-yyyy
    _numeric("/"),  # d/M/yy
    _numeric_full_year("/"),  # d/M/yyyy
    _month_name("-", "yy"),  # d-MMM-yy
    _month_name("-", "yyyy"),  # d-MMM-yyyy
    _month_name(" ", "yy"),  # d MMM yy
    _month_name(" ", "yyyy"),  # d MMM yyyy
    _month_name("/", "yy"),  # d/MMM/yy
    _month_name("/", "yyyy"),  # d/MMM/yyyy
    _month_name_no_sep("yy"),  # ddMMMyy
    _month_name_no_sep("yyyy"),  # ddMMMyyyy
    _numeric("-", year_first=True),  # yyyy-M-d
    _numeric("/", year_first=True),  # yyyy/M/d
    _numeric("."),  # d.M.yy
    _numeric_full_year("."),  # d.M.yyyy
]


def _resolve_year(match: re.Match[str]) -> int | None:
    groups = match.groupdict()
    if groups.get("y") is not None:
        return int(groups["y"])
    if groups.get("y2") is not None:
        # Java's DateTimeFormatterBuilder resolves a bare two-letter "yy" pattern against a fixed
        # base of 2000 — always 2000-2099, never a rolling pivot.
        return 2000 + int(groups["y2"])
    return None


def _try_pattern(pattern: re.Pattern[str], candidate: str) -> date | None:
    m = pattern.match(candidate)
    if not m:
        return None
    groups = m.groupdict()
    year = _resolve_year(m)
    if year is None:
        return None
    day = int(groups["d"])
    if "mon" in groups and groups["mon"] is not None:
        month = _MONTHS.get(groups["mon"].lower())
        if month is None:
            return None
    else:
        month = int(groups["m"])
    try:
        return date(year, month, day)
    except ValueError:
        return None


def excel_serial(serial: int) -> date | None:
    """Excel day-count to calendar date, or None outside the plausible range."""
    if _SERIAL_MIN <= serial <= _SERIAL_MAX:
        from datetime import timedelta

        return _EXCEL_EPOCH + timedelta(days=serial)
    return None


def _parse_calendar_date(token: str | None) -> date | None:
    cleaned = token.strip() if token is not None else ""
    if not cleaned:
        return None

    # Statement cells often carry a time component; the date is the leading token.
    head = cleaned.split(" ", 1)[0] or cleaned

    for candidate in (head, cleaned):
        for pattern in _FORMAT_SPECS:
            result = _try_pattern(pattern, candidate)
            if result is not None:
                return result

    # xlsx date cells arrive as bare serial numbers when the workbook stores them as real dates
    # rather than text.
    head_of_cleaned = cleaned.split(".", 1)[0]
    try:
        serial = int(head_of_cleaned)
    except ValueError:
        serial = None
    if serial is not None:
        parsed = excel_serial(serial)
        if parsed is not None:
            return parsed

    return None


def parse_date(cell: str | None) -> int | None:
    """Parses a statement date cell to epoch-millis at noon Asia/Kolkata for that calendar date.

    Midday, not midnight: a statement gives no clock time, and anchoring at noon keeps the row
    inside the intended day under any small timezone nudge.
    """
    parsed = _parse_calendar_date(cell)
    if parsed is None:
        return None
    dt = datetime(parsed.year, parsed.month, parsed.day, 12, 0, tzinfo=_ZONE)
    return int(dt.timestamp() * 1000)


# Money ---------------------------------------------------------------------------------------

_DR_CR_TOKEN = re.compile(r"\b[dc]r\b", re.IGNORECASE)
# No trailing \b after the optional dot: in "Rs. 500" a boundary between "." and " " does not
# exist, so requiring one leaves the dot behind and ".500" parses as ₹0.50.
_CURRENCY_TOKEN = re.compile(r"\brs\.?|\binr\b|\brupees\b|[₹$]", re.IGNORECASE)
_NUMERIC_LITERAL = re.compile(r"^[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?$")


def parse_paise(text: str | None) -> int | None:
    """Rupee string to integer paise.

    Tolerates currency symbols, thousands separators, whitespace, trailing ``Dr``/``Cr`` markers
    and parenthesised negatives — all of which appear in real exports. Returns None when there is
    no number at all, and preserves sign so a single-amount column can encode direction.
    """
    if text is None or text.strip() == "":
        return None
    s = text.strip()

    parenthesised = s.startswith("(") and s.endswith(")")
    if parenthesised:
        s = s[1:-1]

    trailing_dr = s.upper().endswith("DR")

    s = _DR_CR_TOKEN.sub("", s)
    s = _CURRENCY_TOKEN.sub("", s)
    s = s.replace(",", "")
    s = s.replace(" ", "")
    s = s.strip()
    s = s.lstrip(".:")

    if s == "" or s == "-" or s == ".":
        return None

    if not _NUMERIC_LITERAL.match(s):
        return None

    try:
        value = Decimal(s) * 100
        value = value.quantize(Decimal("1"), rounding=ROUND_HALF_UP)
        result = int(value)
    except (InvalidOperation, ValueError):
        return None

    if parenthesised or trailing_dr:
        result = -abs(result)
    return result
