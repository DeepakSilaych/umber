import bisect
import time
from collections import defaultdict
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.auth import Actor, get_actor
from app.categories import INCOME
from app.db.base import get_db
from app.db.models import Transaction
from app.netting import MerchantCredit, MerchantDebit, apply as apply_netting
from app.schemas import (
    ChannelBreakdownItem,
    ChannelBreakdownResponse,
    MerchantBreakdownItem,
    MerchantBreakdownResponse,
    StatsResponse,
    SubcategoryBreakdownItem,
    SubcategoryBreakdownResponse,
    TimelineBucket,
    TimelineResponse,
    TimePatternBucket,
    TimePatternResponse,
)

router = APIRouter(prefix="/v1", tags=["stats"])

# Single-user app, India-only bank SMS parsing — calendar periods (this_month/this_year) are
# anchored to IST, matching how the phone itself buckets "today" for its home-screen widget.
TZ = ZoneInfo("Asia/Kolkata")

CALENDAR_PERIODS = {"today", "this_month", "this_year"}
ROLLING_PERIODS = {"7d": 7, "30d": 30}

# Covers >1yr daily, ~8yr weekly, ~33yr monthly — a personal ledger never legitimately needs more.
MAX_BUCKETS = 400

DAY_LABELS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def _period_bounds(period: str, now_ms: int) -> tuple[int, int]:
    now = datetime.fromtimestamp(now_ms / 1000, tz=TZ)
    if period == "today":
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    elif period == "this_month":
        start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    elif period == "this_year":
        start = now.replace(month=1, day=1, hour=0, minute=0, second=0, microsecond=0)
    elif period in ROLLING_PERIODS:
        return now_ms - ROLLING_PERIODS[period] * 24 * 60 * 60 * 1000, now_ms
    else:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Unknown period '{period}'. Use one of: today, 7d, 30d, this_month, this_year — or pass from_ms/to_ms.",
        )
    return int(start.timestamp() * 1000), now_ms


def _resolve_window(period: str, from_ms: int | None, to_ms: int | None, now_ms: int) -> tuple[int, int, str]:
    if from_ms is not None and to_ms is not None:
        return from_ms, to_ms, "custom"
    start, end = _period_bounds(period, now_ms)
    return start, end, period


def _fetch_rows(db: Session, window_from: int, window_to: int, account_id: str | None) -> list[Transaction]:
    stmt = select(Transaction).where(Transaction.occurred_at >= window_from, Transaction.occurred_at <= window_to)
    if account_id is not None:
        stmt = stmt.where(Transaction.account_id == account_id)
    return db.execute(stmt).scalars().all()


def _netting_for(rows: list[Transaction]):
    debits = [
        MerchantDebit(merchant_norm=r.merchant_norm, category=r.category, paise=r.amount_paise)
        for r in rows
        if r.direction == "DEBIT"
    ]
    # A credit deliberately filed as Income by the user (not just left at the unclassified
    # default) can never be treated as a reimbursement — see docs/ARCHITECTURE.md "Reimbursement
    # netting". Credits with no merchant can't be matched to a counterparty at all.
    credits = [
        MerchantCredit(merchant_norm=r.merchant_norm, paise=r.amount_paise)
        for r in rows
        if r.direction == "CREDIT"
        and r.merchant_norm
        and not (r.category == INCOME and r.category_source in ("USER", "MEMORY"))
    ]
    return apply_netting(debits, credits)


@router.get("/stats", response_model=StatsResponse)
def stats(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    period: str = Query(default="this_month"),
    from_ms: int | None = None,
    to_ms: int | None = None,
    account_id: str | None = None,
) -> StatsResponse:
    now_ms = int(time.time() * 1000)
    window_from, window_to, label = _resolve_window(period, from_ms, to_ms, now_ms)

    rows = _fetch_rows(db, window_from, window_to, account_id)
    result = _netting_for(rows)
    income_paise = sum(r.amount_paise for r in rows if r.direction == "CREDIT" and r.category == INCOME)
    needs_review_count = sum(1 for r in rows if r.needs_review)

    return StatsResponse(
        period=label,
        from_ms=window_from,
        to_ms=window_to,
        account_id=account_id,
        gross_spend_paise=result.gross_paise,
        reimbursed_paise=result.reimbursed_paise,
        net_spend_paise=result.net_paise,
        income_paise=income_paise,
        net_by_category=result.net_by_category,
        transaction_count=len(rows),
        needs_review_count=needs_review_count,
    )


# --- Timeline -----------------------------------------------------------------


def _step(dt: datetime, granularity: str) -> datetime:
    if granularity == "day":
        return dt + timedelta(days=1)
    if granularity == "week":
        return dt + timedelta(days=7)
    # month: calendar-correct, no dateutil dependency
    month = dt.month - 1 + 1
    year = dt.year + month // 12
    month = month % 12 + 1
    return dt.replace(year=year, month=month)


