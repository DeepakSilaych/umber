from decimal import Decimal

from pydantic import BaseModel, Field, field_validator

from app.accounts import ACCOUNT_KINDS
from app.categories import CATEGORY_SOURCES, CHANNELS, DIRECTIONS, is_valid
from app.holdings import HOLDING_KINDS, HOLDING_TXN_TYPES


class TxnIn(BaseModel):
    """One row as pushed by the phone in POST /v1/sync. Field names match docs/SYNC.md."""

    client_id: str
    occurred_at: int
    amount_paise: int = Field(ge=0)
    direction: str
    channel: str | None = None
    merchant_norm: str | None = None
    merchant_raw: str | None = None
    account_tail: str | None = None
    reference: str | None = None
    balance_paise: int | None = None
    category: str
    category_source: str
    updated_at: int

    @field_validator("direction")
    @classmethod
    def _direction(cls, v: str) -> str:
        if v not in DIRECTIONS:
            raise ValueError(f"direction must be one of {DIRECTIONS}")
        return v

    @field_validator("channel")
    @classmethod
    def _channel(cls, v: str | None) -> str | None:
        if v is not None and v not in CHANNELS:
            raise ValueError(f"channel must be one of {CHANNELS}")
        return v

    @field_validator("category")
    @classmethod
    def _category(cls, v: str) -> str:
        if not is_valid(v):
            raise ValueError("unknown category")
        return v

    @field_validator("category_source")
    @classmethod
    def _category_source(cls, v: str) -> str:
        if v not in CATEGORY_SOURCES:
            raise ValueError(f"category_source must be one of {CATEGORY_SOURCES}")
        return v


class TxnOut(BaseModel):
    """Shared with sync.py's pull response sent to the phone — never add dashboard-only fields
    here (e.g. subcategory). See TxnOutDetailed for the dashboard/agent-facing superset."""

    client_id: str
    device_id: str
    account_id: str | None
    occurred_at: int
    amount_paise: int
    direction: str
    channel: str | None
    merchant_norm: str | None
    merchant_raw: str | None
    account_tail: str | None
    reference: str | None
    balance_paise: int | None
    category: str
    category_source: str
    needs_review: bool
    updated_at: int
    created_at: int

    model_config = {"from_attributes": True}


class TxnOutDetailed(TxnOut):
    """Used only by dashboard/agent-facing transaction endpoints — adds fields the phone never
    sees or sets."""

    subcategory: str | None


class RejectedTxn(BaseModel):
    client_id: str
    reason: str


class SyncRequest(BaseModel):
    device_id: str
    since: int = 0
    transactions: list[TxnIn] = Field(default_factory=list)


class SyncResponse(BaseModel):
    cursor: int
    applied: int
    rejected: list[RejectedTxn]
    transactions: list[TxnOut]


