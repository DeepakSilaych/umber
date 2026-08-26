"""Bank statement parsing entry point.

Ported from the ``StatementParser`` object (and the bytes-sniffing half of ``StatementImporter``)
in ``app/src/main/java/com/deepak/umber/io/StatementImporter.kt``. This module is pure and has no
database access — it turns bytes into :class:`ParsedTxnRow` objects and nothing else, so a FastAPI
route can call it directly.

There is no standard schema for an Indian bank statement export — every bank invents its own
column names, throws a few rows of account preamble above the header, and disagrees about whether
debit and credit are separate columns or one signed column. So rather than a per-bank template
library, the header row is located by scoring and columns are matched on keywords, exactly as the
Kotlin source does. The keyword lists and matching precedence below are copied verbatim, in the
same order, because column-matching order (an exact match on any keyword always beats a substring
match on any keyword, and earlier keywords in a list always beat later ones even positionally) is
part of what has to stay byte-identical with the phone.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.parsing import csv_reader, xlsx_reader
from app.parsing.dates import parse_date, parse_paise
from app.parsing.normalize import normalize_merchant, vpa_handle as vpa_handle_of

DATE_KEYS = ["value date", "txn date", "transaction date", "tran date", "posting date", "date"]
NARRATION_KEYS = [
    "narration", "description", "particulars", "transaction remarks", "transaction details",
    "remarks", "details",
]
DEBIT_KEYS = [
    "withdrawal amt", "withdrawal amount", "debit amount", "withdrawal", "debit", "paid out", "dr",
]
CREDIT_KEYS = [
    "deposit amt", "deposit amount", "credit amount", "deposit", "credit", "paid in", "cr",
]
AMOUNT_KEYS = ["transaction amount", "txn amount", "amount"]
BALANCE_KEYS = ["closing balance", "available balance", "running balance", "balance"]
REF_KEYS = [
    "chq/ref no", "ref no./cheque no", "reference no", "transaction id", "txn id", "utr no",
    "reference", "ref no", "cheque no", "utr",
]

# References embedded in a narration, anchored to a scheme marker.
#
# Anchoring is essential: narrations also contain account numbers of similar length, and mistaking
# one for a reference would merge unrelated transactions — a far worse outcome than simply not
# finding a reference.
_NARRATION_REF = re.compile(
    r"\b(?:UPI|IMPS|NEFT|RTGS|UTR|RRN|MMT|ACH)[\s/:\-]{1,3}([A-Z0-9]{8,22})", re.IGNORECASE
)

_HEADER_SEARCH_DEPTH = 30

_SCHEME_WORDS = {
    "upi", "imps", "neft", "rtgs", "ach", "pos", "atm", "mmt", "inb", "nwd",
    "dr", "cr", "p2a", "p2m", "payment", "transfer", "txn",
}

_PAYMENT_TO_FROM = re.compile(r"^payment (?:to|from)\s+", re.IGNORECASE)

# Canonical form of a reference number for cross-source matching, ported from RefKey.normalize in
# TxnRecord.kt. TxnRecord.kt lives outside the files this port was scoped to, but
# StatementParser.parseRows in StatementImporter.kt hands its resolved `ref` straight to the
# `txnRecord()` builder, whose refNo parameter is `RefKey.normalize(refNo)` — so a statement's
# reference goes through this exact normalization in the real pipeline, same as an SMS's does. The
# router that calls this module does an exact-match SQL lookup against `Transaction.reference`,
# which the phone always populates with this same normalized form (see RefKey.normalize's callers
# in TxnRecord.kt) — skipping this step here would silently break reference-based dedup between
# statement imports and phone-synced rows for any reference containing stray punctuation, mixed
# case, or too few digits to be trustworthy as a dedupe key.
#
# A reference must contain at least four digits to be used: the phrase "UPI Mandate" once caused
# the SMS extractor to capture the word "MANDATE" as a reference, and because a reference doubles
# as a dedupe key, every recurring mandate payment after the first was silently discarded as a
# duplicate of it (see docs/ARCHITECTURE.md#deduplication).
_NON_ALNUM = re.compile(r"[^A-Za-z0-9]")

_MONEY_KEYS = DEBIT_KEYS + CREDIT_KEYS + AMOUNT_KEYS
_ALL_KEYS = DATE_KEYS + NARRATION_KEYS + DEBIT_KEYS + CREDIT_KEYS + AMOUNT_KEYS + BALANCE_KEYS + REF_KEYS


def _normalize_reference(ref: str | None) -> str | None:
    if ref is None or ref.strip() == "":
        return None
    key = _NON_ALNUM.sub("", ref).upper()
    if len(key) >= 6 and sum(1 for c in key if c.isdigit()) >= 4:
        return key
    return None


@dataclass
class ParsedTxnRow:
    occurred_at_ms: int
    amount_paise: int  # absolute value
    direction: str  # "DEBIT" or "CREDIT"
    channel: str  # one of UPI, CARD, ATM, IMPS, NEFT, RTGS, NETBANKING, AUTOPAY, WALLET, UNKNOWN
    merchant_raw: str | None
    merchant_norm: str | None
    vpa_handle: str | None
    reference: str | None
    balance_paise: int | None
    raw_line: str  # original row cells joined with " | "


@dataclass
class StatementParseResult:
    rows: list[ParsedTxnRow]
    total_data_rows: int
    skipped: int
    problem: str | None = None  # None on success; a human-readable message on failure


def _looks_like_zip(data: bytes) -> bool:
    """Zip local-file-header magic. Cheap way to tell an xlsx from a CSV before doing any work."""
    return len(data) >= 4 and data[0] == 0x50 and data[1] == 0x4B


def _looks_like_legacy_xls(data: bytes) -> bool:
    """True for the legacy binary (BIFF) format, so the caller can give a useful message."""
    return len(data) >= 8 and data[0:4] == b"\xd0\xcf\x11\xe0"


def _get(row: list[str], idx: int | None) -> str | None:
    if idx is None or idx < 0 or idx >= len(row):
        return None
    return row[idx]


def _index_of_key(header: list[str], keys: list[str]) -> int | None:
    """Exact match first, so "amount" never wins over "withdrawal amount" by accident.

    Keys are tried in priority order across the *whole* header before falling back to the next
    key — a low-priority key's exact match never loses to a high-priority key's later position.
    """
    for key in keys:
        for i, cell in enumerate(header):
            if cell == key:
                return i
    for key in keys:
        for i, cell in enumerate(header):
            if key in cell:
                return i
    return None


def _find_header_row(rows: list[list[str]]) -> int | None:
    """Scores each row on how many known column keywords it contains and takes the best.

    Bank exports routinely put account holder, account number and statement period above the real
    header, so the first row is usually the wrong answer.
    """
    best = -1
    best_score = 0

    for index, row in enumerate(rows[:_HEADER_SEARCH_DEPTH]):
        cells = [c.lower().strip() for c in row]
        has_date = any(any(k in cell for k in DATE_KEYS) for cell in cells)
        has_money = any(any(k in cell for k in _MONEY_KEYS) for cell in cells)
        score = sum(1 for cell in cells if cell != "" and any(k in cell for k in _ALL_KEYS))

        if has_date and has_money and score > best_score:
            best_score = score
            best = index

    return best if best >= 0 else None


def _resolve_amount(
    row: list[str], debit_col: int | None, credit_col: int | None, amount_col: int | None
) -> int | None:
    """Negative is a debit. Separate debit/credit columns take precedence over a signed column."""
    if debit_col is not None:
        v = parse_paise(_get(row, debit_col))
        if v is not None and v != 0:
            return -abs(v)
    if credit_col is not None:
        v = parse_paise(_get(row, credit_col))
        if v is not None and v != 0:
            return abs(v)
    if amount_col is not None:
        v = parse_paise(_get(row, amount_col))
        if v is not None and v != 0:
            return v
    return None


def _merchant_from(narration: str) -> str | None:
    """Pulls a merchant out of a slash-delimited narration such as
    ``UPI/512345678902/PAYMENT TO SWIGGY`` by taking the most word-like segment.
    """
    if narration.strip() == "":
        return None

    segments = [s.strip() for s in re.split(r"[/|:]", narration)]
    candidates = [
        s
        for s in segments
        if len(s) >= 3
        and sum(1 for c in s if c.isalpha()) >= 3
        and s.lower() not in _SCHEME_WORDS
    ]
    if not candidates:
        return None

    best = max(candidates, key=lambda s: sum(1 for c in s if c.isalpha()))
    result = _PAYMENT_TO_FROM.sub("", best).strip()[:60]
    return result if result != "" else None


def _channel_from(narration: str) -> str:
    n = narration.lower()
    if "upi" in n:
        return "UPI"
    if "imps" in n:
        return "IMPS"
    if "neft" in n:
        return "NEFT"
    if "rtgs" in n:
        return "RTGS"
    if "atm" in n or "cash wdl" in n:
        return "ATM"
    if "pos" in n or "card" in n:
        return "CARD"
    if "ach" in n or "mandate" in n:
        return "AUTOPAY"
    return "UNKNOWN"


def _parse_rows(rows: list[list[str]]) -> StatementParseResult:
    """Shared by the CSV and xlsx paths — a spreadsheet and a CSV are the same grid of cells once
    decoded, so column detection and row interpretation have exactly one implementation.
    """
    if not rows:
        return StatementParseResult([], 0, 0, "No rows found")

    header_index = _find_header_row(rows)
    if header_index is None:
        return StatementParseResult(
            [], 0, 0, "Couldn't find a header row with a date and an amount column"
        )

    header = [c.lower().strip() for c in rows[header_index]]
    date_col = _index_of_key(header, DATE_KEYS)
    if date_col is None:
        return StatementParseResult([], 0, 0, "No date column")

    narration_col = _index_of_key(header, NARRATION_KEYS)
    debit_col = _index_of_key(header, DEBIT_KEYS)
    credit_col = _index_of_key(header, CREDIT_KEYS)
    amount_col = _index_of_key(header, AMOUNT_KEYS)
    balance_col = _index_of_key(header, BALANCE_KEYS)
    ref_col = _index_of_key(header, REF_KEYS)

    if debit_col is None and credit_col is None and amount_col is None:
        return StatementParseResult([], 0, 0, "No debit/credit or amount column")

    out: list[ParsedTxnRow] = []
    skipped = 0
    data_rows = rows[header_index + 1 :]

    for row in data_rows:
        occurred_at_ms = parse_date(_get(row, date_col))
        signed = _resolve_amount(row, debit_col, credit_col, amount_col)
        if occurred_at_ms is None or signed is None or signed == 0:
            skipped += 1
            continue

        narration = _get(row, narration_col) or ""

        ref_cell = _get(row, ref_col)
        ref = ref_cell if (ref_cell is not None and ref_cell.strip() != "" and ref_cell != "-") else None
        if ref is None:
            m = _NARRATION_REF.search(narration)
            ref = m.group(1) if m else None
        ref = _normalize_reference(ref)

        merchant_raw = _merchant_from(narration)
        balance_paise = parse_paise(_get(row, balance_col)) if balance_col is not None else None

        out.append(
            ParsedTxnRow(
                occurred_at_ms=occurred_at_ms,
                amount_paise=abs(signed),
                direction="DEBIT" if signed < 0 else "CREDIT",
                channel=_channel_from(narration),
                merchant_raw=merchant_raw,
                merchant_norm=normalize_merchant(merchant_raw),
                vpa_handle=vpa_handle_of(merchant_raw),
                reference=ref,
                balance_paise=balance_paise,
                raw_line=" | ".join(row),
            )
        )

    return StatementParseResult(out, len(data_rows), skipped)


def parse_statement(data: bytes) -> StatementParseResult:
    """Turns a bank statement file (xlsx, CSV or TSV) into transaction rows.

    Format is detected from magic bytes rather than filename or MIME type, because banks routinely
    serve a CSV named ``.xls`` and an upload's reported content type is unreliable.
    """
    if not data:
        return StatementParseResult([], 0, 0, "File is empty")

    if _looks_like_legacy_xls(data):
        return StatementParseResult(
            [],
            0,
            0,
            "That's an old-format .xls file. Open it and 'Save as' .xlsx or CSV, then try again.",
        )

    if _looks_like_zip(data):
        try:
            rows = xlsx_reader.read_rows(data)
        except Exception:
            return StatementParseResult([], 0, 0, "Couldn't read that spreadsheet")
        return _parse_rows(rows)

    text = data.decode("utf-8", errors="replace")
    if text.startswith("﻿"):
        text = text[1:]
    if text.strip() == "":
        return StatementParseResult([], 0, 0, "File is empty")

    rows = csv_reader.read_rows(text)
    return _parse_rows(rows)
