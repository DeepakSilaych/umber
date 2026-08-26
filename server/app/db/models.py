from decimal import Decimal

from sqlalchemy import JSON, BigInteger, Boolean, CheckConstraint, Float, ForeignKey, Index, Numeric, Text, text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.accounts import ACCOUNT_KINDS
from app.categories import CATEGORY_SOURCES, CHANNELS, DIRECTIONS
from app.db.base import Base
from app.holdings import HOLDING_KINDS, HOLDING_TXN_TYPES


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    kind: Mapped[str] = mapped_column(Text, nullable=False)  # 'phone' | 'dashboard' | 'agent'
    label: Mapped[str | None] = mapped_column(Text, nullable=True)
    token_hash: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    last_seen_at: Mapped[int | None] = mapped_column(BigInteger, nullable=True)

    __table_args__ = (CheckConstraint("kind in ('phone','dashboard','agent')", name="ck_devices_kind"),)


class Account(Base):
    __tablename__ = "accounts"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    label: Mapped[str] = mapped_column(Text, nullable=False)
    bank_name: Mapped[str | None] = mapped_column(Text, nullable=True)
    kind: Mapped[str] = mapped_column(Text, nullable=False)
    # Deliberately NOT unique — two different banks/cards can share the same last-4 digits.
    # Auto-link relies on this column allowing duplicates; see app/accounts.py.
    account_tail: Mapped[str | None] = mapped_column(Text, nullable=True)
    opening_balance_paise: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    opening_balance_as_of: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)

    __table_args__ = (
        CheckConstraint(f"kind in {ACCOUNT_KINDS}", name="ck_accounts_kind"),
        Index("ix_accounts_account_tail", "account_tail"),
    )


class Transaction(Base):
    __tablename__ = "transactions"

    client_id: Mapped[str] = mapped_column(Text, primary_key=True)
    device_id: Mapped[str] = mapped_column(Text, ForeignKey("devices.id"), nullable=False)
    account_id: Mapped[str | None] = mapped_column(
        Text, ForeignKey("accounts.id", ondelete="SET NULL"), nullable=True
    )

    occurred_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    amount_paise: Mapped[int] = mapped_column(BigInteger, nullable=False)
    direction: Mapped[str] = mapped_column(Text, nullable=False)
    channel: Mapped[str | None] = mapped_column(Text, nullable=True)

    merchant_norm: Mapped[str | None] = mapped_column(Text, nullable=True)
    merchant_raw: Mapped[str | None] = mapped_column(Text, nullable=True)
    account_tail: Mapped[str | None] = mapped_column(Text, nullable=True)
    reference: Mapped[str | None] = mapped_column(Text, nullable=True)
    balance_paise: Mapped[int | None] = mapped_column(BigInteger, nullable=True)

    category: Mapped[str] = mapped_column(Text, nullable=False)
    category_source: Mapped[str] = mapped_column(Text, nullable=False)
    # Dashboard/agent-only free-text tag layered on top of the frozen 15-item `category` taxonomy.
    # Never synced to or from the phone — see TxnOutDetailed vs TxnOut in schemas.py.
    subcategory: Mapped[str | None] = mapped_column(Text, nullable=True)
    needs_review: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    # Statement row order, monotonic across imports. A whole import batch shares one `created_at`,
    # so without this the file order (needed to pick a day's *closing* balance for the balance
    # chart) is unrecoverable. Null for rows that never came from a statement (phone SMS, manual).
    import_seq: Mapped[int | None] = mapped_column(BigInteger, nullable=True)

    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[int] = mapped_column(BigInteger, nullable=False)

    device: Mapped["Device"] = relationship()
    account: Mapped["Account | None"] = relationship()

    __table_args__ = (
        CheckConstraint(f"direction in {DIRECTIONS}", name="ck_txn_direction"),
        CheckConstraint(f"channel is null or channel in {CHANNELS}", name="ck_txn_channel"),
        CheckConstraint(f"category_source in {CATEGORY_SOURCES}", name="ck_txn_category_source"),
        # Global, not per-device: this is a single-user ledger, so a reference number from a
        # dashboard-uploaded statement must dedupe against the same transaction the phone already
        # synced via SMS. Partial because most rows legitimately have no reference.
        Index("ux_txn_reference", "reference", unique=True, postgresql_where=text("reference IS NOT NULL")),
        Index("ix_txn_device_updated", "device_id", "updated_at"),
        Index("ix_txn_occurred", "occurred_at"),
        Index("ix_txn_merchant_norm", "merchant_norm"),
        Index("ix_txn_needs_review", "needs_review"),
        Index("ix_txn_account_occurred", "account_id", "occurred_at", "import_seq"),
        Index("ix_txn_subcategory", "subcategory", postgresql_where=text("subcategory IS NOT NULL")),
    )


