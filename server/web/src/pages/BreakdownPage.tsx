import { useEffect, useMemo, useState } from 'react'
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
import { ApiError, getBySubcategory, getDistribution, type WindowParams } from '@/lib/api'
import { formatPaise } from '@/lib/format'
import { cn } from '@/lib/utils'
import type {
  BreakdownPeriod,
  DistributionResponse,
  SubcategoryBreakdownResponse,
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

export default function BreakdownPage() {
  const [period, setPeriod] = useState<BreakdownPeriod>('this_month')
  const [distribution, setDistribution] = useState<DistributionResponse | null>(null)
  const [bySubcategory, setBySubcategory] = useState<SubcategoryBreakdownResponse | null>(null)
  const [selectedCategory, setSelectedCategory] = useState('') // '' = all categories
  const [loading, setLoading] = useState(true)
  const [subLoading, setSubLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Distribution matrix depends only on the period.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    getDistribution(windowParamsFor(period))
      .then((res) => {
        if (!cancelled) setDistribution(res)
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
  }, [period])

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

  const typedTotal =
    (distribution?.normal_total_paise ?? 0) + (distribution?.special_total_paise ?? 0)
  const grandTotal = typedTotal + (distribution?.untyped_total_paise ?? 0)

  const rows = distribution?.rows ?? []
  // Longest typed bar in the matrix, to scale each row's bar width by its share of the biggest row.
  const maxRowTotal = useMemo(
    () => Math.max(1, ...rows.map((r) => r.normal_paise + r.special_paise)),
    [rows],
  )

  const subItems = bySubcategory?.items ?? []
  const maxSubAmount = useMemo(
    () => Math.max(1, ...subItems.map((i) => i.amount_paise)),
    [subItems],
  )

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
          <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
            <StatCard
              label="Normal"
              value={distribution.normal_total_paise}
              tone="normal"
              share={grandTotal > 0 ? distribution.normal_total_paise / grandTotal : 0}
            />
            <StatCard
              label="Special"
              value={distribution.special_total_paise}
              tone="special"
              share={grandTotal > 0 ? distribution.special_total_paise / grandTotal : 0}
            />
            <StatCard
              label="Untyped"
              value={distribution.untyped_total_paise}
              tone="untyped"
              share={grandTotal > 0 ? distribution.untyped_total_paise / grandTotal : 0}
              hint="Spend not yet marked Normal or Special"
            />
          </div>

          <Card className="mb-6">
            <CardContent>
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-medium">Category × Normal / Special</h2>
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
                        <TableHead>Category</TableHead>
                        <TableHead className="text-right">Normal</TableHead>
                        <TableHead className="text-right">Special</TableHead>
                        <TableHead className="text-right">Total</TableHead>
                        <TableHead className="w-[160px]">Split</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {rows.map((r) => {
                        const rowTotal = r.normal_paise + r.special_paise
                        const active = selectedCategory === r.category
                        return (
                          <TableRow
                            key={r.category}
                            onClick={() => toggleCategory(r.category)}
                            className={cn(
                              'cursor-pointer',
                              active && 'bg-muted/60 hover:bg-muted/60',
                            )}
                          >
                            <TableCell className="font-medium">{r.category}</TableCell>
                            <TableCell className="text-right tabular-nums">
                              {formatPaise(r.normal_paise)}
                            </TableCell>
                            <TableCell className="text-right tabular-nums text-amber-600 dark:text-amber-400">
                              {formatPaise(r.special_paise)}
                            </TableCell>
                            <TableCell className="text-right font-medium tabular-nums">
                              {formatPaise(rowTotal)}
                            </TableCell>
                            <TableCell>
                              <SplitBar
                                normal={r.normal_paise}
                                special={r.special_paise}
                                widthFraction={rowTotal / maxRowTotal}
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

function StatCard({
  label,
  value,
  tone,
  share,
  hint,
}: {
  label: string
  value: number
  tone: 'normal' | 'special' | 'untyped'
  share: number
  hint?: string
}) {
  const valueClasses =
    tone === 'special'
      ? 'text-amber-600 dark:text-amber-400'
      : tone === 'untyped'
        ? 'text-muted-foreground'
        : 'text-foreground'
  const barClass =
    tone === 'special' ? 'bg-amber-500' : tone === 'untyped' ? 'bg-muted-foreground/40' : 'bg-emerald-500'

  return (
    <Card className="gap-1 py-4">
      <CardContent className="px-4">
        <div className="flex items-center justify-between">
          <div className="text-xs text-muted-foreground">{label}</div>
          <div className="text-xs tabular-nums text-muted-foreground">{Math.round(share * 100)}%</div>
        </div>
        <div className={cn('mt-1 text-lg font-semibold tabular-nums', valueClasses)}>
          {formatPaise(value)}
        </div>
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
          <div className={cn('h-full rounded-full', barClass)} style={{ width: `${share * 100}%` }} />
        </div>
        {hint && <p className="mt-1.5 text-xs text-muted-foreground">{hint}</p>}
      </CardContent>
    </Card>
  )
}

/**
 * Two-segment horizontal bar showing a row's Normal:Special split. The overall bar length is scaled
 * by `widthFraction` (this row's typed total relative to the largest row), so bigger categories read
 * as longer bars; the internal split shows how much of that is Normal (emerald) vs Special (amber).
 */
function SplitBar({
  normal,
  special,
  widthFraction,
}: {
  normal: number
  special: number
  widthFraction: number
}) {
  const total = normal + special
  if (total <= 0) {
    return <div className="h-2 w-full rounded-full bg-muted" title="Nothing typed yet" />
  }
  const normalPct = (normal / total) * 100
  const specialPct = (special / total) * 100
  return (
    <div className="h-2 w-full rounded-full bg-muted">
      <div
        className="flex h-full overflow-hidden rounded-full"
        style={{ width: `${Math.max(6, widthFraction * 100)}%` }}
        title={`Normal ${formatPaise(normal)} · Special ${formatPaise(special)}`}
      >
        <div className="h-full bg-emerald-500" style={{ width: `${normalPct}%` }} />
        <div className="h-full bg-amber-500" style={{ width: `${specialPct}%` }} />
      </div>
    </div>
  )
}
