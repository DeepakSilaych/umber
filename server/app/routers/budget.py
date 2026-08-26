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
from app.categories import INCOME
from app.db.base import get_db
from app.db.models import BudgetBucket, BudgetConfig
from app.routers.stats import _fetch_rows, _netting_for, _resolve_window
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
    # Net-of-reimbursement spend per category, same basis as /v1/stats.
    net_by_category = _netting_for(rows).net_by_category
    # Total outflow excludes Income (an inflow category), matching how the savings bucket is defined.
    total_spent = sum(paise for cat, paise in net_by_category.items() if cat != INCOME)

    config = _get_config(db)
    buckets = _buckets(db)

    budgeted_categories: set[str] = set()
    progress: list[BudgetBucketProgress] = []
    for b in buckets:
        if b.kind == "savings":
            actual = config.monthly_income_paise - total_spent
        else:
            actual = sum(net_by_category.get(cat, 0) for cat in b.category_keys)
            budgeted_categories.update(b.category_keys)
        progress.append(
            BudgetBucketProgress(
                id=b.id,
                name=b.name,
                kind=b.kind,
                category_keys=b.category_keys,
                target_paise=b.monthly_target_paise,
                actual_paise=actual,
            )
        )

    # Spend in categories assigned to no bucket (excluding Income) — surfaces leakage like
    # Transfers/Other that isn't reflected in any budget line.
    unbudgeted = sum(
        paise
        for cat, paise in net_by_category.items()
        if cat != INCOME and cat not in budgeted_categories
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