class MerchantCategory(Base):
    __tablename__ = "merchant_categories"

    merchant_norm: Mapped[str] = mapped_column(Text, primary_key=True)
    category: Mapped[str] = mapped_column(Text, nullable=False)
    confidence: Mapped[float | None] = mapped_column(Float, nullable=True)
    model: Mapped[str | None] = mapped_column(Text, nullable=True)
    decided_at: Mapped[int] = mapped_column(BigInteger, nullable=False)


class Holding(Base):
    __tablename__ = "holdings"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    kind: Mapped[str] = mapped_column(Text, nullable=False)
    # Numeric, not paise: a physical unit count, not money. Mutual fund NAVs commonly need 4
    # decimal places, and weighted-average cost isn't paise-clean across repeated recomputation.
    units: Mapped[Decimal | None] = mapped_column(Numeric(20, 6), nullable=True)
    avg_cost_paise: Mapped[Decimal | None] = mapped_column(Numeric(20, 4), nullable=True)
    current_value_paise: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)

    __table_args__ = (
        CheckConstraint(f"kind in {HOLDING_KINDS}", name="ck_holdings_kind"),
        CheckConstraint("units is null or units >= 0", name="ck_holdings_units_nonneg"),
    )


class HoldingTransaction(Base):
    __tablename__ = "holding_transactions"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    holding_id: Mapped[str] = mapped_column(Text, ForeignKey("holdings.id", ondelete="CASCADE"), nullable=False)
    type: Mapped[str] = mapped_column(Text, nullable=False)
    units: Mapped[Decimal | None] = mapped_column(Numeric(20, 6), nullable=True)
    price_paise: Mapped[Decimal | None] = mapped_column(Numeric(20, 4), nullable=True)
    amount_paise: Mapped[int] = mapped_column(BigInteger, nullable=False)
    # Computed and stored at write time — avg_cost drifts afterward, so a later recompute of a
    # historical sale's gain would be wrong. Null for value-tracked SELLs (no defensible per-unit
    # cost basis without an arbitrary FIFO/LIFO assumption) and for BUY/VALUATION_UPDATE rows.
    realized_gain_paise: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    occurred_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[int] = mapped_column(BigInteger, nullable=False)

    holding: Mapped["Holding"] = relationship()

    __table_args__ = (
        CheckConstraint(f"type in {HOLDING_TXN_TYPES}", name="ck_holdingtxn_type"),
        CheckConstraint("units is null or units > 0", name="ck_holdingtxn_units_positive"),
        CheckConstraint("amount_paise >= 0", name="ck_holdingtxn_amount_nonneg"),
        Index("ix_holdingtxn_holding_occurred", "holding_id", "occurred_at"),
    )


class InsightsCache(Base):
    __tablename__ = "insights_cache"

    period: Mapped[str] = mapped_column(Text, primary_key=True)
    from_ms: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    to_ms: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    # sha256 of the aggregated JSON sent to the LLM — any real data change is automatically a
    # cache miss, so no TTL/expiry logic is needed.
    stats_hash: Mapped[str] = mapped_column(Text, primary_key=True)

    summary: Mapped[str] = mapped_column(Text, nullable=False)
    suggestions: Mapped[list] = mapped_column(JSON, nullable=False)
    model: Mapped[str] = mapped_column(Text, nullable=False)
    generated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)


class BudgetConfig(Base):
    """Single-row table (id always 1) — the monthly plan's top-level income figure. Single-user
    app, so a one-row config table is simpler than a settings key-value store."""

    __tablename__ = "budget_config"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, default=1)
    monthly_income_paise: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)

    __table_args__ = (CheckConstraint("id = 1", name="ck_budget_config_singleton"),)


class BudgetBucket(Base):
    """One line of the monthly budget. Bucket-based (matching the user's Obsidian plan), not
    per-category — each bucket rolls up zero or more of the 15 fixed categories. `kind='spend'`
    tracks actual spend in its categories against the target; `kind='savings'` tracks leftover
    (income minus total outflow) against the target and has no categories."""

    __tablename__ = "budget_buckets"

    id: Mapped[str] = mapped_column(Text, primary_key=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    monthly_target_paise: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    category_keys: Mapped[list] = mapped_column(JSON, nullable=False, default=list)
    kind: Mapped[str] = mapped_column(Text, nullable=False, default="spend")
    sort_order: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    created_at: Mapped[int] = mapped_column(BigInteger, nullable=False)
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False)

    __table_args__ = (CheckConstraint("kind in ('spend','savings')", name="ck_budget_bucket_kind"),)