def _bucket_boundaries(window_from: int, window_to: int, granularity: str) -> list[datetime]:
    start_dt = datetime.fromtimestamp(window_from / 1000, tz=TZ)
    if granularity == "day":
        cur = start_dt.replace(hour=0, minute=0, second=0, microsecond=0)
    elif granularity == "week":
        floored = start_dt.replace(hour=0, minute=0, second=0, microsecond=0)
        cur = floored - timedelta(days=floored.weekday())  # Monday-anchored
    elif granularity == "month":
        cur = start_dt.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    else:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST, f"Unknown granularity '{granularity}'. Use one of: day, week, month."
        )

    boundaries = [cur]
    while int(boundaries[-1].timestamp() * 1000) <= window_to:
        if len(boundaries) > MAX_BUCKETS:
            raise HTTPException(
                status.HTTP_400_BAD_REQUEST,
                f"Date range too large for granularity '{granularity}' (>{MAX_BUCKETS} buckets). "
                "Widen the granularity or narrow the range.",
            )
        boundaries.append(_step(boundaries[-1], granularity))
    return boundaries  # N+1 boundaries define N buckets


@router.get("/stats/timeline", response_model=TimelineResponse)
def timeline(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    period: str = Query(default="this_month"),
    from_ms: int | None = None,
    to_ms: int | None = None,
    granularity: str = Query(default="day", pattern="^(day|week|month)$"),
    group_by: str | None = Query(default=None, pattern="^(category|merchant|channel)$"),
    top_n: int = Query(default=8, ge=1, le=20),
    account_id: str | None = Query(
        default=None,
        description="Reserved for the forthcoming accounts feature filter; already wired here.",
    ),
) -> TimelineResponse:
    now_ms = int(time.time() * 1000)
    window_from, window_to, _label = _resolve_window(period, from_ms, to_ms, now_ms)
    rows = _fetch_rows(db, window_from, window_to, account_id)

    boundaries = _bucket_boundaries(window_from, window_to, granularity)
    boundary_ms = [int(b.timestamp() * 1000) for b in boundaries]
    num_buckets = len(boundary_ms) - 1

    rows_by_bucket: list[list[Transaction]] = [[] for _ in range(num_buckets)]
    for r in rows:
        idx = bisect.bisect_right(boundary_ms, r.occurred_at) - 1
        idx = max(0, min(idx, num_buckets - 1))
        rows_by_bucket[idx].append(r)

    series_keys: list[str] | None = None
    if group_by is not None:
        totals: dict[str, int] = defaultdict(int)
        for r in rows:
            if r.direction != "DEBIT":
                continue
            key = r.category if group_by == "category" else (r.merchant_norm if group_by == "merchant" else r.channel)
            totals[key or "(none)"] += r.amount_paise
        series_keys = [k for k, _ in sorted(totals.items(), key=lambda kv: -kv[1])[:top_n]]

    buckets: list[TimelineBucket] = []
    for i in range(num_buckets):
        bucket_rows = rows_by_bucket[i]
        result = _netting_for(bucket_rows)
        income_paise = sum(r.amount_paise for r in bucket_rows if r.direction == "CREDIT" and r.category == INCOME)
        needs_review_count = sum(1 for r in bucket_rows if r.needs_review)

        breakdown: dict[str, int] | None = None
        if group_by == "category":
            breakdown = {k: 0 for k in series_keys}
            other = 0
            for cat, amt in result.net_by_category.items():
                if cat in breakdown:
                    breakdown[cat] += amt
                else:
                    other += amt
            if other:
                breakdown["Other"] = breakdown.get("Other", 0) + other
        elif group_by in ("merchant", "channel"):
            breakdown = {k: 0 for k in series_keys}
            other = 0
            for r in bucket_rows:
                if r.direction != "DEBIT":
                    continue
                key = r.merchant_norm if group_by == "merchant" else r.channel
                key = key or "(none)"
                if key in breakdown:
                    breakdown[key] += r.amount_paise
                else:
                    other += r.amount_paise
            if other:
                breakdown["Other"] = breakdown.get("Other", 0) + other

        buckets.append(
            TimelineBucket(
                bucket_start_ms=int(boundaries[i].timestamp() * 1000),
                bucket_end_ms=int(boundaries[i + 1].timestamp() * 1000),
                gross_spend_paise=result.gross_paise,
                reimbursed_paise=result.reimbursed_paise,
                net_spend_paise=result.net_paise,
                income_paise=income_paise,
                transaction_count=len(bucket_rows),
                needs_review_count=needs_review_count,
                breakdown=breakdown,
            )
        )

    return TimelineResponse(
        from_ms=window_from,
        to_ms=window_to,
        granularity=granularity,
        group_by=group_by,
        account_id=account_id,
        series_keys=series_keys,
        buckets=buckets,
    )


# --- Granular breakdowns --------------------------------------------------------


