import time
import uuid
from collections import defaultdict
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, UploadFile
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.auth import Actor, require_dashboard_or_agent
from app.categories import OTHER
from app.db.base import get_db
from app.db.models import Transaction
from app.parsing.statement import parse_statement
from app.schemas import StatementImportResponse

router = APIRouter(prefix="/v1/statements", tags=["statements"])

TZ = ZoneInfo("Asia/Kolkata")


def _day_bucket(occurred_at_ms: int) -> str:
    return datetime.fromtimestamp(occurred_at_ms / 1000, tz=TZ).strftime("%Y-%m-%d")


def _latest_known_category(db: Session, merchant_norm: str | None) -> str | None:
    """Server-side stand-in for the phone's merchant-memory layer 1: the most recent
    user-or-memory-confirmed category already on file for this merchant, if any."""
    if not merchant_norm:
        return None
    row = (
        db.execute(
            select(Transaction)
            .where(
                Transaction.merchant_norm == merchant_norm,
                Transaction.category_source.in_(("USER", "MEMORY")),
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
) -> StatementImportResponse:
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

    # Tier-3 same-day-occurrence dedup (docs/ARCHITECTURE.md#deduplication): the Nth identical
    # row in this import is a duplicate only once at least N such transactions already exist.
    # Baseline counts are read once per signature so later rows in this same file don't inflate
    # the baseline for earlier ones — only the file's own row order advances N.
    signature_existing_counts: dict[tuple, int] = {}
    signature_seen_in_batch: dict[tuple, int] = defaultdict(int)

    for row in result.rows:
        if row.reference:
            collision = db.execute(
                select(Transaction).where(Transaction.reference == row.reference)
            ).scalar_one_or_none()
            if collision is not None:
                skipped_duplicate += 1
                continue
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

        db.add(
            Transaction(
                client_id=str(uuid.uuid4()),
                device_id=actor.device_id,
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
