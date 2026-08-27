import { useEffect, useId, useState } from 'react'
import { Input } from '@/components/ui/input'
import type { TxnOut } from '@/lib/types'

interface SubcategoryCellProps {
  txn: TxnOut
  /** Existing sub-category values offered as datalist suggestions. */
  suggestions: string[]
  /** Commits the new value; empty string clears the sub-category. */
  onCommit: (txn: TxnOut, subcategory: string) => void
}

/**
 * Inline-editable sub-category cell — a light text input backed by a <datalist> of the existing
 * sub-category values. Commits on blur or Enter (and only when the value actually changed), so a
 * plain focus-then-blur never fires a needless PATCH. Escape reverts the in-progress edit.
 */
export default function SubcategoryCell({ txn, suggestions, onCommit }: SubcategoryCellProps) {
  const listId = useId()
  const [draft, setDraft] = useState(txn.subcategory ?? '')

  // Keep the input in sync when the row is replaced by the server response (or another edit).
  useEffect(() => {
    setDraft(txn.subcategory ?? '')
  }, [txn.subcategory])

  function commit() {
    const next = draft.trim()
    if (next === (txn.subcategory ?? '')) return
    onCommit(txn, next)
  }

  return (
    <>
      <Input
        list={listId}
        value={draft}
        placeholder="—"
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            e.currentTarget.blur()
          } else if (e.key === 'Escape') {
            setDraft(txn.subcategory ?? '')
            e.currentTarget.blur()
          }
        }}
        className="h-8 w-40 border-transparent bg-transparent px-2 shadow-none hover:border-input focus-visible:border-input"
      />
      <datalist id={listId}>
        {suggestions.map((s) => (
          <option key={s} value={s} />
        ))}
      </datalist>
    </>
  )
}
