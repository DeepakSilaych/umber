import time
import uuid
from collections import defaultdict
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, Form, HTTPException, UploadFile, status
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.auth import Actor, require_dashboard_or_agent
from app.categories import OTHER
from app.db.base import get_db
from app.db.models import Account, Transaction
from app.parsing.statement import parse_statement
from app.schemas import StatementImportResponse

router = APIRouter(prefix="/v1/statements", tags=["statements"])

TZ = ZoneInfo("Asia/Kolkata")


def _day_bucket(occurred_at_ms: int) -> str:
    return datetime.fromtimestamp(occurred_at_ms / 1000, tz=TZ).strftime("%Y-%m-%d")


def _latest_known_category(db: Session, merchant_norm: str | None) -> str | None:
    """Server-side stand-in for the phone's merchant-memory layer 1: the most recent
    confirmed category already on file for this merchant, if any.

    "Confirmed" means a human picked it — USER/MEMORY (phone) or DASHBOARD (this same web UI's
    own `PATCH /v1/transactions/{id}` category edit, or a prior statement-import auto-match — see
    below). DASHBOARD must count here or the obvious workflow this whole endpoint exists for
    (categorize one Zomato transaction by hand, then have every future Zomato import inherit it)
    wouldn't work at all — every re-import would land back in the needs-review queue forever."""
    if not merchant_norm:
        return None
    row = (
        db.execute(
            select(Transaction)
            .where(
                Transaction.merchant_norm == merchant_norm,
                Transaction.category_source.in_(("USER", "MEMORY", "DASHBOARD")),
            )
            .order_by(Transaction.updated_at.desc())
            .limit(1)
        )
        .scalars()
        .first()
    )
    return row.category if row else None


@router.post("/import", response_model=StatementImportResponse)
async def import_statement(
    file: UploadFile,
    db: Session = Depends(get_db),
    actor: Actor = Depends(require_dashboard_or_agent),
    account_id: str | None = Form(default=None),
) -> StatementImportResponse:
    if account_id is not None and db.get(Account, account_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Account not found")

    data = await file.read()
    result = parse_statement(data)

    if result.problem:
        return StatementImportResponse(
            total_rows=result.total_data_rows,
            inserted=0,
            skipped_duplicate=0,
            needs_review=0,
            problem=result.problem,
        )

    now_ms = int(time.time() * 1000)
    inserted = 0
    skipped_duplicate = 0
    needs_review = 0

    # Statement row order must be preserved (see Transaction.import_seq) so the balance chart can
    # pick each day's closing balance. Continue the global monotonic sequence from wherever it left
    # off, so rows stay ordered across separate imports too.
    seq_base = db.execute(select(func.coalesce(func.max(Transaction.import_seq), 0))).scalar_one()
    row_index = 0

    # Tier-3 same-day-occurrence dedup (docs/ARCHITECTURE.md#deduplication): the Nth identical
    # row in this import is a duplicate only once at least N such transactions already exist.
    # Baseline counts are read once per signature so later rows in this same file don't inflate
    # the baseline for earlier ones — only the file's own row order advances N.
    signature_existing_counts: dict[tuple, int] = {}
    signature_seen_in_batch: dict[tuple, int] = defaultdict(int)

    # The session only commits once, at the end of the loop (see below) — a mid-batch `select()`
    # for a reference collision only ever sees previously *committed* rows, never a sibling row
    # added earlier in this same file (the session's autoflush is off; see db/base.py). Without
    # this, two rows in one file sharing a reference — a real dedup case, not a parsing bug —
    # both pass their individual checks, both get staged, and the final commit fails outright on
    # the DB's unique constraint, rolling back the *entire* import instead of skipping the one
    # duplicate row.
    seen_references: set[str] = set()

    for row in result.rows:
        if row.reference:
            if row.reference in seen_references:
                skipped_duplicate += 1
                continue
            collision = db.execute(
                select(Transaction).where(Transaction.reference == row.reference)
            ).scalar_one_or_none()
            if collision is not None:
                skipped_duplicate += 1
                continue
            seen_references.add(row.reference)
        else:
            signature = (_day_bucket(row.occurred_at_ms), row.amount_paise, row.direction, row.merchant_norm)
            if signature not in signature_existing_counts:
                date_str = signature[0]
                day_start = datetime.strptime(date_str, "%Y-%m-%d").replace(tzinfo=TZ)
                day_end_ms = int(day_start.timestamp() * 1000) + 24 * 60 * 60 * 1000
                day_start_ms = int(day_start.timestamp() * 1000)
                signature_existing_counts[signature] = db.execute(
                    select(func.count()).select_from(Transaction).where(
                        Transaction.occurred_at >= day_start_ms,
                        Transaction.occurred_at < day_end_ms,
                        Transaction.amount_paise == row.amount_paise,
                        Transaction.direction == row.direction,
                        Transaction.merchant_norm.is_(row.merchant_norm)
                        if row.merchant_norm is None
                        else Transaction.merchant_norm == row.merchant_norm,
                    )
                ).scalar_one()

            signature_seen_in_batch[signature] += 1
            if signature_seen_in_batch[signature] <= signature_existing_counts[signature]:
                skipped_duplicate += 1
                continue

        known_category = _latest_known_category(db, row.merchant_norm)
        if known_category is not None:
            category = known_category
            category_source = "DASHBOARD"
            row_needs_review = False
        else:
            category = OTHER
            category_source = "NONE"
            row_needs_review = True
            needs_review += 1

        row_index += 1
        db.add(
            Transaction(
                client_id=str(uuid.uuid4()),
                device_id=actor.device_id,
                account_id=account_id,
                occurred_at=row.occurred_at_ms,
                amount_paise=row.amount_paise,
                direction=row.direction,
                channel=row.channel,
                merchant_norm=row.merchant_norm,
                merchant_raw=row.merchant_raw,
                account_tail=None,
                reference=row.reference,
                balance_paise=row.balance_paise,
                category=category,
                category_source=category_source,
                needs_review=row_needs_review,
                import_seq=seq_base + row_index,
                updated_at=now_ms,
                created_at=now_ms,
            )
        )
        inserted += 1

    db.commit()

    return StatementImportResponse(
        total_rows=result.total_data_rows,
        inserted=inserted,
        skipped_duplicate=skipped_duplicate,
        needs_review=needs_review,
        problem=None,
    )
