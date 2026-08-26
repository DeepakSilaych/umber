import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { format } from 'date-fns'
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart'
import { formatPaise } from '@/lib/format'
import type { BalanceSeriesPoint } from '@/lib/types'

interface BalanceAreaChartProps {
  points: BalanceSeriesPoint[]
}

const chartConfig = {
  balance: { label: 'Balance', color: 'var(--chart-1)' },
} satisfies ChartConfig

const compactInr = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  notation: 'compact',
  maximumFractionDigits: 1,
})

/** Daily closing-balance area chart for one account. Points are already ascending daily closes. */
export default function BalanceAreaChart({ points }: BalanceAreaChartProps) {
  const data = points.map((p) => ({ day: p.day_ms, balance: p.balance_paise }))

  return (
    <ChartContainer config={chartConfig} className="aspect-auto h-64 w-full">
      <AreaChart data={data} margin={{ top: 8, right: 12, bottom: 4, left: 4 }}>
        <CartesianGrid vertical={false} />
        <XAxis
          dataKey="day"
          type="number"
          scale="time"
          domain={['dataMin', 'dataMax']}
          tickLine={false}
          axisLine={false}
          tickMargin={8}
          minTickGap={32}
          tickFormatter={(value) => format(new Date(Number(value)), 'd MMM')}
        />
        <YAxis
          tickLine={false}
          axisLine={false}
          width={64}
          tickFormatter={(value) => compactInr.format(Number(value) / 100)}
        />
        <ChartTooltip
          cursor={{ stroke: 'var(--border)' }}
          content={
            <ChartTooltipContent
              labelFormatter={(value) => format(new Date(Number(value)), 'd MMM yyyy')}
              formatter={(value) => formatPaise(Number(value))}
            />
          }
        />
        <defs>
          <linearGradient id="fillBalance" x1="0" y1="0" x2="0" y2="1">
            <stop offset="5%" stopColor="var(--color-balance)" stopOpacity={0.4} />
            <stop offset="95%" stopColor="var(--color-balance)" stopOpacity={0.05} />
          </linearGradient>
        </defs>
        <Area
          dataKey="balance"
          type="monotone"
          stroke="var(--color-balance)"
          strokeWidth={2}
          fill="url(#fillBalance)"
          isAnimationActive={false}
        />
      </AreaChart>
    </ChartContainer>
  )
}
