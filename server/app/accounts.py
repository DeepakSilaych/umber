"""Account-kind taxonomy for the account registry. Unlike categories.py's taxonomy, this is
server-only — nothing on the Android side depends on it, so it can change freely if needed."""

from sqlalchemy import select
from sqlalchemy.orm import Session

BANK = "BANK"
CREDIT_CARD = "CREDIT_CARD"
WALLET = "WALLET"
CASH = "CASH"
OTHER = "OTHER"

ACCOUNT_KINDS = (BANK, CREDIT_CARD, WALLET, CASH, OTHER)


def find_unique_match(db: Session, account_tail: str) -> str | None:
    """Returns the single account id whose account_tail matches, or None if zero or multiple
    accounts match — auto-link never guesses."""
    from app.db.models import Account

    matches = db.execute(select(Account.id).where(Account.account_tail == account_tail)).scalars().all()
    return matches[0] if len(matches) == 1 else None


def auto_link(db: Session, txn) -> None:
    """Call before commit, on any insert/update that sets account_tail. No-op if already linked
    or if there's no account_tail to match on."""
    if txn.account_id is not None or not txn.account_tail:
        return
    match = find_unique_match(db, txn.account_tail)
    if match is not None:
        txn.account_id = match
