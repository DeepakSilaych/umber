import time
import uuid
from decimal import Decimal

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app.auth import Actor, get_actor, require_dashboard_or_agent
from app.db.base import get_db
from app.db.models import Holding, HoldingTransaction
from app.holdings import BUY, SELL, VALUATION_UPDATE
from app.schemas import (
    HoldingCreate,
    HoldingKindAllocation,
    HoldingListResponse,
    HoldingOut,
    HoldingPatch,
    HoldingsSummaryResponse,
    HoldingTimelinePoint,
    HoldingTimelineResponse,
    HoldingTransactionCreate,
    HoldingTransactionListResponse,
    HoldingTransactionOut,
)

router = APIRouter(prefix="/v1/holdings", tags=["holdings"])


def _is_unit_tracked(db: Session, holding: Holding) -> bool | None:
    """True/False once established; None if undetermined (no BUY/SELL logged yet)."""
    if holding.units is not None:
        return True
    has_prior = db.execute(
        select(func.count()).select_from(HoldingTransaction).where(
            HoldingTransaction.holding_id == holding.id, HoldingTransaction.type.in_((BUY, SELL))
        )
    ).scalar_one()
    return False if has_prior > 0 else None


def _metrics(db: Session, holding: Holding) -> dict:
    total_buy, total_sell, realized = db.execute(
        select(
            func.coalesce(func.sum(case((HoldingTransaction.type == BUY, HoldingTransaction.amount_paise), else_=0)), 0),
            func.coalesce(func.sum(case((HoldingTransaction.type == SELL, HoldingTransaction.amount_paise), else_=0)), 0),
            func.coalesce(func.sum(case((HoldingTransaction.type == SELL, HoldingTransaction.realized_gain_paise), else_=0)), 0),
        ).where(HoldingTransaction.holding_id == holding.id)
    ).one()
    net_invested = int(total_buy) - int(total_sell)

    if holding.current_value_paise is None:
        unrealized = None
    elif holding.units is not None and holding.avg_cost_paise is not None:
        unrealized = holding.current_value_paise - int(holding.units * holding.avg_cost_paise)
    else:
        unrealized = holding.current_value_paise - net_invested

    return {"net_invested_paise": net_invested, "unrealized_gain_paise": unrealized, "realized_gain_paise": int(realized)}


def _to_out(db: Session, holding: Holding) -> HoldingOut:
    m = _metrics(db, holding)
    return HoldingOut(
        id=holding.id,
        name=holding.name,
        kind=holding.kind,
        units=holding.units,
        avg_cost_paise=holding.avg_cost_paise,
        current_value_paise=holding.current_value_paise,
        notes=holding.notes,
        created_at=holding.created_at,
        updated_at=holding.updated_at,
        **m,
    )


# --- static paths first: /summary and /timeline must be registered before /{holding_id} ---


@router.get("/summary", response_model=HoldingsSummaryResponse)
def holdings_summary(db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)) -> HoldingsSummaryResponse:
    holdings = db.execute(select(Holding)).scalars().all()
    total_value = total_invested = total_unrealized = total_realized = 0
    by_kind: dict[str, HoldingKindAllocation] = {}

    for h in holdings:
        m = _metrics(db, h)
        total_invested += m["net_invested_paise"]
        total_realized += m["realized_gain_paise"]
        if h.current_value_paise is not None:
            total_value += h.current_value_paise
        if m["unrealized_gain_paise"] is not None:
            total_unrealized += m["unrealized_gain_paise"]

        alloc = by_kind.setdefault(h.kind, HoldingKindAllocation(current_value_paise=0, invested_paise=0, count=0))
        alloc.count += 1
        alloc.invested_paise += m["net_invested_paise"]
        if h.current_value_paise is not None:
            alloc.current_value_paise += h.current_value_paise

    return HoldingsSummaryResponse(
        total_current_value_paise=total_value,
        total_invested_paise=total_invested,
        total_unrealized_gain_paise=total_unrealized,
        total_realized_gain_paise=total_realized,
        by_kind=by_kind,
    )


@router.get("/timeline", response_model=HoldingTimelineResponse)
def holdings_timeline(db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)) -> HoldingTimelineResponse:
    all_txns = db.execute(select(HoldingTransaction).order_by(HoldingTransaction.occurred_at.asc())).scalars().all()
    valuation_timestamps = sorted({t.occurred_at for t in all_txns if t.type == VALUATION_UPDATE})

    by_holding: dict[str, list[HoldingTransaction]] = {}
    for t in all_txns:
        by_holding.setdefault(t.holding_id, []).append(t)

    points: list[HoldingTimelinePoint] = []
    for ts in valuation_timestamps:
        total = 0
        estimated = False
        for _holding_id, txns in by_holding.items():
            relevant = [t for t in txns if t.occurred_at <= ts]
            if not relevant:
                continue
            valuations = [t for t in relevant if t.type == VALUATION_UPDATE]
            if valuations:
                total += valuations[-1].amount_paise  # latest known real valuation at/before ts
            else:
                net = sum(t.amount_paise for t in relevant if t.type == BUY) - sum(
                    t.amount_paise for t in relevant if t.type == SELL
                )
                total += net
                estimated = True
        points.append(HoldingTimelinePoint(occurred_at=ts, total_value_paise=total, is_estimated=estimated))

    return HoldingTimelineResponse(points=points)


