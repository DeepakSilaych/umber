import { Bar, BarChart, Cell, LabelList, XAxis, YAxis } from 'recharts'
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart'
import { formatSignedPaise } from '@/lib/format'

interface CategoryBarChartProps {
  data: { category: string; paise: number }[]
}

const chartConfig = {
  paise: { label: 'Net spend' },
} satisfies ChartConfig

/** Replaces the old div-width-percentage bar hack with a real Recharts horizontal bar chart. */
export default function CategoryBarChart({ data }: CategoryBarChartProps) {
  const values = data.map((d) => d.paise)
  const domainMax = Math.max(0, ...values)
  const domainMin = Math.min(0, ...values)

  return (
    <ChartContainer config={chartConfig} className="aspect-auto w-full" style={{ height: Math.max(220, data.length * 40) }}>
      <BarChart data={data} layout="vertical" margin={{ top: 4, right: 48, bottom: 4, left: 4 }}>
        <XAxis type="number" hide domain={[domainMin, domainMax]} />
        <YAxis
          type="category"
          dataKey="category"
          tickLine={false}
          axisLine={false}
          width={128}
          tick={{ fill: 'var(--muted-foreground)' }}
        />
        <ChartTooltip
          cursor={{ fill: 'var(--muted)' }}
          content={<ChartTooltipContent hideLabel formatter={(value) => formatSignedPaise(Number(value))} />}
        />
        {/* isAnimationActive={false}: recharts 3.x's bar entrance animation never resolves to its
            final frame in this environment, leaving every bar collapsed at width 0 — a real bug we
            hit, not a preference. Disabling it renders the (static) bars correctly and matches the
            original div-bar hack, which had no animation either. */}
        <Bar dataKey="paise" radius={4} isAnimationActive={false}>
          {data.map((entry) => (
            <Cell
              key={entry.category}
              fill={entry.paise < 0 ? 'var(--color-emerald-500)' : 'var(--color-chart-1)'}
            />
          ))}
          <LabelList
            dataKey="paise"
            position="right"
            className="fill-foreground"
            fontSize={12}
            formatter={(value) => formatSignedPaise(Number(value))}
          />
        </Bar>
      </BarChart>
    </ChartContainer>
  )
}