@router.get("/stats/by-merchant", response_model=MerchantBreakdownResponse)
def by_merchant(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    period: str = Query(default="this_month"),
    from_ms: int | None = None,
    to_ms: int | None = None,
    direction: str = Query(default="DEBIT", pattern="^(DEBIT|CREDIT)$"),
    limit: int = Query(default=10, ge=1, le=100),
    account_id: str | None = Query(default=None),
) -> MerchantBreakdownResponse:
    now_ms = int(time.time() * 1000)
    window_from, window_to, label = _resolve_window(period, from_ms, to_ms, now_ms)
    rows = _fetch_rows(db, window_from, window_to, account_id)

    totals: dict[str | None, list[int]] = defaultdict(lambda: [0, 0])  # [amount, count]
    for r in rows:
        if r.direction != direction:
            continue
        entry = totals[r.merchant_norm]
        entry[0] += r.amount_paise
        entry[1] += 1

    ranked = sorted(totals.items(), key=lambda kv: -kv[1][0])
    top, rest = ranked[:limit], ranked[limit:]

    return MerchantBreakdownResponse(
        period=label,
        from_ms=window_from,
        to_ms=window_to,
        direction=direction,
        items=[MerchantBreakdownItem(merchant_norm=k, amount_paise=v[0], transaction_count=v[1]) for k, v in top],
        other_paise=sum(v[0] for _, v in rest),
        other_transaction_count=sum(v[1] for _, v in rest),
    )


@router.get("/stats/by-channel", response_model=ChannelBreakdownResponse)
def by_channel(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    period: str = Query(default="this_month"),
    from_ms: int | None = None,
    to_ms: int | None = None,
    direction: str = Query(default="DEBIT", pattern="^(DEBIT|CREDIT)$"),
    account_id: str | None = Query(default=None),
) -> ChannelBreakdownResponse:
    now_ms = int(time.time() * 1000)
    window_from, window_to, label = _resolve_window(period, from_ms, to_ms, now_ms)

    stmt = select(Transaction.channel, func.sum(Transaction.amount_paise), func.count()).where(
        Transaction.occurred_at >= window_from,
        Transaction.occurred_at <= window_to,
        Transaction.direction == direction,
    )
    if account_id is not None:
        stmt = stmt.where(Transaction.account_id == account_id)
    stmt = stmt.group_by(Transaction.channel)

    rows = db.execute(stmt).all()
    items = sorted(
        (ChannelBreakdownItem(channel=ch, amount_paise=amt, transaction_count=cnt) for ch, amt, cnt in rows),
        key=lambda it: -it.amount_paise,
    )
    return ChannelBreakdownResponse(period=label, from_ms=window_from, to_ms=window_to, direction=direction, items=items)


@router.get("/stats/by-time-pattern", response_model=TimePatternResponse)
def by_time_pattern(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    period: str = Query(default="this_month"),
    from_ms: int | None = None,
    to_ms: int | None = None,
    dimension: str = Query(default="day_of_week", pattern="^(day_of_week|hour_of_day)$"),
    direction: str = Query(default="DEBIT", pattern="^(DEBIT|CREDIT)$"),
    account_id: str | None = Query(default=None),
) -> TimePatternResponse:
    now_ms = int(time.time() * 1000)
    window_from, window_to, label = _resolve_window(period, from_ms, to_ms, now_ms)
    rows = _fetch_rows(db, window_from, window_to, account_id)

    num_buckets = 7 if dimension == "day_of_week" else 24
    totals = [[0, 0] for _ in range(num_buckets)]  # [amount, count]

    for r in rows:
        if r.direction != direction:
            continue
        local = datetime.fromtimestamp(r.occurred_at / 1000, tz=TZ)
        idx = local.weekday() if dimension == "day_of_week" else local.hour
        totals[idx][0] += r.amount_paise
        totals[idx][1] += 1

    buckets = [
        TimePatternBucket(
            bucket=i,
            label=DAY_LABELS[i] if dimension == "day_of_week" else f"{i:02d}:00",
            amount_paise=totals[i][0],
            transaction_count=totals[i][1],
        )
        for i in range(num_buckets)
    ]
    return TimePatternResponse(
        period=label, from_ms=window_from, to_ms=window_to, dimension=dimension, direction=direction, buckets=buckets
    )


@router.get("/stats/by-subcategory", response_model=SubcategoryBreakdownResponse)
def by_subcategory(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    period: str = Query(default="this_month"),
    from_ms: int | None = None,
    to_ms: int | None = None,
    category: str | None = None,
    direction: str = Query(default="DEBIT", pattern="^(DEBIT|CREDIT)$"),
) -> SubcategoryBreakdownResponse:
    now_ms = int(time.time() * 1000)
    window_from, window_to, label = _resolve_window(period, from_ms, to_ms, now_ms)

    stmt = select(
        Transaction.category, Transaction.subcategory, func.sum(Transaction.amount_paise), func.count()
    ).where(
        Transaction.occurred_at >= window_from,
        Transaction.occurred_at <= window_to,
        Transaction.direction == direction,
    )
    if category is not None:
        stmt = stmt.where(Transaction.category == category)
    stmt = stmt.group_by(Transaction.category, Transaction.subcategory)

    rows = db.execute(stmt).all()
    items = sorted(
        (
            SubcategoryBreakdownItem(category=cat, subcategory=sub, amount_paise=amt, transaction_count=cnt)
            for cat, sub, amt, cnt in rows
        ),
        key=lambda it: -it.amount_paise,
    )
    return SubcategoryBreakdownResponse(
        period=label, from_ms=window_from, to_ms=window_to, direction=direction, items=items
    )