@router.get("", response_model=HoldingListResponse)
def list_holdings(db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)) -> HoldingListResponse:
    rows = db.execute(select(Holding).order_by(Holding.name)).scalars().all()
    return HoldingListResponse(items=[_to_out(db, r) for r in rows])


@router.post("", response_model=HoldingOut, status_code=status.HTTP_201_CREATED)
def create_holding(
    body: HoldingCreate, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> HoldingOut:
    now_ms = int(time.time() * 1000)
    row = Holding(
        id=str(uuid.uuid4()),
        name=body.name,
        kind=body.kind,
        units=None,
        avg_cost_paise=None,
        current_value_paise=None,
        notes=body.notes,
        created_at=now_ms,
        updated_at=now_ms,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return _to_out(db, row)


@router.get("/{holding_id}", response_model=HoldingOut)
def get_holding(holding_id: str, db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)) -> HoldingOut:
    row = db.get(Holding, holding_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Holding not found")
    return _to_out(db, row)


@router.patch("/{holding_id}", response_model=HoldingOut)
def patch_holding(
    holding_id: str, body: HoldingPatch, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> HoldingOut:
    row = db.get(Holding, holding_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Holding not found")
    if body.name is not None:
        row.name = body.name
    if body.kind is not None:
        row.kind = body.kind
    if body.notes is not None:
        row.notes = body.notes
    row.updated_at = int(time.time() * 1000)
    db.commit()
    db.refresh(row)
    return _to_out(db, row)


@router.delete("/{holding_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_holding(
    holding_id: str, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> None:
    row = db.get(Holding, holding_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Holding not found")
    db.delete(row)  # ON DELETE CASCADE removes its holding_transactions
    db.commit()


@router.get("/{holding_id}/transactions", response_model=HoldingTransactionListResponse)
def list_holding_transactions(
    holding_id: str, db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)
) -> HoldingTransactionListResponse:
    if db.get(Holding, holding_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Holding not found")
    rows = (
        db.execute(
            select(HoldingTransaction)
            .where(HoldingTransaction.holding_id == holding_id)
            .order_by(HoldingTransaction.occurred_at.asc())
        )
        .scalars()
        .all()
    )
    return HoldingTransactionListResponse(items=[HoldingTransactionOut.model_validate(r) for r in rows])


@router.post("/{holding_id}/transactions", response_model=HoldingTransactionOut, status_code=status.HTTP_201_CREATED)
def add_holding_transaction(
    holding_id: str,
    body: HoldingTransactionCreate,
    db: Session = Depends(get_db),
    _actor: Actor = Depends(require_dashboard_or_agent),
) -> HoldingTransactionOut:
    holding = db.get(Holding, holding_id)
    if holding is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Holding not found")

    now_ms = int(time.time() * 1000)
    realized_gain_paise: int | None = None

    if body.type == VALUATION_UPDATE:
        if body.units is not None or body.price_paise is not None:
            raise HTTPException(422, "VALUATION_UPDATE must not include units or price_paise")
        holding.current_value_paise = body.amount_paise

    elif body.type == BUY:
        # price_paise only makes sense paired with units — a value-tracked instrument (FD, PPF,
        # gold-as-value) has no per-unit price concept, just a cash amount.
        unit_tracked = _is_unit_tracked(db, holding)
        if unit_tracked is True and body.units is None:
            raise HTTPException(422, "This holding is unit-tracked; BUY requires units")
        if unit_tracked is False and body.units is not None:
            raise HTTPException(422, "This holding is value-tracked (no units); BUY must omit units")
        if body.units is not None:
            if body.price_paise is None:
                raise HTTPException(422, "BUY with units requires price_paise")
            old_units = holding.units or Decimal(0)
            old_cost_total = (holding.avg_cost_paise or Decimal(0)) * old_units
            new_units = old_units + body.units
            holding.avg_cost_paise = (old_cost_total + Decimal(body.amount_paise)) / new_units
            holding.units = new_units

    elif body.type == SELL:
        unit_tracked = _is_unit_tracked(db, holding)
        if unit_tracked is None:
            raise HTTPException(422, "Cannot SELL before any BUY has established this holding")
        if unit_tracked and body.units is None:
            raise HTTPException(422, "This holding is unit-tracked; SELL requires units")
        if not unit_tracked and body.units is not None:
            raise HTTPException(422, "This holding is value-tracked (no units); SELL must omit units")
        if unit_tracked:
            if body.price_paise is None:
                raise HTTPException(422, "SELL with units requires price_paise")
            if body.units > holding.units:
                raise HTTPException(422, "Cannot sell more units than currently held")
            if holding.avg_cost_paise is not None:
                realized_gain_paise = int((body.price_paise - holding.avg_cost_paise) * body.units)
            holding.units -= body.units
            # avg_cost_paise deliberately unchanged — cost basis of remaining units doesn't move.
        # value-tracked SELL: no per-sale realized_gain_paise; net_invested_paise (computed live)
        # simply drops by amount_paise.

    holding.updated_at = now_ms

    row = HoldingTransaction(
        id=str(uuid.uuid4()),
        holding_id=holding_id,
        type=body.type,
        units=body.units,
        price_paise=body.price_paise,
        amount_paise=body.amount_paise,
        realized_gain_paise=realized_gain_paise,
        occurred_at=body.occurred_at,
        created_at=now_ms,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return HoldingTransactionOut.model_validate(row)
