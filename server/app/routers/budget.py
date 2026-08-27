"""Monthly budget / financial goal. Bucket-based (matching the user's Obsidian plan) rather than
per-category — each bucket rolls up zero or more of the 15 fixed categories. `spend` buckets track
actual spend in their categories against a target; a `savings` bucket tracks leftover (income minus
total outflow) against its target."""

import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import Actor, get_actor, require_dashboard_or_agent
from app.db.base import get_db
from app.db.models import BudgetBucket, BudgetConfig
from app.routers.stats import _fetch_rows, _resolve_window
from app.schemas import (
    BudgetBucketIn,
    BudgetBucketOut,
    BudgetBucketPatch,
    BudgetBucketProgress,
    BudgetIncomePatch,
    BudgetOut,
    BudgetProgressResponse,
)

router = APIRouter(prefix="/v1/budget", tags=["budget"])


def _get_config(db: Session) -> BudgetConfig:
    config = db.get(BudgetConfig, 1)
    if config is None:
        config = BudgetConfig(id=1, monthly_income_paise=0, updated_at=int(time.time() * 1000))
        db.add(config)
        db.commit()
        db.refresh(config)
    return config


def _buckets(db: Session) -> list[BudgetBucket]:
    return (
        db.execute(select(BudgetBucket).order_by(BudgetBucket.sort_order, BudgetBucket.name))
        .scalars()
        .all()
    )


@router.get("", response_model=BudgetOut)
def get_budget(db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)) -> BudgetOut:
    config = _get_config(db)
    return BudgetOut(
        monthly_income_paise=config.monthly_income_paise,
        buckets=[BudgetBucketOut.model_validate(b) for b in _buckets(db)],
    )


@router.patch("", response_model=BudgetOut)
def patch_income(
    body: BudgetIncomePatch, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> BudgetOut:
    config = _get_config(db)
    config.monthly_income_paise = body.monthly_income_paise
    config.updated_at = int(time.time() * 1000)
    db.commit()
    return BudgetOut(
        monthly_income_paise=config.monthly_income_paise,
        buckets=[BudgetBucketOut.model_validate(b) for b in _buckets(db)],
    )


@router.post("/buckets", response_model=BudgetBucketOut, status_code=status.HTTP_201_CREATED)
def create_bucket(
    body: BudgetBucketIn, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> BudgetBucketOut:
    now_ms = int(time.time() * 1000)
    row = BudgetBucket(
        id=str(uuid.uuid4()),
        name=body.name,
        monthly_target_paise=body.monthly_target_paise,
        category_keys=body.category_keys,
        subcategory_keywords=body.subcategory_keywords,
        kind=body.kind,
        sort_order=body.sort_order,
        created_at=now_ms,
        updated_at=now_ms,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return BudgetBucketOut.model_validate(row)


@router.patch("/buckets/{bucket_id}", response_model=BudgetBucketOut)
def patch_bucket(
    bucket_id: str,
    body: BudgetBucketPatch,
    db: Session = Depends(get_db),
    _actor: Actor = Depends(require_dashboard_or_agent),
) -> BudgetBucketOut:
    row = db.get(BudgetBucket, bucket_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Budget bucket not found")
    if body.name is not None:
        row.name = body.name
    if body.monthly_target_paise is not None:
        row.monthly_target_paise = body.monthly_target_paise
    if body.category_keys is not None:
        row.category_keys = body.category_keys
    if body.subcategory_keywords is not None:
        row.subcategory_keywords = body.subcategory_keywords
    if body.kind is not None:
        row.kind = body.kind
    if body.sort_order is not None:
        row.sort_order = body.sort_order
    row.updated_at = int(time.time() * 1000)
    db.commit()
    db.refresh(row)
    return BudgetBucketOut.model_validate(row)


@router.delete("/buckets/{bucket_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_bucket(
    bucket_id: str, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> None:
    row = db.get(BudgetBucket, bucket_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Budget bucket not found")
    db.delete(row)
    db.commit()


@router.get("/progress", response_model=BudgetProgressResponse)
def budget_progress(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    period: str = Query(default="this_month"),
    from_ms: int | None = None,
    to_ms: int | None = None,
) -> BudgetProgressResponse:
    now_ms = int(time.time() * 1000)
    window_from, window_to, label = _resolve_window(period, from_ms, to_ms, now_ms)

    rows = _fetch_rows(db, window_from, window_to, None)
    config = _get_config(db)
    buckets = _buckets(db)
    spend_buckets = [b for b in buckets if b.kind == "spend"]

    # Per-transaction assignment (not category aggregates) so a bucket can match by sub-category —
    # e.g. a "Subscriptions" bucket keyworded on "streaming"/"mobile" pulls those out of Bills /
    # Entertainment, keeping "subscriptions" and "bills" genuinely separate. Gross debit basis:
    # a budget asks "how much went out per bucket", and per-transaction gross is what supports the
    # sub-category split (reimbursement netting is category-level and can't be split by subcategory).
    # First bucket in sort order wins, so each transaction counts toward at most one bucket.
    def _match(txn) -> BudgetBucket | None:
        sub = (txn.subcategory or "").lower()
        for b in spend_buckets:
            if txn.category in b.category_keys:
                return b
            if sub and any(kw in sub for kw in b.subcategory_keywords):
                return b
        return None

    actual_by_bucket: dict[str, int] = {b.id: 0 for b in spend_buckets}
    total_spent = 0
    unbudgeted = 0
    for r in rows:
        if r.direction != "DEBIT":
            continue
        total_spent += r.amount_paise
        b = _match(r)
        if b is None:
            unbudgeted += r.amount_paise
        else:
            actual_by_bucket[b.id] += r.amount_paise

    progress: list[BudgetBucketProgress] = []
    for b in buckets:
        actual = config.monthly_income_paise - total_spent if b.kind == "savings" else actual_by_bucket[b.id]
        progress.append(
            BudgetBucketProgress(
                id=b.id,
                name=b.name,
                kind=b.kind,
                category_keys=b.category_keys,
                subcategory_keywords=b.subcategory_keywords,
                target_paise=b.monthly_target_paise,
                actual_paise=actual,
            )
        )

    return BudgetProgressResponse(
        period=label,
        from_ms=window_from,
        to_ms=window_to,
        monthly_income_paise=config.monthly_income_paise,
        total_spent_paise=total_spent,
        unbudgeted_paise=unbudgeted,
        buckets=progress,
    )
