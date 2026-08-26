import time
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app.accounts import find_unique_match
from app.auth import Actor, get_actor, require_dashboard_or_agent
from app.db.base import get_db
from app.db.models import Account, Transaction
from app.schemas import (
    AccountBalanceResponse,
    AccountCreate,
    AccountListResponse,
    AccountOut,
    AccountPatch,
    RelinkAccountsResponse,
)

router = APIRouter(prefix="/v1/accounts", tags=["accounts"])


def _balance_paise(db: Session, account: Account, as_of_ms: int) -> int:
    signed = case((Transaction.direction == "CREDIT", Transaction.amount_paise), else_=-Transaction.amount_paise)
    delta = db.execute(
        select(func.coalesce(func.sum(signed), 0)).where(
            Transaction.account_id == account.id,
            Transaction.occurred_at > account.opening_balance_as_of,
            Transaction.occurred_at <= as_of_ms,
        )
    ).scalar_one()
    return account.opening_balance_paise + delta


def _to_out(db: Session, account: Account, now_ms: int) -> AccountOut:
    return AccountOut(
        id=account.id,
        label=account.label,
        bank_name=account.bank_name,
        kind=account.kind,
        account_tail=account.account_tail,
        opening_balance_paise=account.opening_balance_paise,
        opening_balance_as_of=account.opening_balance_as_of,
        balance_paise=_balance_paise(db, account, now_ms),
        created_at=account.created_at,
        updated_at=account.updated_at,
    )


# NOTE: /relink is registered before /{account_id} — a dynamic path segment would otherwise
# shadow it and FastAPI would treat "relink" as an account id.
@router.post("/relink", response_model=RelinkAccountsResponse)
def relink_transactions(
    db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> RelinkAccountsResponse:
    """Idempotent backfill for pre-existing rows and for retroactively matching a newly created
    Account against transaction history. Safe to call repeatedly."""
    candidates = (
        db.execute(select(Transaction).where(Transaction.account_id.is_(None), Transaction.account_tail.isnot(None)))
        .scalars()
        .all()
    )
    linked = ambiguous = no_match = 0
    for txn in candidates:
        matches_count = db.execute(
            select(func.count()).select_from(Account).where(Account.account_tail == txn.account_tail)
        ).scalar_one()
        match = find_unique_match(db, txn.account_tail) if txn.account_tail else None
        if match is not None:
            txn.account_id = match
            linked += 1
        elif matches_count == 0:
            no_match += 1
        else:
            ambiguous += 1
    db.commit()
    return RelinkAccountsResponse(scanned=len(candidates), linked=linked, ambiguous=ambiguous, no_match=no_match)


@router.get("", response_model=AccountListResponse)
def list_accounts(db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)) -> AccountListResponse:
    now_ms = int(time.time() * 1000)
    rows = db.execute(select(Account).order_by(Account.label)).scalars().all()
    return AccountListResponse(items=[_to_out(db, r, now_ms) for r in rows])


@router.post("", response_model=AccountOut, status_code=status.HTTP_201_CREATED)
def create_account(
    body: AccountCreate, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> AccountOut:
    now_ms = int(time.time() * 1000)
    row = Account(
        id=str(uuid.uuid4()),
        label=body.label,
        bank_name=body.bank_name,
        kind=body.kind,
        account_tail=body.account_tail,
        opening_balance_paise=body.opening_balance_paise,
        opening_balance_as_of=body.opening_balance_as_of if body.opening_balance_as_of is not None else now_ms,
        created_at=now_ms,
        updated_at=now_ms,
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return _to_out(db, row, now_ms)


@router.get("/{account_id}", response_model=AccountOut)
def get_account(account_id: str, db: Session = Depends(get_db), _actor: Actor = Depends(get_actor)) -> AccountOut:
    row = db.get(Account, account_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")
    return _to_out(db, row, int(time.time() * 1000))


@router.patch("/{account_id}", response_model=AccountOut)
def patch_account(
    account_id: str, body: AccountPatch, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> AccountOut:
    row = db.get(Account, account_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")
    if body.label is not None:
        row.label = body.label
    if body.bank_name is not None:
        row.bank_name = body.bank_name
    if body.kind is not None:
        row.kind = body.kind
    if body.account_tail is not None:
        row.account_tail = body.account_tail
    if body.opening_balance_paise is not None:
        row.opening_balance_paise = body.opening_balance_paise
    if body.opening_balance_as_of is not None:
        row.opening_balance_as_of = body.opening_balance_as_of
    now_ms = int(time.time() * 1000)
    row.updated_at = now_ms
    db.commit()
    db.refresh(row)
    return _to_out(db, row, now_ms)


@router.delete("/{account_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_account(
    account_id: str, db: Session = Depends(get_db), _actor: Actor = Depends(require_dashboard_or_agent)
) -> None:
    row = db.get(Account, account_id)
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")
    db.delete(row)  # transactions.account_id has ON DELETE SET NULL — history is never touched
    db.commit()


@router.get("/{account_id}/balance", response_model=AccountBalanceResponse)
def account_balance(
    account_id: str,
    db: Session = Depends(get_db),
    _actor: Actor = Depends(get_actor),
    as_of: int | None = Query(default=None, description="ms epoch; defaults to now"),
) -> AccountBalanceResponse:
    account = db.get(Account, account_id)
    if account is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")
    as_of_ms = as_of if as_of is not None else int(time.time() * 1000)

    signed = case((Transaction.direction == "CREDIT", Transaction.amount_paise), else_=-Transaction.amount_paise)
    delta, count = db.execute(
        select(func.coalesce(func.sum(signed), 0), func.count()).where(
            Transaction.account_id == account_id,
            Transaction.occurred_at > account.opening_balance_as_of,
            Transaction.occurred_at <= as_of_ms,
        )
    ).one()

    return AccountBalanceResponse(
        account_id=account_id,
        as_of=as_of_ms,
        opening_balance_paise=account.opening_balance_paise,
        balance_paise=account.opening_balance_paise + delta,
        transaction_count=count,
    )
