import hashlib
import json
import re
import time

import httpx
from fastapi import APIRouter, Depends, Query
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.auth import Actor, require_dashboard_or_agent
from app.config import Settings, get_settings
from app.db.base import get_db
from app.db.models import InsightsCache
from app.llm_gateway import chat_completion
from app.categories import INCOME
from app.routers.stats import _fetch_rows, _netting_for, _resolve_window
from app.schemas import InsightsResponse

router = APIRouter(prefix="/v1/insights", tags=["insights"])

SYSTEM_PROMPT = """You are a personal finance assistant for a single user's expense tracker. You receive a compact
JSON summary of one time period's spending (already aggregated — no individual transactions).
Write a short, concrete, non-generic analysis grounded only in the numbers given. Do not invent
categories, merchants, or amounts not present in the input. Amounts are in INR.

Respond with ONLY a single JSON object, no markdown fences, no prose outside the JSON:
{"summary": "<2-4 sentence paragraph citing specific numbers from the input>",
 "suggestions": ["<concrete, actionable suggestion>", "..."]}
"suggestions" must have 2-4 items, each one sentence, each referencing a specific category,
merchant, or trend from the input — never generic advice like "track your spending"."""


def _paise_to_rupees(paise: int) -> float:
    return round(paise / 100, 2)


def _build_payload(db: Session, period: str, window_from: int, window_to: int) -> dict:
    rows = _fetch_rows(db, window_from, window_to, None)
    result = _netting_for(rows)
    income_paise = sum(r.amount_paise for r in rows if r.direction == "CREDIT" and r.category == INCOME)

    prev_len = window_to - window_from
    prev_from, prev_to = window_from - prev_len, window_from
    prev_rows = _fetch_rows(db, prev_from, prev_to, None)
    prev_result = _netting_for(prev_rows)
    prev_income = sum(r.amount_paise for r in prev_rows if r.direction == "CREDIT" and r.category == INCOME)

    merchant_totals: dict[str, int] = {}
    channel_totals: dict[str, int] = {}
    for r in rows:
        if r.direction != "DEBIT":
            continue
        if r.merchant_norm:
            merchant_totals[r.merchant_norm] = merchant_totals.get(r.merchant_norm, 0) + r.amount_paise
        if r.channel:
            channel_totals[r.channel] = channel_totals.get(r.channel, 0) + r.amount_paise

    top_merchants = sorted(merchant_totals.items(), key=lambda kv: -kv[1])[:5]

    return {
        "period_label": period,
        "totals": {
            "gross_spend_inr": _paise_to_rupees(result.gross_paise),
            "net_spend_inr": _paise_to_rupees(result.net_paise),
            "reimbursed_inr": _paise_to_rupees(result.reimbursed_paise),
            "income_inr": _paise_to_rupees(income_paise),
            "transaction_count": len(rows),
            "needs_review_count": sum(1 for r in rows if r.needs_review),
        },
        "previous_period": {
            "label": "previous equal-length period",
            "net_spend_inr": _paise_to_rupees(prev_result.net_paise),
            "income_inr": _paise_to_rupees(prev_income),
        },
        "spend_by_category_inr": {k: _paise_to_rupees(v) for k, v in result.net_by_category.items()},
        "top_merchants_inr": [{"merchant": m, "amount_inr": _paise_to_rupees(a)} for m, a in top_merchants],
        "spend_by_channel_inr": {k: _paise_to_rupees(v) for k, v in channel_totals.items()},
    }, result, income_paise


_FENCE_RE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)


def _parse_llm_response(raw: str) -> dict | None:
    for candidate in (raw, _FENCE_RE.sub("", raw).strip()):
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict) and "summary" in parsed and "suggestions" in parsed:
                return parsed
        except (json.JSONDecodeError, TypeError):
            continue
    return None


def _fallback_summary(net_spend_paise: int, income_paise: int, prev_net_spend_paise: int) -> str:
    trend = "up from" if net_spend_paise > prev_net_spend_paise else "down from"
    return (
        f"Net spend this period was ₹{_paise_to_rupees(net_spend_paise):,.2f}, {trend} "
        f"₹{_paise_to_rupees(prev_net_spend_paise):,.2f} last period. Income was "
        f"₹{_paise_to_rupees(income_paise):,.2f}."
    )


@router.post("/generate", response_model=InsightsResponse)
def generate_insights(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(require_dashboard_or_agent),
    settings: Settings = Depends(get_settings),
    period: str = Query(default="this_month"),
    from_ms: int | None = None,
    to_ms: int | None = None,
    force_refresh: bool = Query(default=False),
) -> InsightsResponse:
    now_ms = int(time.time() * 1000)
    window_from, window_to, label = _resolve_window(period, from_ms, to_ms, now_ms)

    payload, result, income_paise = _build_payload(db, label, window_from, window_to)
    payload_json = json.dumps(payload, sort_keys=True)
    stats_hash = hashlib.sha256(payload_json.encode("utf-8")).hexdigest()

    if not force_refresh:
        cached = db.get(InsightsCache, (label, window_from, window_to, stats_hash))
        if cached is not None:
            return InsightsResponse(
                period=label,
                from_ms=window_from,
                to_ms=window_to,
                summary=cached.summary,
                suggestions=cached.suggestions,
                llm_generated=True,
                cached=True,
                model=cached.model,
                generated_at=cached.generated_at,
            )

    prev_len = window_to - window_from
    prev_rows = _fetch_rows(db, window_from - prev_len, window_from, None)
    prev_result = _netting_for(prev_rows)

    parsed: dict | None = None
    try:
        raw = chat_completion(settings, SYSTEM_PROMPT, payload_json)
        parsed = _parse_llm_response(raw)
    except (httpx.HTTPError, KeyError, ValueError):
        parsed = None

    if parsed is not None:
        summary = str(parsed["summary"])
        suggestions = [str(s) for s in parsed["suggestions"]][:4]
        db.add(
            InsightsCache(
                period=label,
                from_ms=window_from,
                to_ms=window_to,
                stats_hash=stats_hash,
                summary=summary,
                suggestions=suggestions,
                model=settings.llm_model,
                generated_at=now_ms,
            )
        )
        try:
            db.commit()
        except IntegrityError:
            # Concurrent request already cached this exact (period, window, hash) — harmless race.
            db.rollback()
        return InsightsResponse(
            period=label,
            from_ms=window_from,
            to_ms=window_to,
            summary=summary,
            suggestions=suggestions,
            llm_generated=True,
            cached=False,
            model=settings.llm_model,
            generated_at=now_ms,
        )

    # Gateway down/unparseable — never a 500. Fallback is never cached, so the next call retries
    # the gateway instead of being stuck behind a stale non-LLM summary.
    return InsightsResponse(
        period=label,
        from_ms=window_from,
        to_ms=window_to,
        summary=_fallback_summary(result.net_paise, income_paise, prev_result.net_paise),
        suggestions=[],
        llm_generated=False,
        cached=False,
        model=None,
        generated_at=now_ms,
    )
