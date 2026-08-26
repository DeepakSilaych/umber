import { useMemo } from 'react'
import { addDays, format, startOfDay, startOfWeek } from 'date-fns'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { formatDate, formatSignedPaise, toDateInputValue } from '@/lib/format'
import { cn } from '@/lib/utils'
import type { TimelineBucket } from '@/lib/types'

interface SpendHeatmapProps {
  buckets: TimelineBucket[]
  from: number // epoch ms of the range start
  to: number // epoch ms of the range end (usually now)
}

// 5 quantized intensity levels, faint muted (no/zero spend) → full umber brand. These are written
// as full literal class strings so Tailwind's scanner picks them up.
const LEVEL_CLASSES = ['bg-muted', 'bg-primary/25', 'bg-primary/45', 'bg-primary/70', 'bg-primary'] as const

const CELL = 13 // px
const GAP = 3 // px
const MONTH_LABEL_H = 16 // px
const WEEKDAY_LABELS = ['Mon', '', 'Wed', '', 'Fri', '', ''] as const

interface DayCell {
  date: Date
  net: number | undefined // net_spend_paise for the day; undefined = no data
  inRange: boolean
}

/**
 * GitHub-contributions-style spend heatmap, hand-rolled as a CSS grid (recharts has no calendar
 * primitive). Columns = ISO weeks (Mon-anchored), rows = day-of-week Mon(top)→Sun(bottom); each
 * cell is colored by that day's net spend, quantized into 5 levels.
 */
export default function SpendHeatmap({ buckets, from, to }: SpendHeatmapProps) {
  const { columns, maxNet } = useMemo(() => {
    // Each bucket_start_ms is IST-midnight of the day; key by local calendar date (browser runs in
    // IST for this user, so local midnight == IST midnight and the keys line up).
    const byDay = new Map<string, number>()
    let max = 0
    for (const b of buckets) {
      byDay.set(toDateInputValue(b.bucket_start_ms), b.net_spend_paise)
      if (b.net_spend_paise > max) max = b.net_spend_paise
    }

    const fromDay = startOfDay(new Date(from)).getTime()
    const toDay = startOfDay(new Date(to)).getTime()
    const gridStart = startOfWeek(new Date(from), { weekStartsOn: 1 })

    const cols: DayCell[][] = []
    let cursor = gridStart
    // Iterate week-by-week until we pass the end date.
    while (startOfDay(cursor).getTime() <= toDay) {
      const week: DayCell[] = []
      for (let i = 0; i < 7; i++) {
        const t = startOfDay(cursor).getTime()
        const inRange = t >= fromDay && t <= toDay
        week.push({
          date: cursor,
          net: inRange ? byDay.get(toDateInputValue(cursor.getTime())) : undefined,
          inRange,
        })
        cursor = addDays(cursor, 1)
      }
      cols.push(week)
    }
    return { columns: cols, maxNet: max }
  }, [buckets, from, to])

  function levelOf(net: number | undefined): number {
    if (net === undefined || net <= 0) return 0
    if (maxNet <= 0) return 1
    return Math.min(4, Math.ceil((net / maxNet) * 4))
  }

  // Month label above the column where a new month begins — but suppressed if it would sit within
  // 3 columns of the previous label (a 3-letter month is wider than one 13px cell, so adjacent
  // labels would visually collide, e.g. a partial leading month right before a full one).
  const monthLabels = useMemo(() => {
    let prevMonth = ''
    let lastLabelIdx = -Infinity
    return columns.map((week, i) => {
      const month = format(week[0].date, 'MMM')
      let out = ''
      if (month !== prevMonth && i - lastLabelIdx >= 3) {
        out = month
        lastLabelIdx = i
      }
      prevMonth = month
      return out
    })
  }, [columns])

  return (
    <TooltipProvider delayDuration={80}>
      <div className="flex gap-2 overflow-x-auto pb-1">
        {/* Weekday labels down the left, offset to clear the month-label row. */}
        <div
          className="flex flex-col text-[10px] text-muted-foreground"
          style={{ gap: GAP, marginTop: MONTH_LABEL_H }}
        >
          {WEEKDAY_LABELS.map((label, i) => (
            <div key={i} style={{ height: CELL, lineHeight: `${CELL}px` }} className="pr-1">
              {label}
            </div>
          ))}
        </div>

        <div>
          {/* Month labels row, one slot per week column. */}
          <div
            className="flex text-[10px] text-muted-foreground"
            style={{ gap: GAP, height: MONTH_LABEL_H }}
          >
            {monthLabels.map((label, i) => (
              <div key={i} style={{ width: CELL }} className="overflow-visible whitespace-nowrap">
                {label}
              </div>
            ))}
          </div>

          {/* Cells: one column per week, rows Mon→Sun. */}
          <div className="flex" style={{ gap: GAP }}>
            {columns.map((week, wi) => (
              <div key={wi} className="flex flex-col" style={{ gap: GAP }}>
                {week.map((cell, di) =>
                  cell.inRange ? (
                    <Tooltip key={di}>
                      <TooltipTrigger asChild>
                        <div
                          className={cn('rounded-[3px]', LEVEL_CLASSES[levelOf(cell.net)])}
                          style={{ width: CELL, height: CELL }}
                        />
                      </TooltipTrigger>
                      <TooltipContent>
                        <div className="font-medium">{formatDate(cell.date.getTime())}</div>
                        <div>
                          {cell.net === undefined
                            ? 'No transactions'
                            : `Net spend ${formatSignedPaise(cell.net)}`}
                        </div>
                      </TooltipContent>
                    </Tooltip>
                  ) : (
                    <div key={di} style={{ width: CELL, height: CELL }} />
                  ),
                )}
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Legend */}
      <div className="mt-3 flex items-center gap-1.5 text-xs text-muted-foreground">
        <span>Less</span>
        {LEVEL_CLASSES.map((c, i) => (
          <div key={i} className={cn('rounded-[3px]', c)} style={{ width: CELL, height: CELL }} />
        ))}
        <span>More</span>
      </div>
    </TooltipProvider>
  )
}
