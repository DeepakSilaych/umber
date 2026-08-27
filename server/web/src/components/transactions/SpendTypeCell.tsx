import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { cn } from '@/lib/utils'
import type { SpendType, TxnOut } from '@/lib/types'

interface SpendTypeCellProps {
  txn: TxnOut
  /** Commits the new spend_type; empty string clears it. */
  onCommit: (txn: TxnOut, spendType: string) => void
}

// Radix Select forbids an empty-string item value, so "unset" rides this sentinel internally and
// is translated back to '' (which clears the field server-side) at the callback boundary.
const NONE_VALUE = '__none__'

/**
 * Compact spend-type control: a small Select cycling between Normal / Special / unset. SPECIAL is
 * rendered as a distinct amber pill, NORMAL muted, and untyped a faint placeholder — so the "how
 * complete is my typing" signal reads at a glance down the column.
 */
export default function SpendTypeCell({ txn, onCommit }: SpendTypeCellProps) {
  const value: SpendType | null = txn.spend_type
  const triggerTone =
    value === 'SPECIAL'
      ? 'border-transparent bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300'
      : value === 'NORMAL'
        ? 'border-transparent bg-muted text-muted-foreground'
        : 'border-dashed text-muted-foreground'

  return (
    <Select
      value={value ?? NONE_VALUE}
      onValueChange={(next) => onCommit(txn, next === NONE_VALUE ? '' : next)}
    >
      <SelectTrigger
        size="sm"
        className={cn('h-7 w-[104px] rounded-full px-2.5 text-xs font-medium', triggerTone)}
        aria-label="Spend type"
      >
        <SelectValue placeholder="Set type" />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="NORMAL">Normal</SelectItem>
        <SelectItem value="SPECIAL">Special</SelectItem>
        <SelectItem value={NONE_VALUE}>Unset</SelectItem>
      </SelectContent>
    </Select>
  )
}