class TransactionPatch(BaseModel):
    category: str | None = None
    subcategory: str | None = None
    merchant_raw: str | None = None
    needs_review: bool | None = None
    account_id: str | None = None

    @field_validator("category")
    @classmethod
    def _category(cls, v: str | None) -> str | None:
        if v is not None and not is_valid(v):
            raise ValueError("unknown category")
        return v

    @field_validator("subcategory")
    @classmethod
    def _subcategory(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.strip()
        if len(v) > 60:
            raise ValueError("subcategory must be at most 60 characters")
        return v or None  # "" clears it


class TransactionCreate(BaseModel):
    occurred_at: int
    amount_paise: int = Field(gt=0)
    direction: str
    category: str
    merchant_raw: str | None = None
    channel: str | None = None
    account_tail: str | None = None
    reference: str | None = None
    account_id: str | None = None

    @field_validator("direction")
    @classmethod
    def _direction(cls, v: str) -> str:
        if v not in DIRECTIONS:
            raise ValueError(f"direction must be one of {DIRECTIONS}")
        return v

    @field_validator("category")
    @classmethod
    def _category(cls, v: str) -> str:
        if not is_valid(v):
            raise ValueError("unknown category")
        return v

    @field_validator("channel")
    @classmethod
    def _channel(cls, v: str | None) -> str | None:
        if v is not None and v not in CHANNELS:
            raise ValueError(f"channel must be one of {CHANNELS}")
        return v


class TransactionListResponse(BaseModel):
    total: int
    items: list[TxnOutDetailed]


class BulkAssignAccountRequest(BaseModel):
    client_ids: list[str] = Field(min_length=1, max_length=500)
    account_id: str


class BulkAssignAccountResponse(BaseModel):
    updated: int
    not_found: list[str]


class StatsResponse(BaseModel):
    period: str
    from_ms: int
    to_ms: int
    account_id: str | None = None
    gross_spend_paise: int
    reimbursed_paise: int
    net_spend_paise: int
    income_paise: int
    net_by_category: dict[str, int]
    transaction_count: int
    needs_review_count: int


class DeviceRegisterRequest(BaseModel):
    setup_key: str
    label: str | None = None


class DeviceRegisterResponse(BaseModel):
    device_id: str
    token: str


class LoginRequest(BaseModel):
    password: str


class StatementImportResponse(BaseModel):
    total_rows: int
    inserted: int
    skipped_duplicate: int
    needs_review: int
    problem: str | None = None


# --- Accounts ---------------------------------------------------------------


class AccountCreate(BaseModel):
    label: str
    bank_name: str | None = None
    kind: str
    account_tail: str | None = None
    opening_balance_paise: int = 0
    opening_balance_as_of: int | None = None  # defaults to now if omitted

    @field_validator("kind")
    @classmethod
    def _kind(cls, v: str) -> str:
        if v not in ACCOUNT_KINDS:
            raise ValueError(f"kind must be one of {ACCOUNT_KINDS}")
        return v


class AccountPatch(BaseModel):
    label: str | None = None
    bank_name: str | None = None
    kind: str | None = None
    account_tail: str | None = None
    opening_balance_paise: int | None = None
    opening_balance_as_of: int | None = None

    @field_validator("kind")
    @classmethod
    def _kind(cls, v: str | None) -> str | None:
        if v is not None and v not in ACCOUNT_KINDS:
            raise ValueError(f"kind must be one of {ACCOUNT_KINDS}")
        return v


class AccountOut(BaseModel):
    id: str
    label: str
    bank_name: str | None
    kind: str
    account_tail: str | None
    opening_balance_paise: int
    opening_balance_as_of: int
    balance_paise: int  # live-computed as of now, inlined so the list view needs no N+1 calls
    created_at: int
    updated_at: int


class AccountListResponse(BaseModel):
    items: list[AccountOut]


class AccountBalanceResponse(BaseModel):
    account_id: str
    as_of: int
    opening_balance_paise: int
    balance_paise: int
    transaction_count: int


class RelinkAccountsResponse(BaseModel):
    scanned: int
    linked: int
    ambiguous: int
    no_match: int


# --- Holdings ----------------------------------------------------------------


class HoldingCreate(BaseModel):
    name: str
    kind: str
    notes: str | None = None

    @field_validator("kind")
    @classmethod
    def _kind(cls, v: str) -> str:
        if v not in HOLDING_KINDS:
            raise ValueError(f"kind must be one of {HOLDING_KINDS}")
        return v


class HoldingPatch(BaseModel):
    name: str | None = None
    kind: str | None = None
    notes: str | None = None
    # Deliberately no units/avg_cost_paise/current_value_paise here — those are log-driven only
    # (via POST .../transactions), preserving the append-only-log-as-source-of-truth invariant.

    @field_validator("kind")
    @classmethod
    def _kind(cls, v: str | None) -> str | None:
        if v is not None and v not in HOLDING_KINDS:
            raise ValueError(f"kind must be one of {HOLDING_KINDS}")
        return v


class HoldingTransactionCreate(BaseModel):
    type: str
    units: Decimal | None = None
    price_paise: Decimal | None = None
    amount_paise: int = Field(ge=0)
    occurred_at: int

    @field_validator("type")
    @classmethod
    def _type(cls, v: str) -> str:
        if v not in HOLDING_TXN_TYPES:
            raise ValueError(f"type must be one of {HOLDING_TXN_TYPES}")
        return v

    @field_validator("units")
    @classmethod
    def _units_positive(cls, v: Decimal | None) -> Decimal | None:
        if v is not None and v <= 0:
            raise ValueError("units must be positive")
        return v


class HoldingTransactionOut(BaseModel):
    id: str
    holding_id: str
    type: str
    units: Decimal | None
    price_paise: Decimal | None
    amount_paise: int
    realized_gain_paise: int | None
    occurred_at: int
    created_at: int

    model_config = {"from_attributes": True}


class HoldingTransactionListResponse(BaseModel):
    items: list[HoldingTransactionOut]


class HoldingOut(BaseModel):
    id: str
    name: str
    kind: str
    units: Decimal | None
    avg_cost_paise: Decimal | None
    current_value_paise: int | None  # None = not yet valued; dashboard should show that, not ₹0
    net_invested_paise: int
    unrealized_gain_paise: int | None  # None if current_value_paise is None
    realized_gain_paise: int  # sum across this holding's SELL rows; 0 if none
    notes: str | None
    created_at: int
    updated_at: int


class HoldingListResponse(BaseModel):
    items: list[HoldingOut]


class HoldingKindAllocation(BaseModel):
    current_value_paise: int
    invested_paise: int
    count: int


class HoldingsSummaryResponse(BaseModel):
    total_current_value_paise: int  # sum over holdings with a non-null current_value_paise only
    total_invested_paise: int
    total_unrealized_gain_paise: int
    total_realized_gain_paise: int
    by_kind: dict[str, HoldingKindAllocation]


class HoldingTimelinePoint(BaseModel):
    occurred_at: int
    total_value_paise: int
    is_estimated: bool  # True if any contributing holding used a cost-basis proxy, not a real valuation


class HoldingTimelineResponse(BaseModel):
    points: list[HoldingTimelinePoint]


# --- Stats: timeline / breakdowns --------------------------------------------


class TimelineBucket(BaseModel):
    bucket_start_ms: int
    bucket_end_ms: int
    gross_spend_paise: int
    reimbursed_paise: int
    net_spend_paise: int
    income_paise: int
    transaction_count: int
    needs_review_count: int
    breakdown: dict[str, int] | None = None  # present iff group_by set; keys == series_keys


class TimelineResponse(BaseModel):
    from_ms: int
    to_ms: int
    granularity: str
    group_by: str | None
    account_id: str | None
    series_keys: list[str] | None
    buckets: list[TimelineBucket]


class MerchantBreakdownItem(BaseModel):
    merchant_norm: str | None  # None = rows with no merchant recorded
    amount_paise: int
    transaction_count: int


class MerchantBreakdownResponse(BaseModel):
    period: str
    from_ms: int
    to_ms: int
    direction: str
    items: list[MerchantBreakdownItem]  # top `limit`, desc
    other_paise: int
    other_transaction_count: int


class ChannelBreakdownItem(BaseModel):
    channel: str | None
    amount_paise: int
    transaction_count: int


class ChannelBreakdownResponse(BaseModel):
    period: str
    from_ms: int
    to_ms: int
    direction: str
    items: list[ChannelBreakdownItem]  # all channels present, desc


class TimePatternBucket(BaseModel):
    bucket: int  # 0-6 (Mon=0) for day_of_week; 0-23 for hour_of_day
    label: str  # "Monday" / "14:00"
    amount_paise: int
    transaction_count: int


class TimePatternResponse(BaseModel):
    period: str
    from_ms: int
    to_ms: int
    dimension: str
    direction: str
    buckets: list[TimePatternBucket]  # always 7 or 24 entries


class SubcategoryBreakdownItem(BaseModel):
    category: str
    subcategory: str | None  # None = not tagged yet
    amount_paise: int
    transaction_count: int


class SubcategoryBreakdownResponse(BaseModel):
    period: str
    from_ms: int
    to_ms: int
    direction: str
    items: list[SubcategoryBreakdownItem]  # desc by amount_paise


# --- Insights ------------------------------------------------------------------


class InsightsResponse(BaseModel):
    period: str
    from_ms: int
    to_ms: int
    summary: str
    suggestions: list[str]
    llm_generated: bool
    cached: bool
    model: str | None
    generated_at: int
