import { Badge } from '@/components/ui/badge'
import type { Direction } from '@/lib/types'

export function DirectionBadge({ direction }: { direction: Direction }) {
  const isDebit = direction === 'DEBIT'
  return (
    <Badge
      className={
        isDebit
          ? 'border-transparent bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'
          : 'border-transparent bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'
      }
    >
      {isDebit ? 'Debit' : 'Credit'}
    </Badge>
  )
}

export function NeedsReviewBadge({ needsReview }: { needsReview: boolean }) {
  if (!needsReview) return null
  return (
    <Badge className="border-transparent bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">
      Needs review
    </Badge>
  )
}
