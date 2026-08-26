import { useState } from 'react'
import { CalendarIcon } from 'lucide-react'
import type { DateRange } from 'react-day-picker'
import { Button } from '@/components/ui/button'
import { Calendar } from '@/components/ui/calendar'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'
import { dateInputToEpochMs, formatDate, toDateInputValue } from '@/lib/format'

interface DateRangeFilterProps {
  /** yyyy-mm-dd, or '' when unset — same value contract as the <input type="date"> pair this replaces. */
  from: string
  to: string
  onChange: (from: string, to: string) => void
  className?: string
}

/**
 * Shared replacement for the "From"/"To" <input type="date"> pair — a single Popover + Calendar
 * (range mode) that reports back the same yyyy-mm-dd strings the rest of the filter state expects.
 */
export default function DateRangeFilter({ from, to, onChange, className }: DateRangeFilterProps) {
  const [open, setOpen] = useState(false)

  const range: DateRange | undefined =
    from || to
      ? {
          from: from ? new Date(dateInputToEpochMs(from)) : undefined,
          to: to ? new Date(dateInputToEpochMs(to)) : undefined,
        }
      : undefined

  const label =
    from && to
      ? `${formatDate(dateInputToEpochMs(from))} – ${formatDate(dateInputToEpochMs(to))}`
      : from
        ? `From ${formatDate(dateInputToEpochMs(from))}`
        : to
          ? `Until ${formatDate(dateInputToEpochMs(to))}`
          : 'Any date'

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          className={cn('justify-start text-left font-normal', !from && !to && 'text-muted-foreground', className)}
        >
          <CalendarIcon className="opacity-60" />
          {label}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-auto p-0" align="start">
        <Calendar
          mode="range"
          defaultMonth={range?.from}
          selected={range}
          onSelect={(next) => {
            const nextFrom = next?.from ? toDateInputValue(next.from.getTime()) : ''
            const nextTo = next?.to ? toDateInputValue(next.to.getTime()) : ''
            onChange(nextFrom, nextTo)
            if (nextFrom && nextTo) setOpen(false)
          }}
          numberOfMonths={2}
        />
      </PopoverContent>
    </Popover>
  )
}
