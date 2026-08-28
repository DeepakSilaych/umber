"""POST /v1/classify — the server-side classification job designed (but never built) in
docs/SYNC.md's "Server-side classification" section. Batches merchant strings with no confident
category and asks the LLM gateway; caches the verdict permanently in `merchant_categories` (a
merchant's category doesn't change, so any given string costs one call ever); applies the result
to matching transactions as `category_source = 'REMOTE'`, subject to the usual conflict rules.
"""

import json
import re
import time

import httpx
from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import Actor, require_dashboard_or_agent
from app.categories import ALL as ALL_CATEGORIES, OTHER, beats, is_valid
from app.config import Settings, get_settings
from app.db.base import get_db
from app.db.models import MerchantCategory, Transaction
from app.llm_gateway import chat_completion
from app.schemas import ClassifyDetailResponse, ClassifyResponse

router = APIRouter(prefix="/v1/classify", tags=["classify"])

# Measured on 15 real merchant descriptors per docs/SYNC.md: 348 tokens, 0.37s, one call — batching
# keeps a full history's worth of distinct merchants to a handful of calls, not one per merchant.
BATCH_SIZE = 25

_CATEGORY_LIST = ", ".join(f'"{c}"' for c in ALL_CATEGORIES)

SYSTEM_PROMPT = f"""You classify Indian bank/UPI transaction merchant strings into a FIXED set of
personal-finance categories. The strings are often short, abbreviated, or mangled by bank-statement
formatting (e.g. a stray space or line break mid-word: "bl inkit.pa" is Blinkit, "ze pto.payu" is
Zepto, "a mazonpayg" is Amazon Pay, "b haratpe09" is BharatPe, "air tel.pay" is Airtel). Use your
knowledge of common Indian merchants, UPI apps and payment aggregators to infer the real merchant
even from a garbled fragment.

Valid categories, exactly as written (never invent a new one, never abbreviate):
{_CATEGORY_LIST}

Respond with ONLY a single JSON object mapping each input string to exactly one category from that
list, no markdown fences, no prose outside the JSON:
{{"<merchant string 1>": "<category>", "<merchant string 2>": "<category>", ...}}
Every input string must appear as a key exactly once, verbatim. If you genuinely cannot infer
anything about a merchant, use "Other" rather than guessing wildly."""

_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)


def _parse_batch_response(raw: str, merchants: list[str]) -> dict[str, str]:
    for candidate in (raw, _FENCE_RE.sub("", raw).strip()):
        try:
            parsed = json.loads(candidate)
        except (json.JSONDecodeError, TypeError):
            continue
        if not isinstance(parsed, dict):
            continue
        result: dict[str, str] = {}
        for m in merchants:
            category = parsed.get(m)
            result[m] = category if isinstance(category, str) and is_valid(category) else OTHER
        return result
    # Total parse failure — fail the whole batch to Other rather than guessing per-key.
    return {m: OTHER for m in merchants}


@router.post("", response_model=ClassifyResponse)
def classify(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(require_dashboard_or_agent),
    settings: Settings = Depends(get_settings),
    limit: int = Query(default=200, ge=1, le=2000, description="max distinct merchants to classify this call"),
) -> ClassifyResponse:
    cached_merchants = select(MerchantCategory.merchant_norm)
    candidates = (
        db.execute(
            select(Transaction.merchant_norm)
            .where(
                Transaction.merchant_norm.isnot(None),
                Transaction.category == OTHER,
                Transaction.category_source.in_(("NONE", "SEED")),
                Transaction.merchant_norm.notin_(cached_merchants),
            )
            .distinct()
            .limit(limit)
        )
        .scalars()
        .all()
    )

    now_ms = int(time.time() * 1000)
    classified = 0
    transactions_updated = 0
    failed_batches = 0

    for i in range(0, len(candidates), BATCH_SIZE):
        batch = candidates[i : i + BATCH_SIZE]
        user_content = json.dumps(batch)
        try:
            raw = chat_completion(settings, SYSTEM_PROMPT, user_content)
            verdicts = _parse_batch_response(raw, batch)
        except httpx.HTTPError:
            failed_batches += 1
            continue

        for merchant_norm, category in verdicts.items():
            db.add(
                MerchantCategory(
                    merchant_norm=merchant_norm,
                    category=category,
                    confidence=None,
                    model=settings.llm_model,
                    decided_at=now_ms,
                )
            )
            classified += 1

            rows = db.execute(
                select(Transaction).where(Transaction.merchant_norm == merchant_norm)
            ).scalars().all()
            for row in rows:
                if beats("REMOTE", now_ms, row.category_source, row.updated_at):
                    row.category = category
                    row.category_source = "REMOTE"
                    row.needs_review = False
                    row.updated_at = now_ms
                    transactions_updated += 1
        db.commit()

    return ClassifyResponse(
        candidates_found=len(candidates),
        merchants_classified=classified,
        transactions_updated=transactions_updated,
        failed_batches=failed_batches,
    )


# --- Detailed classification: sub-category (axis 1) and normal/special (axis 2) ----------------

