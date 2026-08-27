import { useEffect, useState } from 'react'
import { endOfMonth, startOfMonth, subMonths } from 'date-fns'
import { PencilIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from 'sonner'
import ErrorAlert from '@/components/ErrorAlert'
import ToggleGroupFilter from '@/components/ToggleGroupFilter'
import BucketDialog from '@/components/goals/BucketDialog'
import {
  ApiError,
  deleteBudgetBucket,
  getBudget,
  getBudgetProgress,
  patchBudgetIncome,
} from '@/lib/api'
import { formatPaise, formatSignedPaise, rupeesToPaise } from '@/lib/format'
import { cn } from '@/lib/utils'
import type {
  BudgetBucket,
  BudgetBucketProgress,
  BudgetOut,
  BudgetPeriod,
  BudgetProgressResponse,
} from '@/lib/types'

const PERIODS: { value: BudgetPeriod; label: string }[] = [
  { value: 'this_month', label: 'This month' },
  { value: 'last_month', label: 'Last month' },
  { value: 'this_year', label: 'This year' },
]

function progressParams(period: BudgetPeriod) {
  if (period === 'last_month') {
    const ref = subMonths(Date.now(), 1)
    return { from_ms: startOfMonth(ref).getTime(), to_ms: endOfMonth(ref).getTime() }
  }
  return { period }
}

/** Fraction 0..1 of a bucket's target that has been used/reached, clamped for the bar width. */
function fillFraction(p: BudgetBucketProgress): number {
  if (p.target_paise <= 0) return p.actual_paise > 0 ? 1 : 0
  return Math.max(0, Math.min(1, p.actual_paise / p.target_paise))
}

/** Bar color: spend buckets go green→amber(≥85%)→red(over); savings invert (green when met). */
function barColor(p: BudgetBucketProgress): string {
  if (p.kind === 'savings') {
    return p.actual_paise >= p.target_paise ? 'bg-emerald-500' : 'bg-red-500'
  }
  if (p.target_paise > 0 && p.actual_paise > p.target_paise) return 'bg-red-500'
  if (p.target_paise > 0 && p.actual_paise / p.target_paise >= 0.85) return 'bg-amber-500'
  return 'bg-emerald-500'
}

export default function GoalsPage() {
  const [period, setPeriod] = useState<BudgetPeriod>('this_month')
  const [budget, setBudget] = useState<BudgetOut | null>(null)
  const [progress, setProgress] = useState<BudgetProgressResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [bucketDialog, setBucketDialog] = useState<{ open: boolean; bucket: BudgetBucket | null }>({
    open: false,
    bucket: null,
  })
  const [deleteTarget, setDeleteTarget] = useState<BudgetBucketProgress | null>(null)
  const [incomeOpen, setIncomeOpen] = useState(false)
  const [incomeInput, setIncomeInput] = useState('')
  const [incomeSaving, setIncomeSaving] = useState(false)

  function load() {
    setLoading(true)
    setError(null)
    Promise.all([getBudget(), getBudgetProgress(progressParams(period))])
      .then(([b, p]) => {
        setBudget(b)
        setProgress(p)
      })
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) return
        setError(err instanceof ApiError ? err.message : 'Failed to load budget.')
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    load()
  }, [period]) // eslint-disable-line -- load reads only `period` from render scope

  async function saveIncome() {
    setIncomeSaving(true)
    try {
      const updated = await patchBudgetIncome(rupeesToPaise(incomeInput))
      setBudget(updated)
      // Reflect the new income in the savings-bucket actual immediately.
      const p = await getBudgetProgress(progressParams(period))
      setProgress(p)
      toast.success('Monthly income updated.')
      setIncomeOpen(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to update income.')
    } finally {
      setIncomeSaving(false)
    }
  }

  async function confirmDelete() {
    if (!deleteTarget) return
    try {
      await deleteBudgetBucket(deleteTarget.id)
      toast.success('Bucket deleted.')
      setDeleteTarget(null)
      load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to delete bucket.')
    }
  }

  const savings = progress?.buckets.find((b) => b.kind === 'savings') ?? null
  const monthlyIncome = progress?.monthly_income_paise ?? budget?.monthly_income_paise ?? 0
  // Full bucket definitions keyed by id, so the edit dialog can seed sort_order etc.
  const bucketById = new Map((budget?.buckets ?? []).map((b) => [b.id, b]))

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold tracking-tight">Goals</h1>
        <div className="flex flex-wrap items-center gap-2">
          <ToggleGroupFilter
            options={PERIODS}
            value={period}
            onChange={(v) => setPeriod(v as BudgetPeriod)}
          />
          <Button
            onClick={() => setBucketDialog({ open: true, bucket: null })}
            disabled={!budget}
          >
            <PlusIcon />
            Add bucket
          </Button>
        </div>
      </div>

      {error && <ErrorAlert message={error} className="mb-4" />}

      {loading && !progress ? (
        <div className="space-y-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-20 rounded-lg" />
            ))}
          </div>
          <Skeleton className="h-64 rounded-lg" />
        </div>
      ) : progress ? (
        <>
          <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-3">
            <Card className="gap-1 py-4">
              <CardContent className="px-4">
                <div className="flex items-center justify-between">
                  <div className="text-xs text-muted-foreground">Monthly income</div>
                  <button
                    type="button"
                    className="text-muted-foreground hover:text-foreground"
                    onClick={() => {
                      setIncomeInput(monthlyIncome ? String(monthlyIncome / 100) : '')
                      setIncomeOpen(true)
                    }}
                    aria-label="Edit monthly income"
                  >
                    <PencilIcon className="size-3.5" />
                  </button>
                </div>
                <div className="mt-1 text-lg font-semibold tabular-nums">
                  {formatPaise(monthlyIncome)}
                </div>
              </CardContent>
            </Card>

            <Card className="gap-1 py-4">
              <CardContent className="px-4">
                <div className="text-xs text-muted-foreground">Total spent</div>
                <div className="mt-1 text-lg font-semibold tabular-nums text-umber-600 dark:text-umber-300">
                  {formatSignedPaise(progress.total_spent_paise)}
                </div>
              </CardContent>
            </Card>

            <Card className="gap-1 py-4">
              <CardContent className="px-4">
                <div className="text-xs text-muted-foreground">
                  {savings ? savings.name : 'Savings / Buffer'}
                </div>
                {savings ? (
                  <div
                    className={cn(
                      'mt-1 text-lg font-semibold tabular-nums',
                      savings.actual_paise >= savings.target_paise
                        ? 'text-emerald-600 dark:text-emerald-400'
                        : 'text-red-600 dark:text-red-400',
                    )}
                  >
                    {formatSignedPaise(savings.actual_paise)}
                    <span className="ml-1 text-xs font-normal text-muted-foreground">
                      of {formatPaise(savings.target_paise)}
                    </span>
                  </div>
                ) : (
                  <div className="mt-1 text-sm text-muted-foreground">No savings bucket</div>
                )}
              </CardContent>
            </Card>
          </div>

          <Card>
            <CardContent className="space-y-5">
              {progress.buckets.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">
                  No budget buckets yet. Add one to start tracking targets.
                </p>
              ) : (
                progress.buckets.map((b) => {
                  const pct = b.target_paise > 0 ? Math.round((b.actual_paise / b.target_paise) * 100) : null
                  return (
                    <div key={b.id}>
                      <div className="mb-1.5 flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <span className="font-medium">{b.name}</span>
                          {b.kind === 'savings' && (
                            <Badge variant="secondary" className="text-[10px]">
                              savings
                            </Badge>
                          )}
                        </div>
                        <div className="flex items-center gap-3">
                          <span className="text-sm tabular-nums text-muted-foreground">
                            {formatSignedPaise(b.actual_paise)} / {formatPaise(b.target_paise)}
                            {pct !== null && <span className="ml-1">· {pct}%</span>}
                          </span>
                          <div className="flex items-center gap-1">
                            <button
                              type="button"
                              className="text-muted-foreground hover:text-foreground"
                              onClick={() =>
                                setBucketDialog({ open: true, bucket: bucketById.get(b.id) ?? null })
                              }
                              aria-label={`Edit ${b.name}`}
                            >
                              <PencilIcon className="size-3.5" />
                            </button>
                            <button
                              type="button"
                              className="text-muted-foreground hover:text-destructive"
                              onClick={() => setDeleteTarget(b)}
                              aria-label={`Delete ${b.name}`}
                            >
                              <Trash2Icon className="size-3.5" />
                            </button>
                          </div>
                        </div>
                      </div>
                      <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                        <div
                          className={cn('h-full rounded-full transition-all', barColor(b))}
                          style={{ width: `${fillFraction(b) * 100}%` }}
                        />
                      </div>
                      {(b.category_keys.length > 0 || b.subcategory_keywords.length > 0) && (
                        <div className="mt-2 flex flex-wrap gap-1">
                          {b.category_keys.map((cat) => (
                            <Badge key={cat} variant="secondary" className="text-[10px] font-normal">
                              {cat}
                            </Badge>
                          ))}
                          {b.subcategory_keywords.map((kw) => (
                            <Badge
                              key={`kw-${kw}`}
                              variant="outline"
                              className="text-[10px] font-normal text-muted-foreground"
                            >
                              #{kw}
                            </Badge>
                          ))}
                        </div>
                      )}
                    </div>
                  )
                })
              )}

              {/* Leakage: spend in categories mapped to no bucket. */}
              <div className="border-t pt-4">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium text-muted-foreground">Unbudgeted</span>
                  <span className="text-sm tabular-nums text-muted-foreground">
                    {formatSignedPaise(progress.unbudgeted_paise)}
                  </span>
                </div>
                <p className="mt-0.5 text-xs text-muted-foreground">
                  Spend in categories not assigned to any bucket.
                </p>
              </div>
            </CardContent>
          </Card>
        </>
      ) : null}

      <BucketDialog
        open={bucketDialog.open}
        onOpenChange={(open) => setBucketDialog((prev) => ({ ...prev, open }))}
        bucket={bucketDialog.bucket}
        nextSortOrder={budget?.buckets.length ?? 0}
        onSaved={load}
      />

      <Dialog open={incomeOpen} onOpenChange={setIncomeOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Monthly income</DialogTitle>
            <DialogDescription>
              Used to compute the savings bucket (income minus total spend).
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2">
            <Label htmlFor="income-input">Amount (₹)</Label>
            <Input
              id="income-input"
              type="number"
              inputMode="decimal"
              step="0.01"
              placeholder="0.00"
              value={incomeInput}
              onChange={(e) => setIncomeInput(e.target.value)}
            />
          </div>
          <DialogFooter>
            <Button onClick={saveIncome} disabled={incomeSaving}>
              {incomeSaving ? 'Saving…' : 'Save'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete “{deleteTarget?.name}”?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes the budget bucket. Your transactions are not affected.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDelete}>Delete</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
