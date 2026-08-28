import { useCallback, useEffect, useMemo, useState } from 'react'
import { endOfMonth, startOfMonth, subMonths } from 'date-fns'
import { Card, CardContent } from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import ErrorAlert from '@/components/ErrorAlert'
import ToggleGroupFilter from '@/components/ToggleGroupFilter'
import TripsPanel from '@/components/trips/TripsPanel'
import { ApiError, getBySubcategory, getContexts, getDistribution, type WindowParams } from '@/lib/api'
import { formatPaise } from '@/lib/format'
import { cn } from '@/lib/utils'
import {
  DAILY_EXPENSE_CONTEXT,
  type BreakdownPeriod,
  type DistributionResponse,
  type SubcategoryBreakdownResponse,
} from '@/lib/types'

const PERIODS: { value: BreakdownPeriod; label: string }[] = [
  { value: 'this_month', label: 'This month' },
  { value: 'last_month', label: 'Last month' },
  { value: 'this_year', label: 'This year' },
  { value: 'all', label: 'All' },
]

// The stats endpoints know `this_month`/`this_year` as keywords; "Last month" and "All" have none,
// so they go through explicit from_ms/to_ms windows (mirrors GoalsPage's progressParams).
function windowParamsFor(period: BreakdownPeriod): WindowParams {
  if (period === 'last_month') {
    const ref = subMonths(Date.now(), 1)
    return { from_ms: startOfMonth(ref).getTime(), to_ms: endOfMonth(ref).getTime() }
  }
  if (period === 'all') {
    return { from_ms: 0, to_ms: Date.now() }
  }
  return { period }
}

// Sentinel for "All categories" in the sub-category Select (Radix forbids an empty item value).
const ALL_CATEGORIES = '__all__'

// Column colors per context. Daily-expense (always first) gets emerald; trips cycle the rest.
const CONTEXT_PALETTE = [
  'var(--color-chart-2)', // daily expense — emerald
  'var(--color-chart-3)', // amber
  'var(--color-chart-1)', // umber
  'var(--color-chart-4)', // red
  'var(--color-chart-5)', // umber-300
]
function contextColor(index: number): string {
  return CONTEXT_PALETTE[index % CONTEXT_PALETTE.length]
}

