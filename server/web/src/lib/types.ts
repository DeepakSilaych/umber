// Mirrors app/schemas.py. Keep field names and shapes exactly in sync with the server —
// this is the source of truth, not this file.

export type Direction = 'DEBIT' | 'CREDIT'

/** Routine everyday spend vs a special/big-budget one-off. `null` = not yet typed. */
export type SpendType = 'NORMAL' | 'SPECIAL'

// The list/detail/patch transaction endpoints return TxnOutDetailed server-side, which is TxnOut
// plus the dashboard-only `subcategory`/`spend_type` fields — so those are folded in here.
export interface TxnOut {
  client_id: string
  device_id: string
  account_id?: string | null
  occurred_at: number // epoch ms
  amount_paise: number
  direction: Direction
  channel: string | null
  merchant_norm: string | null
  merchant_raw: string | null
  account_tail: string | null
  reference: string | null
  balance_paise: number | null
  category: string
  category_source: string
  needs_review: boolean
  subcategory: string | null
  spend_type: SpendType | null
  updated_at: number
  created_at: number
}

export interface TransactionListResponse {
  total: number
  items: TxnOut[]
}

export interface TransactionPatch {
  category?: string
  merchant_raw?: string
  needs_review?: boolean
  /** Free-form detail under the category. Empty string clears it server-side. */
  subcategory?: string
  /** "NORMAL" | "SPECIAL". Empty string clears it server-side. */
  spend_type?: string
}

export interface TransactionCreate {
  occurred_at: number
  amount_paise: number
  direction: Direction
  category: string
  merchant_raw?: string | null
  channel?: string | null
  account_tail?: string | null
  reference?: string | null
}

export interface StatsResponse {
  period: string
  from_ms: number
  to_ms: number
  gross_spend_paise: number
  reimbursed_paise: number
  net_spend_paise: number
  income_paise: number
  net_by_category: Record<string, number>
  transaction_count: number
  needs_review_count: number
}

export type StatsPeriod = 'today' | '7d' | '30d' | 'this_month' | 'this_year'

export interface StatementImportResponse {
  total_rows: number
  inserted: number
  skipped_duplicate: number
  needs_review: number
  problem: string | null
}

// --- Accounts ---------------------------------------------------------------

export type AccountKind = 'BANK' | 'CREDIT_CARD' | 'WALLET' | 'CASH' | 'OTHER'

export interface Account {
  id: string
  label: string
  bank_name: string | null
  kind: AccountKind
  account_tail: string | null
  opening_balance_paise: number
  opening_balance_as_of: number
  balance_paise: number // live-computed as of now
  created_at: number
  updated_at: number
}

export interface AccountListResponse {
  items: Account[]
}

export interface AccountCreate {
  label: string
  bank_name?: string | null
  kind: AccountKind
  account_tail?: string | null
  opening_balance_paise?: number
}

export interface BalanceSeriesPoint {
  day_ms: number // IST midnight of the day
  balance_paise: number // the day's closing balance for this account
}

export interface BalanceSeriesResponse {
  account_id: string
  points: BalanceSeriesPoint[]
}

// --- Stats timeline ---------------------------------------------------------

export interface TimelineBucket {
  bucket_start_ms: number // IST-midnight of the day for granularity=day
  bucket_end_ms: number
  gross_spend_paise: number
  reimbursed_paise: number
  net_spend_paise: number
  income_paise: number
  transaction_count: number
  needs_review_count: number
  breakdown: Record<string, number> | null
}

export interface TimelineResponse {
  from_ms: number
  to_ms: number
  granularity: string
  group_by: string | null
  account_id: string | null
  series_keys: string[] | null
  buckets: TimelineBucket[]
}

// --- Budget -----------------------------------------------------------------

export type BucketKind = 'spend' | 'savings'

export interface BudgetBucket {
  id: string
  name: string
  monthly_target_paise: number
  category_keys: string[]
  subcategory_keywords: string[]
  kind: BucketKind
  sort_order: number
}

export interface BudgetOut {
  monthly_income_paise: number
  buckets: BudgetBucket[]
}

export interface BudgetBucketProgress {
  id: string
  name: string
  kind: BucketKind
  category_keys: string[]
  subcategory_keywords: string[]
  target_paise: number
  actual_paise: number
}

export interface BudgetProgressResponse {
  period: string
  from_ms: number
  to_ms: number
  monthly_income_paise: number
  total_spent_paise: number
  unbudgeted_paise: number
  buckets: BudgetBucketProgress[]
}

export interface BudgetBucketInput {
  name: string
  monthly_target_paise: number
  category_keys: string[]
  subcategory_keywords: string[]
  kind: BucketKind
  sort_order: number
}

export type BudgetBucketPatch = Partial<BudgetBucketInput>

export type BudgetPeriod = 'this_month' | 'last_month' | 'this_year'

// --- Distribution (category × normal/special) -------------------------------

export interface DistributionRow {
  category: string
  normal_paise: number
  special_paise: number
}

export interface DistributionResponse {
  period: string
  from_ms: number
  to_ms: number
  normal_total_paise: number
  special_total_paise: number
  untyped_total_paise: number // spend on rows with no spend_type assigned yet
  rows: DistributionRow[] // desc by (normal + special)
}

// --- Sub-category breakdown -------------------------------------------------

export interface SubcategoryBreakdownItem {
  category: string
  subcategory: string | null // null = not tagged yet ("Untagged")
  amount_paise: number
  transaction_count: number
}

export interface SubcategoryBreakdownResponse {
  period: string
  from_ms: number
  to_ms: number
  direction: string
  items: SubcategoryBreakdownItem[] // desc by amount_paise
}

/** Period picker shared by the Breakdown analytics page. */
export type BreakdownPeriod = 'this_month' | 'last_month' | 'this_year' | 'all'