SUBCATEGORY_PROMPT = """You assign a SHORT sub-category to each Indian bank/UPI merchant, refining
its already-known top-level category. The merchant strings are often abbreviated or mangled by
statement formatting (a stray space or line break mid-word).

Input is a JSON array of {"merchant": "...", "category": "..."} objects. For each, return a concise
1-3 word Title Case sub-category that sits UNDER the given category — never contradict it. Examples:
- {"merchant":"ze pto.payu","category":"Groceries"} -> "Instant Delivery"
- {"merchant":"H2 canteen","category":"Food & Dining"} -> "Canteen"
- {"merchant":"pay zomato@","category":"Food & Dining"} -> "Food Delivery"
- {"merchant":"ne tflix.bd","category":"Entertainment"} -> "Streaming"
- {"merchant":"air tel.pay","category":"Bills & Utilities"} -> "Mobile & Internet"
- {"merchant":"hostingerp","category":"Bills & Utilities"} -> "Hosting & Cloud"
- {"merchant":"s bicardp.b","category":"Bills & Utilities"} -> "Credit Card Bill"
- {"merchant":"a mazonpayg","category":"Shopping"} -> "Online Marketplace"
- {"merchant":"uber","category":"Transport"} -> "Cab"
- {"merchant":"ola money","category":"Transport"} -> "Cab"
- {"merchant":"rapido","category":"Transport"} -> "Auto"
- {"merchant":"c f.irctc@c","category":"Transport"} -> "Train"
- {"merchant":"indigo","category":"Transport"} -> "Flight"
- {"merchant":"hpcl petrol","category":"Transport"} -> "Fuel"
For Transport specifically, use one of: Cab, Auto, Flight, Train, Bus, Metro, Fuel, Parking.

Respond with ONLY a JSON object mapping each merchant string (verbatim, exactly as given) to its
sub-category, no markdown fences, no extra prose:
{"<merchant 1>": "<sub-category>", "<merchant 2>": "<sub-category>", ...}
Every input merchant must appear as a key exactly once. Keep sub-categories consistent — reuse the
same wording for the same kind of spend."""




def _parse_map(raw: str, keys: list[str], allowed: set[str] | None, default: str) -> dict[str, str]:
    for candidate in (raw, _FENCE_RE.sub("", raw).strip()):
        try:
            parsed = json.loads(candidate)
        except (json.JSONDecodeError, TypeError):
            continue
        if not isinstance(parsed, dict):
            continue
        out: dict[str, str] = {}
        for k in keys:
            v = parsed.get(k)
            if isinstance(v, str) and v.strip() and (allowed is None or v.strip().upper() in allowed):
                out[k] = v.strip().upper() if allowed else v.strip()[:60]
            else:
                out[k] = default
        return out
    return {k: default for k in keys}


@router.post("/subcategory", response_model=ClassifyDetailResponse)
def classify_subcategory(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(require_dashboard_or_agent),
    settings: Settings = Depends(get_settings),
    limit: int = Query(default=200, ge=1, le=2000),
) -> ClassifyDetailResponse:
    """Assign a free-form sub-category to each merchant that has none cached yet, using the LLM,
    and apply it to that merchant's transactions (never overwriting a USER hand-edit)."""
    cached = select(MerchantCategory.merchant_norm).where(MerchantCategory.subcategory.isnot(None))
    # distinct (merchant_norm, category) among rows that still lack a subcategory
    rows = db.execute(
        select(Transaction.merchant_norm, Transaction.category)
        .where(
            Transaction.merchant_norm.isnot(None),
            Transaction.subcategory.is_(None),
            Transaction.merchant_norm.notin_(cached),
        )
        .distinct()
        .limit(limit)
    ).all()
    # keep the first category seen per merchant (a merchant is almost always one category)
    merchant_cat: dict[str, str] = {}
    for m, c in rows:
        merchant_cat.setdefault(m, c)
    merchants = list(merchant_cat.keys())

    now_ms = int(time.time() * 1000)
    assigned = 0
    transactions_updated = 0
    failed_batches = 0

    for i in range(0, len(merchants), BATCH_SIZE):
        batch = merchants[i : i + BATCH_SIZE]
        payload = [{"merchant": m, "category": merchant_cat[m]} for m in batch]
        try:
            raw = chat_completion(settings, SUBCATEGORY_PROMPT, json.dumps(payload))
            verdicts = _parse_map(raw, batch, allowed=None, default="")
        except httpx.HTTPError:
            failed_batches += 1
            continue

        for merchant_norm, subcat in verdicts.items():
            if not subcat:
                continue
            mc = db.get(MerchantCategory, merchant_norm)
            if mc is None:
                mc = MerchantCategory(
                    merchant_norm=merchant_norm,
                    category=merchant_cat[merchant_norm],
                    subcategory=subcat,
                    model=settings.llm_model,
                    decided_at=now_ms,
                )
                db.add(mc)
            else:
                mc.subcategory = subcat
            assigned += 1

            txns = db.execute(
                select(Transaction).where(
                    Transaction.merchant_norm == merchant_norm,
                    Transaction.subcategory.is_(None),
                    Transaction.category_source != "USER",
                )
            ).scalars().all()
            for t in txns:
                t.subcategory = subcat
                transactions_updated += 1
        db.commit()

    return ClassifyDetailResponse(
        candidates_found=len(merchants),
        assigned=assigned,
        transactions_updated=transactions_updated,
        failed_batches=failed_batches,
    )
