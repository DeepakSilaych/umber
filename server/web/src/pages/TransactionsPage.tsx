import { useCallback, useEffect, useId, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import CategorySelect from '@/components/CategorySelect'
import DateRangeFilter from '@/components/DateRangeFilter'
import ErrorAlert from '@/components/ErrorAlert'
import ToggleGroupFilter from '@/components/ToggleGroupFilter'
import TransactionsTable from '@/components/transactions/TransactionsTable'
import {
  ApiError,
  getSubcategories,
  listTransactions,
  patchTransaction,
  type TransactionFilters,
} from '@/lib/api'
import { dateInputToEndOfDayEpochMs, dateInputToEpochMs } from '@/lib/format'
import type { SpendType, TransactionPatch, TxnOut } from '@/lib/types'

const PAGE_SIZE = 50

const NEEDS_REVIEW_OPTIONS = [
  { value: 'all', label: 'All' },
  { value: 'needs_review', label: 'Needs review' },
]

const SPEND_TYPE_OPTIONS = [
  { value: 'all', label: 'All' },
  { value: 'NORMAL', label: 'Normal' },
  { value: 'SPECIAL', label: 'Special' },
]

export default function TransactionsPage() {
  const [items, setItems] = useState<TxnOut[]>([])
  const [total, setTotal] = useState(0)
  const [offset, setOffset] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [needsReviewOnly, setNeedsReviewOnly] = useState(false)
  const [category, setCategory] = useState('')
  const [merchant, setMerchant] = useState('')
  const [merchantInput, setMerchantInput] = useState('')
  const [spendType, setSpendType] = useState('') // '' | 'NORMAL' | 'SPECIAL'
  const [subcategory, setSubcategory] = useState('')
  const [subcategoryInput, setSubcategoryInput] = useState('')
  const [occurredFrom, setOccurredFrom] = useState('')
  const [occurredTo, setOccurredTo] = useState('')

  const [subcategorySuggestions, setSubcategorySuggestions] = useState<string[]>([])
  const subcatFilterListId = useId()

  // Existing sub-category values power both the inline-edit datalist and the filter box.
  const loadSubcategories = useCallback(() => {
    getSubcategories()
      .then(setSubcategorySuggestions)
      .catch(() => {
        /* non-fatal: suggestions are a convenience, not required for editing */
      })
  }, [])

  useEffect(() => {
    loadSubcategories()
  }, [loadSubcategories])

  // Debounce the free-text boxes so we don't fire a request per keystroke.
  useEffect(() => {
    const handle = setTimeout(() => setMerchant(merchantInput), 300)
    return () => clearTimeout(handle)
  }, [merchantInput])
  useEffect(() => {
    const handle = setTimeout(() => setSubcategory(subcategoryInput), 300)
    return () => clearTimeout(handle)
  }, [subcategoryInput])

  // Any filter change resets pagination back to the first page.
  useEffect(() => {
    setOffset(0)
  }, [needsReviewOnly, category, merchant, spendType, subcategory, occurredFrom, occurredTo])

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    const filters: TransactionFilters = {
      limit: PAGE_SIZE,
      offset,
    }
    if (needsReviewOnly) filters.needs_review = true
    if (category) filters.category = category
    if (merchant) filters.merchant = merchant
    if (spendType) filters.spend_type = spendType
    if (subcategory) filters.subcategory = subcategory
    if (occurredFrom) filters.occurred_from = dateInputToEpochMs(occurredFrom)
    if (occurredTo) filters.occurred_to = dateInputToEndOfDayEpochMs(occurredTo)

    try {
      const res = await listTransactions(filters)
      setItems(res.items)
      setTotal(res.total)
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) return // redirect already in flight
      setError(err instanceof ApiError ? err.message : 'Failed to load transactions.')
    } finally {
      setLoading(false)
    }
  }, [needsReviewOnly, category, merchant, spendType, subcategory, occurredFrom, occurredTo, offset])

  useEffect(() => {
    load()
  }, [load])

  // Optimistically apply `optimistic` to the row, PATCH, then swap in the server row; revert on error.
  const patchAndReplace = useCallback(
    async (txn: TxnOut, patch: TransactionPatch, optimistic: Partial<TxnOut>, errorLabel: string) => {
      let reverted = false
      setItems((cur) => cur.map((t) => (t.client_id === txn.client_id ? { ...t, ...optimistic } : t)))
      try {
        const updated = await patchTransaction(txn.client_id, patch)
        setItems((cur) => cur.map((t) => (t.client_id === txn.client_id ? updated : t)))
      } catch (err) {
        reverted = true
        setItems((cur) => cur.map((t) => (t.client_id === txn.client_id ? txn : t)))
        setError(err instanceof ApiError ? err.message : errorLabel)
      }
      return !reverted
    },
    [],
  )

  function handleCategoryChange(txn: TxnOut, newCategory: string) {
    void patchAndReplace(
      txn,
      { category: newCategory },
      { category: newCategory, needs_review: false },
      'Failed to update category.',
    )
  }

  async function handleSubcategoryChange(txn: TxnOut, newSubcategory: string) {
    const ok = await patchAndReplace(
      txn,
      { subcategory: newSubcategory },
      { subcategory: newSubcategory || null },
      'Failed to update sub-category.',
    )
    // A newly-typed value should show up in the suggestion list for the next row.
    if (ok && newSubcategory && !subcategorySuggestions.includes(newSubcategory)) {
      loadSubcategories()
    }
  }

  function handleSpendTypeChange(txn: TxnOut, newSpendType: string) {
    void patchAndReplace(
      txn,
      { spend_type: newSpendType },
      { spend_type: (newSpendType || null) as SpendType | null },
      'Failed to update type.',
    )
  }

  const from = total === 0 ? 0 : offset + 1
  const to = Math.min(offset + PAGE_SIZE, total)
  const hasActiveFilters =
    category || merchantInput || subcategoryInput || spendType || occurredFrom || occurredTo || needsReviewOnly

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold tracking-tight">Transactions</h1>
      </div>

      <div className="mb-4 flex flex-wrap items-end gap-3 rounded-lg border bg-card p-3">
        <ToggleGroupFilter
          options={NEEDS_REVIEW_OPTIONS}
          value={needsReviewOnly ? 'needs_review' : 'all'}
          onChange={(v) => setNeedsReviewOnly(v === 'needs_review')}
        />

        <div>
          <Label className="mb-1 text-xs font-medium text-muted-foreground">Type</Label>
          <ToggleGroupFilter
            options={SPEND_TYPE_OPTIONS}
            value={spendType || 'all'}
            onChange={(v) => setSpendType(v === 'all' ? '' : v)}
          />
        </div>

        <div>
          <Label htmlFor="filter-category" className="mb-1 text-xs font-medium text-muted-foreground">
            Category
          </Label>
          <CategorySelect id="filter-category" value={category} onChange={setCategory} includeAllOption />
        </div>

        <div>
          <Label htmlFor="filter-subcategory" className="mb-1 text-xs font-medium text-muted-foreground">
            Sub-category
          </Label>
          <Input
            id="filter-subcategory"
            type="text"
            list={subcatFilterListId}
            value={subcategoryInput}
            onChange={(e) => setSubcategoryInput(e.target.value)}
            placeholder="Any"
            className="h-9 w-40"
          />
          <datalist id={subcatFilterListId}>
            {subcategorySuggestions.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
        </div>

        <div>
          <Label htmlFor="filter-merchant" className="mb-1 text-xs font-medium text-muted-foreground">
            Merchant
          </Label>
          <Input
            id="filter-merchant"
            type="text"
            value={merchantInput}
            onChange={(e) => setMerchantInput(e.target.value)}
            placeholder="Search merchant…"
            className="h-9 w-48"
          />
        </div>

        <div>
          <Label className="mb-1 text-xs font-medium text-muted-foreground">Date range</Label>
          <DateRangeFilter from={occurredFrom} to={occurredTo} onChange={(f, t) => { setOccurredFrom(f); setOccurredTo(t) }} />
        </div>

        {hasActiveFilters && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              setCategory('')
              setMerchantInput('')
              setSubcategoryInput('')
              setSpendType('')
              setOccurredFrom('')
              setOccurredTo('')
              setNeedsReviewOnly(false)
            }}
            className="text-muted-foreground"
          >
            Clear filters
          </Button>
        )}
      </div>

      {error && <ErrorAlert message={error} className="mb-4" />}

      <TransactionsTable
        items={items}
        loading={loading}
        subcategorySuggestions={subcategorySuggestions}
        onCategoryChange={handleCategoryChange}
        onSubcategoryChange={handleSubcategoryChange}
        onSpendTypeChange={handleSpendTypeChange}
      />

      <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
        <span>{loading ? 'Loading…' : total === 0 ? 'No results' : `Showing ${from}–${to} of ${total}`}</span>
        <div className="flex gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setOffset((o) => Math.max(0, o - PAGE_SIZE))}
            disabled={offset === 0 || loading}
          >
            Previous
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setOffset((o) => o + PAGE_SIZE)}
            disabled={offset + PAGE_SIZE >= total || loading}
          >
            Next
          </Button>
        </div>
      </div>
    </div>
  )
}
