import type {
  StatementImportResponse,
  StatsResponse,
  TransactionCreate,
  TransactionListResponse,
  TransactionPatch,
  TxnOut,
} from './types'

export class ApiError extends Error {
  status: number
  detail: unknown

  constructor(status: number, detail: unknown, message: string) {
    super(message)
    this.status = status
    this.detail = detail
  }
}

interface RequestOptions extends RequestInit {
  /** Skip the automatic redirect-to-login on a 401. Used by the login call itself. */
  skipAuthRedirect?: boolean
}

function extractMessage(detail: unknown, status: number): string {
  if (detail && typeof detail === 'object' && 'detail' in detail) {
    const d = (detail as { detail?: unknown }).detail
    if (typeof d === 'string') return d
  }
  return `Request failed (${status})`
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { skipAuthRedirect, headers, body, ...rest } = options

  const isFormData = typeof FormData !== 'undefined' && body instanceof FormData
  const res = await fetch(path, {
    credentials: 'include',
    headers: {
      ...(body && !isFormData ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body,
    ...rest,
  })

  if (res.status === 401) {
    if (!skipAuthRedirect && window.location.pathname !== '/login') {
      window.location.href = '/login'
    }
    let detail: unknown = null
    try {
      detail = await res.json()
    } catch {
      /* no body */
    }
    throw new ApiError(401, detail, extractMessage(detail, 401))
  }

  if (!res.ok) {
    let detail: unknown = null
    try {
      detail = await res.json()
    } catch {
      /* no body */
    }
    throw new ApiError(res.status, detail, extractMessage(detail, res.status))
  }

  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export function login(password: string): Promise<{ ok: boolean }> {
  return request('/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ password }),
    skipAuthRedirect: true,
  })
}

export function logout(): Promise<{ ok: boolean }> {
  return request('/v1/auth/logout', { method: 'POST' })
}

export interface TransactionFilters {
  category?: string
  needs_review?: boolean
  merchant?: string
  occurred_from?: number
  occurred_to?: number
  limit?: number
  offset?: number
}

export function listTransactions(filters: TransactionFilters = {}): Promise<TransactionListResponse> {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  const qs = params.toString()
  return request(`/v1/transactions${qs ? `?${qs}` : ''}`)
}

export function patchTransaction(clientId: string, patch: TransactionPatch): Promise<TxnOut> {
  return request(`/v1/transactions/${encodeURIComponent(clientId)}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  })
}

export function createTransaction(body: TransactionCreate): Promise<TxnOut> {
  return request('/v1/transactions', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function getStats(period: string): Promise<StatsResponse> {
  return request(`/v1/stats?period=${encodeURIComponent(period)}`)
}

export function importStatement(file: File): Promise<StatementImportResponse> {
  const form = new FormData()
  form.append('file', file)
  return request('/v1/statements/import', {
    method: 'POST',
    body: form,
  })
}
