// Mirrors app/schemas.py. Keep field names and shapes exactly in sync with the server —
// this is the source of truth, not this file.

export type Direction = 'DEBIT' | 'CREDIT'

export interface TxnOut {
  client_id: string
  device_id: string
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
