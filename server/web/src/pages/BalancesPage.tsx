import { useEffect, useState } from 'react'
import { PlusIcon, WalletIcon } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import ErrorAlert from '@/components/ErrorAlert'
import AddAccountDialog, { ACCOUNT_KIND_OPTIONS } from '@/components/balances/AddAccountDialog'
import BalanceAreaChart from '@/components/balances/BalanceAreaChart'
import { ApiError, getBalanceSeries, listAccounts } from '@/lib/api'
import { formatPaise } from '@/lib/format'
import type { Account, AccountKind, BalanceSeriesPoint } from '@/lib/types'

function kindLabel(kind: AccountKind): string {
  return ACCOUNT_KIND_OPTIONS.find((o) => o.value === kind)?.label ?? kind
}

export default function BalancesPage() {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [series, setSeries] = useState<BalanceSeriesPoint[] | null>(null)
  const [seriesLoading, setSeriesLoading] = useState(false)

  function loadAccounts(selectAfter?: string) {
    setLoading(true)
    setError(null)
    listAccounts()
      .then((res) => {
        setAccounts(res.items)
        setSelectedId((prev) => selectAfter ?? prev ?? res.items[0]?.id ?? null)
      })
      .catch((err) => {
        if (err instanceof ApiError && err.status === 401) return
        setError(err instanceof ApiError ? err.message : 'Failed to load accounts.')
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    loadAccounts()
  }, [])

  useEffect(() => {
    if (!selectedId) {
      setSeries(null)
      return
    }
    let cancelled = false
    setSeriesLoading(true)
    getBalanceSeries(selectedId)
      .then((res) => {
        if (!cancelled) setSeries(res.points)
      })
      .catch((err) => {
        if (cancelled) return
        if (err instanceof ApiError && err.status === 401) return
        setError(err instanceof ApiError ? err.message : 'Failed to load balance history.')
      })
      .finally(() => {
        if (!cancelled) setSeriesLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [selectedId])

  const selected = accounts.find((a) => a.id === selectedId) ?? null

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold tracking-tight">Balances</h1>
        <Button onClick={() => setDialogOpen(true)}>
          <PlusIcon />
          Add account
        </Button>
      </div>

      {error && <ErrorAlert message={error} className="mb-4" />}

      {loading ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-28 rounded-lg" />
          ))}
        </div>
      ) : accounts.length === 0 ? (
        <Card>
          <CardContent className="flex flex-col items-center justify-center gap-3 py-12 text-center">
            <WalletIcon className="size-8 text-muted-foreground" />
            <p className="text-sm font-medium">No accounts yet</p>
            <p className="max-w-sm text-sm text-muted-foreground">
              Add a bank, card, wallet, or cash account to track its balance over time.
            </p>
            <Button onClick={() => setDialogOpen(true)}>
              <PlusIcon />
              Add your first account
            </Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <div className="mb-6 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {accounts.map((account) => (
              <Card
                key={account.id}
                className="cursor-pointer gap-2 py-4 transition-colors hover:border-primary/50"
                onClick={() => setSelectedId(account.id)}
              >
                <CardContent className="px-4">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="truncate font-medium">{account.label}</div>
                      <div className="truncate text-xs text-muted-foreground">
                        {account.bank_name ?? 'No bank'}
                        {account.account_tail ? ` · ••${account.account_tail}` : ''}
                      </div>
                    </div>
                    <Badge variant="secondary" className="shrink-0">
                      {kindLabel(account.kind)}
                    </Badge>
                  </div>
                  <div className="mt-3 text-lg font-semibold tabular-nums">
                    {formatPaise(account.balance_paise)}
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>

          <Card>
            <CardHeader className="flex flex-row items-center justify-between gap-3 space-y-0">
              <CardTitle className="text-sm font-medium">Balance over time</CardTitle>
              <Select value={selectedId ?? undefined} onValueChange={setSelectedId}>
                <SelectTrigger className="w-56">
                  <SelectValue placeholder="Select account" />
                </SelectTrigger>
                <SelectContent>
                  {accounts.map((account) => (
                    <SelectItem key={account.id} value={account.id}>
                      {account.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </CardHeader>
            <CardContent>
              {seriesLoading && !series ? (
                <Skeleton className="h-64 w-full rounded-lg" />
              ) : series && series.length > 0 ? (
                <BalanceAreaChart points={series} />
              ) : (
                <p className="py-12 text-center text-sm text-muted-foreground">
                  No balance history yet for {selected?.label ?? 'this account'}. Balances come from
                  imported statement rows that carry a running balance.
                </p>
              )}
            </CardContent>
          </Card>
        </>
      )}

      <AddAccountDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onCreated={(account) => loadAccounts(account.id)}
      />
    </div>
  )
}