export default function BreakdownPage() {
  const [period, setPeriod] = useState<BreakdownPeriod>('this_month')
  const [distribution, setDistribution] = useState<DistributionResponse | null>(null)
  const [trips, setTrips] = useState<string[]>([])
  const [bySubcategory, setBySubcategory] = useState<SubcategoryBreakdownResponse | null>(null)
  const [selectedCategory, setSelectedCategory] = useState('') // '' = all categories
  const [loading, setLoading] = useState(true)
  const [subLoading, setSubLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Distribution matrix + the trip list. Bumped to force a refresh after a trip is tagged.
  const [refreshKey, setRefreshKey] = useState(0)
  const refresh = useCallback(() => setRefreshKey((k) => k + 1), [])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    Promise.all([getDistribution(windowParamsFor(period)), getContexts()])
      .then(([dist, ctxs]) => {
        if (cancelled) return
        setDistribution(dist)
        setTrips(ctxs)
      })
      .catch((err) => {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 401) return
        setError(err instanceof ApiError ? err.message : 'Failed to load distribution.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [period, refreshKey])

  // Sub-category breakdown depends on period + the selected category filter.
  useEffect(() => {
    let cancelled = false
    setSubLoading(true)
    getBySubcategory({
      ...windowParamsFor(period),
      ...(selectedCategory ? { category: selectedCategory } : {}),
    })
      .then((res) => {
        if (!cancelled) setBySubcategory(res)
      })
      .catch((err) => {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 401) return
        setError(err instanceof ApiError ? err.message : 'Failed to load sub-category breakdown.')
      })
      .finally(() => {
        if (!cancelled) setSubLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [period, selectedCategory])

  const contexts = distribution?.contexts ?? []
  const totalsByContext = distribution?.totals_by_context ?? {}
  const grandTotal = useMemo(
    () => Object.values(totalsByContext).reduce((sum, v) => sum + v, 0),
    [totalsByContext],
  )

  const rows = distribution?.rows ?? []
  // Longest row in the matrix, to scale each row's split bar by its share of the biggest row.
  const maxRowTotal = useMemo(() => Math.max(1, ...rows.map((r) => r.total_paise)), [rows])

  const subItems = bySubcategory?.items ?? []
  const maxSubAmount = useMemo(() => Math.max(1, ...subItems.map((i) => i.amount_paise)), [subItems])

  function toggleCategory(category: string) {
    setSelectedCategory((cur) => (cur === category ? '' : category))
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold tracking-tight">Breakdown</h1>
        <ToggleGroupFilter
          options={PERIODS}
          value={period}
          onChange={(v) => setPeriod(v as BreakdownPeriod)}
        />
      </div>

      {error && <ErrorAlert message={error} className="mb-4" />}

      <TripsPanel trips={trips} totalsByContext={totalsByContext} onChanged={refresh} />

      {loading && !distribution ? (
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-20 rounded-lg" />
            ))}
          </div>
          <Skeleton className="h-64 rounded-lg" />
        </div>
      ) : distribution ? (
        <>
          {contexts.length > 0 && (
            <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {contexts.map((ctx, i) => (
                <ContextStatCard
                  key={ctx}
                  label={ctx}
                  value={totalsByContext[ctx] ?? 0}
                  color={contextColor(i)}
                  share={grandTotal > 0 ? (totalsByContext[ctx] ?? 0) / grandTotal : 0}
                  isDefault={ctx === DAILY_EXPENSE_CONTEXT}
                />
              ))}
            </div>
          )}

          <Card className="mb-6">
            <CardContent>
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-medium">Category × Context</h2>
                <span className="text-xs text-muted-foreground">Click a row to filter sub-categories</span>
              </div>
              {rows.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">
                  No spend in this period.
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead className="sticky left-0 bg-card">Category</TableHead>
                        {contexts.map((ctx, i) => (
                          <TableHead key={ctx} className="text-right whitespace-nowrap">
                            <span className="inline-flex items-center gap-1.5">
                              <span
                                className="inline-block size-2 shrink-0 rounded-full"
                                style={{ backgroundColor: contextColor(i) }}
                              />
                              {ctx}
                            </span>
                          </TableHead>
                        ))}
                        <TableHead className="text-right">Total</TableHead>
                        <TableHead className="w-[160px]">Split</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {rows.map((r) => {
                        const active = selectedCategory === r.category
                        return (
                          <TableRow
                            key={r.category}
                            onClick={() => toggleCategory(r.category)}
                            className={cn('cursor-pointer', active && 'bg-muted/60 hover:bg-muted/60')}
                          >
                            <TableCell
                              className={cn(
                                'sticky left-0 bg-card font-medium',
                                active && 'bg-muted/60',
                              )}
                            >
                              {r.category}
                            </TableCell>
                            {contexts.map((ctx) => {
                              const val = r.by_context[ctx] ?? 0
                              return (
                                <TableCell
                                  key={ctx}
                                  className={cn(
                                    'text-right tabular-nums',
                                    val === 0 && 'text-muted-foreground/40',
                                  )}
                                >
                                  {val === 0 ? '—' : formatPaise(val)}
                                </TableCell>
                              )
                            })}
                            <TableCell className="text-right font-medium tabular-nums">
                              {formatPaise(r.total_paise)}
                            </TableCell>
                            <TableCell>
                              <ContextSplitBar
                                contexts={contexts}
                                byContext={r.by_context}
                                total={r.total_paise}
                                widthFraction={r.total_paise / maxRowTotal}
                              />
                            </TableCell>
                          </TableRow>
                        )
                      })}
                    </TableBody>
                  </Table>
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-sm font-medium">Sub-category breakdown</h2>
                <Select
                  value={selectedCategory === '' ? ALL_CATEGORIES : selectedCategory}
                  onValueChange={(v) => setSelectedCategory(v === ALL_CATEGORIES ? '' : v)}
                >
                  <SelectTrigger size="sm" className="w-52">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={ALL_CATEGORIES}>All categories</SelectItem>
                    {rows.map((r) => (
                      <SelectItem key={r.category} value={r.category}>
                        {r.category}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              {subLoading && !bySubcategory ? (
                <Skeleton className="h-40 rounded-lg" />
              ) : subItems.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">
                  No sub-category spend {selectedCategory ? `in ${selectedCategory}` : ''} for this period.
                </p>
              ) : (
                <ul className="space-y-2">
                  {subItems.map((item) => (
                    <li
                      key={`${item.category}::${item.subcategory ?? '∅'}`}
                      className="flex items-center gap-3"
                    >
                      <div className="min-w-0 flex-1">
                        <div className="flex items-baseline justify-between gap-2">
                          <span className="truncate text-sm">
                            {item.subcategory ?? (
                              <span className="text-muted-foreground italic">Untagged</span>
                            )}
                            {!selectedCategory && (
                              <span className="ml-2 text-xs text-muted-foreground">{item.category}</span>
                            )}
                          </span>
                          <span className="shrink-0 text-sm tabular-nums">
                            {formatPaise(item.amount_paise)}
                            <span className="ml-2 text-xs text-muted-foreground">
                              {item.transaction_count} txn{item.transaction_count === 1 ? '' : 's'}
                            </span>
                          </span>
                        </div>
                        <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                          <div
                            className="h-full rounded-full bg-[var(--color-chart-1)]"
                            style={{ width: `${(item.amount_paise / maxSubAmount) * 100}%` }}
                          />
                        </div>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        </>
      ) : null}
    </div>
  )
}

function ContextStatCard({
  label,
  value,
  color,
  share,
  isDefault,
}: {
  label: string
  value: number
  color: string
  share: number
  isDefault: boolean
}) {
  return (
    <Card className="gap-1 py-4">
      <CardContent className="px-4">
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-1.5">
            <span className="inline-block size-2 shrink-0 rounded-full" style={{ backgroundColor: color }} />
            <span className="truncate text-xs text-muted-foreground">{label}</span>
          </div>
          <div className="text-xs tabular-nums text-muted-foreground">{Math.round(share * 100)}%</div>
        </div>
        <div className="mt-1 text-lg font-semibold tabular-nums">{formatPaise(value)}</div>
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
          <div className="h-full rounded-full" style={{ width: `${share * 100}%`, backgroundColor: color }} />
        </div>
        {isDefault && (
          <p className="mt-1.5 text-xs text-muted-foreground">Untagged spend (the default bucket)</p>
        )}
      </CardContent>
    </Card>
  )
}

/**
 * Horizontal bar showing how a category row splits across contexts. The overall bar length is scaled
 * by `widthFraction` (this row's total relative to the largest row), so bigger categories read as
 * longer bars; the internal segments show each context's share, colored to match the column dots.
 */
function ContextSplitBar({
  contexts,
  byContext,
  total,
  widthFraction,
}: {
  contexts: string[]
  byContext: Record<string, number>
  total: number
  widthFraction: number
}) {
  if (total <= 0) {
    return <div className="h-2 w-full rounded-full bg-muted" title="No spend" />
  }
  const title = contexts
    .filter((ctx) => (byContext[ctx] ?? 0) > 0)
    .map((ctx) => `${ctx} ${formatPaise(byContext[ctx] ?? 0)}`)
    .join(' · ')
  return (
    <div className="h-2 w-full rounded-full bg-muted">
      <div
        className="flex h-full overflow-hidden rounded-full"
        style={{ width: `${Math.max(6, widthFraction * 100)}%` }}
        title={title}
      >
        {contexts.map((ctx, i) => {
          const val = byContext[ctx] ?? 0
          if (val <= 0) return null
          return (
            <div
              key={ctx}
              className="h-full"
              style={{ width: `${(val / total) * 100}%`, backgroundColor: contextColor(i) }}
            />
          )
        })}
      </div>
    </div>
  )
}
