import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from app.accounts import auto_link
from app.auth import Actor, get_actor, require_dashboard_or_agent
from app.db.base import get_db
from app.db.models import Account, Transaction
from app.schemas import (
    BulkAssignAccountRequest,
    BulkAssignAccountResponse,
    TransactionCreate,
    TransactionListResponse,
    TransactionPatch,
    TxnOutDetailed,
)

router = APIRouter(prefix="/v1/transactions", tags=["transactions"])


# --- static paths first: /subcategories and /bulk-assign-account must be registered before
# --- /{client_id}, or FastAPI would match those segments as a literal client_id.


@router.get("/subcategories", response_model=list[str])
def list_subcategories(
    db: Session = Depends(get_db), _actor: Actor = Depends(get_actor), category: str | None = None
) -> list[str]:
    """Distinct existing subcategory strings (optionally scoped to one top-level category), for
    dashboard autocomplete — keeps free-text tags from fragmenting ("Coffee" vs "coffee")."""
    stmt = select(Transaction.subcategory).where(Transaction.subcategory.isnot(None)).distinct()
    if category is not None:
        stmt = stmt.where(Transaction.category == category)
    rows = db.execute(stmt.order_by(Transaction.subcategory)).scalars().all()
    return list(rows)


@router.post("/bulk-assign-account", response_model=BulkAssignAccountResponse)
def bulk_assign_account(
    body: BulkAssignAccountRequest, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> BulkAssignAccountResponse:
    if db.get(Account, body.account_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")
    rows = db.execute(select(Transaction).where(Transaction.client_id.in_(body.client_ids))).scalars().all()
    found = {r.client_id for r in rows}
    now_ms = int(time.time() * 1000)
    for r in rows:
        r.account_id = body.account_id
        r.updated_at = now_ms
    db.commit()
    return BulkAssignAccountResponse(updated=len(rows), not_found=[c for c in body.client_ids if c not in found])


@router.get("", response_model=TransactionListResponse)
def list_transactions(
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    category: str | None = None,
    needs_review: bool | None = None,
    merchant: str | None = Query(default=None, description="substring match on merchant_raw/merchant_norm"),
    occurred_from: int | None = None,
    occurred_to: int | None = None,
    account_id: str | None = None,
    subcategory: str | None = None,
    spend_type: str | None = Query(default=None, pattern="^(NORMAL|SPECIAL)$"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
) -> TransactionListResponse:
    stmt = select(Transaction)
    if category is not None:
        stmt = stmt.where(Transaction.category == category)
    if subcategory is not None:
        stmt = stmt.where(Transaction.subcategory == subcategory)
    if spend_type is not None:
        stmt = stmt.where(Transaction.spend_type == spend_type)
    if needs_review is not None:
        stmt = stmt.where(Transaction.needs_review == needs_review)
    if merchant is not None:
        like = f"%{merchant.lower()}%"
        stmt = stmt.where(
            or_(func.lower(Transaction.merchant_raw).like(like), func.lower(Transaction.merchant_norm).like(like))
        )
    if occurred_from is not None:
        stmt = stmt.where(Transaction.occurred_at >= occurred_from)
    if occurred_to is not None:
        stmt = stmt.where(Transaction.occurred_at <= occurred_to)
    if account_id is not None:
        stmt = stmt.where(Transaction.account_id == account_id)

    total = db.execute(select(func.count()).select_from(stmt.subquery())).scalar_one()
    rows = (
        db.execute(stmt.order_by(Transaction.occurred_at.desc()).limit(limit).offset(offset)).scalars().all()
    )
    return TransactionListResponse(total=total, items=[TxnOutDetailed.model_validate(r) for r in rows])


@router.get("/{client_id}", response_model=TxnOutDetailed)
def get_transaction(
    client_id: str, db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)
) -> TxnOutDetailed:
    row = db.get(Transaction, client_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transaction not found")
    return TxnOutDetailed.model_validate(row)


@router.patch("/{client_id}", response_model=TxnOutDetailed)
def patch_transaction(
    client_id: str,
    body: TransactionPatch,
    db: Session = Depends(get_db),
    actor: Actor = Depends(require_dashboard_or_agent),
) -> TxnOutDetailed:
    row = db.get(Transaction, client_id)
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Transaction not found")

    now_ms = int(time.time() * 1000)
    # Both the dashboard and an AI agent acting on the user's behalf write at the same trust tier
    # — see docs/SYNC.md conflict rules. Neither can ever clobber a phone-side USER/MEMORY edit
    # made more recently; the sync endpoint's `beats()` check enforces that on the phone's next
    # push, and a direct edit here always represents the current human/agent intent regardless of
    # what an older phone-side row said, so we apply it unconditionally and just record the tier.
    if body.category is not None:
        row.category = body.category
        row.category_source = "DASHBOARD"
        row.needs_review = False
    if body.merchant_raw is not None:
        row.merchant_raw = body.merchant_raw
    if body.needs_review is not None:
        row.needs_review = body.needs_review
    # subcategory is the one field where "omitted" and "explicit clear" must be distinguished
    # (an empty string clears it — see TransactionPatch._subcategory) — every other field here
    # treats None as "don't touch", but that leaves no way to unset subcategory without this.
    if "subcategory" in body.model_fields_set:
        row.subcategory = body.subcategory
    # spend_type, like subcategory, distinguishes omitted from explicit-clear (empty string).
    if "spend_type" in body.model_fields_set:
        row.spend_type = body.spend_type
    if body.account_id is not None:
        if db.get(Account, body.account_id) is None:
            raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")
        row.account_id = body.account_id
    row.updated_at = now_ms

    db.commit()
    db.refresh(row)
    return TxnOutDetailed.model_validate(row)


@router.delete("/{client_id}/account", response_model=TxnOutDetailed)
def unlink_transaction_account(
    client_id: str, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> TxnOutDetailed:
    row = db.get(Transaction, client_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Transaction not found")
    row.account_id = None
    row.updated_at = int(time.time() * 1000)
    db.commit()
    db.refresh(row)
    return TxnOutDetailed.model_validate(row)


@router.post("", response_model=TxnOutDetailed, status_code=status.HTTP_201_CREATED)
def create_transaction(
    body: TransactionCreate,
    db: Session = Depends(get_db),
    actor: Actor = Depends(require_dashboard_or_agent),
) -> TxnOutDetailed:
    if body.account_id is not None and db.get(Account, body.account_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")

    now_ms = int(time.time() * 1000)
    row = Transaction(
        client_id=str(uuid.uuid4()),
        device_id=actor.device_id,
        account_id=body.account_id,
        occurred_at=body.occurred_at,
        amount_paise=body.amount_paise,
        direction=body.direction,
        channel=body.channel,
        merchant_norm=None,
        merchant_raw=body.merchant_raw,
        account_tail=body.account_tail,
        reference=body.reference,
        balance_paise=None,
        category=body.category,
        category_source="DASHBOARD",
        needs_review=False,
        updated_at=now_ms,
        created_at=now_ms,
    )
    if row.account_id is None:
        auto_link(db, row)
    db.add(row)
    db.commit()
    db.refresh(row)
    return TxnOutDetailed.model_validate(row)
