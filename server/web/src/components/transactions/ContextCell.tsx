import { useEffect, useId, useState } from 'react'
import { Input } from '@/components/ui/input'
import type { TxnOut } from '@/lib/types'

interface ContextCellProps {
  txn: TxnOut
  /** Existing context (trip) names offered as datalist suggestions. */
  suggestions: string[]
  /** Commits the new value; empty string clears it → back to the daily-expense default. */
  onCommit: (txn: TxnOut, context: string) => void
}

/**
 * Inline-editable context cell — a light text input backed by a <datalist> of existing trip names,
 * mirroring SubcategoryCell. Commits on blur or Enter (only when the value actually changed), so a
 * plain focus-then-blur never fires a needless PATCH. Escape reverts the in-progress edit. When the
 * value is empty, a muted "daily expense" placeholder stands in for the default (NULL) bucket.
 */
export default function ContextCell({ txn, suggestions, onCommit }: ContextCellProps) {
  const listId = useId()
  const [draft, setDraft] = useState(txn.context ?? '')

  // Keep the input in sync when the row is replaced by the server response (or another edit).
  useEffect(() => {
    setDraft(txn.context ?? '')
  }, [txn.context])

  function commit() {
    const next = draft.trim()
    if (next === (txn.context ?? '')) return
    onCommit(txn, next)
  }

  return (
    <>
      <Input
        list={listId}
        value={draft}
        placeholder="daily expense"
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            e.currentTarget.blur()
          } else if (e.key === 'Escape') {
            setDraft(txn.context ?? '')
            e.currentTarget.blur()
          }
        }}
        className="h-8 w-40 border-transparent bg-transparent px-2 shadow-none placeholder:text-muted-foreground/70 placeholder:italic hover:border-input focus-visible:border-input"
      />
      <datalist id={listId}>
        {suggestions.map((s) => (
          <option key={s} value={s} />
        ))}
      </datalist>
    </>
  )
}
