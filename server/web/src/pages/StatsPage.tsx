import { useEffect, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import ErrorAlert from '@/components/ErrorAlert'
import ToggleGroupFilter from '@/components/ToggleGroupFilter'
import CategoryBarChart from '@/components/stats/CategoryBarChart'
import { ApiError, getStats } from '@/lib/api'
import { formatPaise } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { StatsPeriod, StatsResponse } from '@/lib/types'

const PERIODS: { value: StatsPeriod; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
  { value: 'this_month', label: 'This month' },
  { value: 'this_year', label: 'This year' },
]

export default function StatsPage() {
  const [period, setPeriod] = useState<StatsPeriod>('this_month')
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    getStats(period)
      .then((res) => {
        if (!cancelled) setStats(res)
      })
      .catch((err) => {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 401) return
        setError(err instanceof ApiError ? err.message : 'Failed to load stats.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [period])

  const categoryEntries = stats
    ? Object.entries(stats.net_by_category).sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))
    : []
  const chartData = categoryEntries.map(([category, paise]) => ({ category, paise }))

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold tracking-tight">Stats</h1>
        <ToggleGroupFilter
          options={PERIODS}
          value={period}
          onChange={(v) => setPeriod(v as StatsPeriod)}
        />
      </div>

      {error && <ErrorAlert message={error} className="mb-4" />}

      {loading && !stats ? (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-16 rounded-lg" />
            ))}
          </div>
          <Skeleton className="h-64 rounded-lg" />
        </div>
      ) : stats ? (
        <>
          <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            <SummaryCard label="Net spend" value={stats.net_spend_paise} tone="primary" />
            <SummaryCard label="Gross spend" value={stats.gross_spend_paise} />
            <SummaryCard label="Reimbursed" value={stats.reimbursed_paise} tone="positive" />
            <SummaryCard label="Income" value={stats.income_paise} tone="positive" />
            <SummaryCard label="Transactions" value={stats.transaction_count} isCount />
            <SummaryCard
              label="Needs review"
              value={stats.needs_review_count}
              isCount
              tone={stats.needs_review_count > 0 ? 'warn' : undefined}
            />
          </div>

          <Card>
            <CardContent>
              <h2 className="mb-3 text-sm font-medium">Net spend by category</h2>
              {chartData.length === 0 ? (
                <p className="text-sm text-muted-foreground">No transactions in this period.</p>
              ) : (
                <CategoryBarChart data={chartData} />
              )}
            </CardContent>
          </Card>
        </>
      ) : null}
    </div>
  )
}

function SummaryCard({
  label,
  value,
  tone,
  isCount,
}: {
  label: string
  value: number
  tone?: 'primary' | 'positive' | 'warn'
  isCount?: boolean
}) {
  const valueClasses =
    tone === 'primary'
      ? 'text-umber-600 dark:text-umber-300'
      : tone === 'positive'
        ? 'text-emerald-600 dark:text-emerald-400'
        : tone === 'warn'
          ? 'text-amber-600 dark:text-amber-400'
          : 'text-foreground'

  return (
    <Card className="gap-1 py-3">
      <CardContent className="px-3">
        <div className="text-xs text-muted-foreground">{label}</div>
        <div className={cn('mt-1 text-lg font-semibold tabular-nums', valueClasses)}>
          {isCount ? value : formatPaise(value)}
        </div>
      </CardContent>
    </Card>
  )
}
