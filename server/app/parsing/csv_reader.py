"""CSV/TSV reading for statement imports.

Row tokenization (quoting, embedded delimiters, embedded newlines, ``""`` escapes) is delegated to
Python's built-in :mod:`csv` module rather than hand-rolled, unlike
``app/src/main/java/com/deepak/umber/io/Csv.kt`` — the server has no APK-size constraint that
justifies avoiding a stdlib dependency the way the Android app avoided a third-party one.

The delimiter **auto-detection** algorithm is *not* something the ``csv`` module does, so it is
ported faithfully from ``Csv.detectDelimiter`` in the Kotlin source: sample the first 20 non-blank
lines, count occurrences of each candidate delimiter per line, take the value at
``sorted(counts)[len(counts) // 2]`` (not a textbook median for even-sized samples, but exactly
what the Kotlin code computes) as that delimiter's typical count, score
``typical * 100 - (lines whose count differs from typical)``, and pick the highest-scoring
candidate — first-seen wins on a tie, which in candidate order ``[',', '\\t', ';', '|']`` means a
tie always falls back to comma.
"""

from __future__ import annotations

import csv
import re

_CANDIDATE_DELIMITERS = [",", "\t", ";", "|"]

# Matches Kotlin's String.lineSequence(), which splits only on \r\n, \r or \n — unlike Python's
# str.splitlines(), which also breaks on \v, \f and various Unicode line separators.
_LINE_SPLIT = re.compile(r"\r\n|\r|\n")
_LINE_SPLIT_KEEP_TERMINATOR = re.compile(r"(\r\n|\r|\n)")


def _lines(text: str) -> list[str]:
    return _LINE_SPLIT.split(text)


def _lines_with_terminators(text: str) -> list[str]:
    """Splits into physical lines the same way ``_lines`` does, but keeps each line's original
    terminator attached.

    Feeding ``csv.reader`` a plain ``io.StringIO(text)`` hits ``_csv.Error: new-line character
    seen in unquoted field`` on input containing a bare ``\\r`` not paired with ``\\n`` (stray
    control bytes, old Mac-style line endings) — a real possibility on an arbitrary uploaded file,
    where Kotlin's hand-rolled tokenizer never throws at all. Passing csv.reader a pre-split list
    of terminator-preserving lines instead avoids that failure mode entirely, and keeping the
    terminators (rather than a bare list of stripped lines) is what lets csv.reader still
    reconstruct an embedded newline correctly inside a quoted multi-line field.
    """
    parts = _LINE_SPLIT_KEEP_TERMINATOR.split(text)
    lines: list[str] = []
    for i in range(0, len(parts), 2):
        terminator = parts[i + 1] if i + 1 < len(parts) else ""
        lines.append(parts[i] + terminator)
    return lines


def detect_delimiter(text: str) -> str:
    sample = [line for line in _lines(text) if line.strip() != ""][:20]
    if not sample:
        return ","

    best_delim = ","
    best_score = None
    for delimiter in _CANDIDATE_DELIMITERS:
        counts = [line.count(delimiter) for line in sample]
        typical = sorted(counts)[len(counts) // 2]
        score = 0 if typical == 0 else typical * 100 - sum(1 for c in counts if c != typical)
        if best_score is None or score > best_score:
            best_score = score
            best_delim = delimiter

    return best_delim


def read_rows(text: str) -> list[list[str]]:
    """Tokenizes CSV/TSV text into a grid of trimmed cell strings, dropping fully-blank rows."""
    if text.startswith("﻿"):
        text = text[1:]

    delimiter = detect_delimiter(text)
    reader = csv.reader(_lines_with_terminators(text), delimiter=delimiter)

    rows: list[list[str]] = []
    for raw_row in reader:
        row = [cell.strip() for cell in raw_row]
        if any(cell != "" for cell in row):
            rows.append(row)
    return rows
