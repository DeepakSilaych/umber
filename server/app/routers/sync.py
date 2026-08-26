import time

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.accounts import auto_link
from app.auth import Actor, require_phone
from app.categories import beats
from app.db.base import get_db
from app.db.models import Transaction
from app.schemas import RejectedTxn, SyncRequest, SyncResponse, TxnOut

router = APIRouter(prefix="/v1", tags=["sync"])

# Generous single-user ceiling — see docs/SYNC.md amendment notes on why the pull side doesn't
# paginate: a personal ledger realistically never produces more than a few thousand changed rows
# between two sync rounds.
PULL_LIMIT = 5000


@router.post("/sync", response_model=SyncResponse)
def sync(
    body: SyncRequest,
    db: Session = Depends(get_db),
    actor: Actor = Depends(require_phone),
) -> SyncResponse:
    if body.device_id != actor.device_id:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="device_id does not match the authenticated device",
        )

    now_ms = int(time.time() * 1000)
    applied = 0
    rejected: list[RejectedTxn] = []

    for txn in body.transactions:
        existing = db.get(Transaction, txn.client_id)

        if existing is None:
            if txn.reference:
                collision = db.execute(
                    select(Transaction).where(Transaction.reference == txn.reference)
                ).scalar_one_or_none()
                if collision is not None:
                    rejected.append(RejectedTxn(client_id=txn.client_id, reason="duplicate_reference"))
                    continue

            new_row = Transaction(
                client_id=txn.client_id,
                device_id=actor.device_id,
                occurred_at=txn.occurred_at,
                amount_paise=txn.amount_paise,
                direction=txn.direction,
                channel=txn.channel,
                merchant_norm=txn.merchant_norm,
                merchant_raw=txn.merchant_raw,
                account_tail=txn.account_tail,
                reference=txn.reference,
                balance_paise=txn.balance_paise,
                category=txn.category,
                category_source=txn.category_source,
                needs_review=False,
                updated_at=txn.updated_at,
                created_at=now_ms,
            )
            auto_link(db, new_row)
            db.add(new_row)
            applied += 1
            continue

        # A user's own correction always wins — see docs/SYNC.md "Conflict rules".
        if beats(txn.category_source, txn.updated_at, existing.category_source, existing.updated_at):
            existing.occurred_at = txn.occurred_at
            existing.amount_paise = txn.amount_paise
            existing.direction = txn.direction
            existing.channel = txn.channel
            existing.merchant_norm = txn.merchant_norm
            existing.merchant_raw = txn.merchant_raw
            existing.account_tail = txn.account_tail
            existing.reference = txn.reference
            existing.balance_paise = txn.balance_paise
            existing.category = txn.category
            existing.category_source = txn.category_source
            existing.updated_at = txn.updated_at
            auto_link(db, existing)
        applied += 1

    db.commit()

    pull_rows = (
        db.execute(
            select(Transaction)
            .where(Transaction.updated_at > body.since)
            .order_by(Transaction.updated_at.asc())
            .limit(PULL_LIMIT)
        )
        .scalars()
        .all()
    )

    return SyncResponse(
        cursor=now_ms,
        applied=applied,
        rejected=rejected,
        transactions=[TxnOut.model_validate(row) for row in pull_rows],
    )
