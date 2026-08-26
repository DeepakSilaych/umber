import { useEffect, useMemo, useState } from 'react'
import { startOfDay, startOfYear, subDays, subMonths } from 'date-fns'
import { Card, CardContent } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import ErrorAlert from '@/components/ErrorAlert'
import ToggleGroupFilter from '@/components/ToggleGroupFilter'
import SpendHeatmap from '@/components/calendar/SpendHeatmap'
import { ApiError, getTimeline, listAccounts } from '@/lib/api'
import { formatSignedPaise } from '@/lib/format'
import type { Account, TimelineResponse } from '@/lib/types'

type RangeKey = '3m' | '6m' | 'year' | 'all'

const RANGES: { value: RangeKey; label: string }[] = [
  { value: '3m', label: 'Last 3 months' },
  { value: '6m', label: 'Last 6 months' },
  { value: 'year', label: 'This year' },
  { value: 'all', label: 'All' },
]

// Radix Select forbids an empty-string item value; "all accounts" uses this sentinel.
const ALL_ACCOUNTS = '__all__'

/** Resolves a range key to a [from_ms, to_ms] window. `all` is capped to ~1 year of days because
 * /v1/stats/timeline rejects day-granularity ranges over 400 buckets. */
function rangeToWindow(range: RangeKey): { from: number; to: number } {
  const now = Date.now()
  const start =
    range === '3m'
      ? subMonths(now, 3)
      : range === '6m'
        ? subMonths(now, 6)
        : range === 'year'
          ? startOfYear(now)
          : subDays(now, 364)
  return { from: startOfDay(start).getTime(), to: now }
}

export default function CalendarPage() {
  const [range, setRange] = useState<RangeKey>('6m')
  const [accountId, setAccountId] = useState<string>(ALL_ACCOUNTS)
  const [accounts, setAccounts] = useState<Account[]>([])
  const [data, setData] = useState<TimelineResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const { from, to } = useMemo(() => rangeToWindow(range), [range])

  useEffect(() => {
    let cancelled = false
    listAccounts()
      .then((res) => {
        if (!cancelled) setAccounts(res.items)
      })
      .catch(() => {
        // Account filter is a nice-to-have; ignore load failures.
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    getTimeline({
      granularity: 'day',
      from_ms: from,
      to_ms: to,
      account_id: accountId === ALL_ACCOUNTS ? undefined : accountId,
    })
      .then((res) => {
        if (!cancelled) setData(res)
      })
      .catch((err) => {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 401) return
        setError(err instanceof ApiError ? err.message : 'Failed to load calendar.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [from, to, accountId])

  const totalNet = data ? data.buckets.reduce((sum, b) => sum + b.net_spend_paise, 0) : 0
  const activeDays = data ? data.buckets.filter((b) => b.transaction_count > 0).length : 0

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold tracking-tight">Calendar</h1>
        <div className="flex flex-wrap items-center gap-2">
          {accounts.length > 0 && (
            <Select value={accountId} onValueChange={setAccountId}>
              <SelectTrigger className="w-48">
                <SelectValue placeholder="All accounts" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL_ACCOUNTS}>All accounts</SelectItem>
                {accounts.map((account) => (
                  <SelectItem key={account.id} value={account.id}>
                    {account.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
          <ToggleGroupFilter
            options={RANGES}
            value={range}
            onChange={(v) => setRange(v as RangeKey)}
          />
        </div>
      </div>

      {error && <ErrorAlert message={error} className="mb-4" />}

      <Card>
        <CardContent>
          <div className="mb-4 flex flex-wrap gap-6">
            <div>
              <div className="text-xs text-muted-foreground">Net spend in range</div>
              <div className="mt-0.5 text-lg font-semibold tabular-nums text-umber-600 dark:text-umber-300">
                {formatSignedPaise(totalNet)}
              </div>
            </div>
            <div>
              <div className="text-xs text-muted-foreground">Active days</div>
              <div className="mt-0.5 text-lg font-semibold tabular-nums">{activeDays}</div>
            </div>
          </div>

          {loading && !data ? (
            <Skeleton className="h-40 w-full rounded-lg" />
          ) : data ? (
            <SpendHeatmap buckets={data.buckets} from={from} to={to} />
          ) : null}
        </CardContent>
      </Card>
    </div>
  )
}
