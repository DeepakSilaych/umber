import { useState } from 'react'
import { Plus, MapPin } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import AddTripDialog from '@/components/trips/AddTripDialog'
import { formatPaise } from '@/lib/format'

interface TripsPanelProps {
  /** Existing trip (context) names, from GET /v1/transactions/contexts. Excludes daily-expense. */
  trips: string[]
  /** context name -> total paise, from the distribution's totals_by_context. */
  totalsByContext: Record<string, number>
  /** Called after a trip is tagged, so the parent refreshes distribution + contexts. */
  onChanged: () => void
}

/**
 * Top-of-Breakdown trips manager: an "Add trip" launcher plus the list of existing trips with each
 * trip's total spend. "daily expense" is the implicit default (the NULL bucket) and never appears
 * here — these entries are the named trips only.
 */
export default function TripsPanel({ trips, totalsByContext, onChanged }: TripsPanelProps) {
  const [dialogOpen, setDialogOpen] = useState(false)

  return (
    <Card className="mb-6">
      <CardContent>
        <div className="mb-3 flex items-center justify-between gap-2">
          <div>
            <h2 className="text-sm font-medium">Trips</h2>
            <p className="text-xs text-muted-foreground">
              Tag a date range to group a trip or occasion across categories.
            </p>
          </div>
          <Button type="button" size="sm" onClick={() => setDialogOpen(true)}>
            <Plus className="size-4" />
            Add trip
          </Button>
        </div>

        {trips.length === 0 ? (
          <p className="py-4 text-center text-sm text-muted-foreground">
            No trips yet. Add one to group a date range's spend.
          </p>
        ) : (
          <ul className="grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {trips.map((trip) => (
              <li
                key={trip}
                className="flex items-center justify-between gap-3 rounded-lg border px-3 py-2"
              >
                <span className="flex min-w-0 items-center gap-2">
                  <MapPin className="size-4 shrink-0 text-muted-foreground" />
                  <span className="truncate text-sm font-medium">{trip}</span>
                </span>
                <span className="shrink-0 text-sm tabular-nums">
                  {formatPaise(totalsByContext[trip] ?? 0)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>

      <AddTripDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onCreated={() => onChanged()}
      />
    </Card>
  )
}
