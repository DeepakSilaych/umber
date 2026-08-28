import { useMemo } from 'react'
import { flexRender, getCoreRowModel, useReactTable, type ColumnDef } from '@tanstack/react-table'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import CategorySelect from '@/components/CategorySelect'
import SubcategoryCell from '@/components/transactions/SubcategoryCell'
import ContextCell from '@/components/transactions/ContextCell'
import { DirectionBadge, NeedsReviewBadge } from '@/components/Badges'
import { formatDateTime, formatPaise } from '@/lib/format'
import type { TxnOut } from '@/lib/types'

interface TransactionsTableProps {
  items: TxnOut[]
  loading: boolean
  /** Existing sub-category values offered as inline datalist suggestions. */
  subcategorySuggestions: string[]
  /** Existing context (trip) names offered as inline datalist suggestions. */
  contextSuggestions: string[]
  onCategoryChange: (txn: TxnOut, category: string) => void
  onSubcategoryChange: (txn: TxnOut, subcategory: string) => void
  onContextChange: (txn: TxnOut, context: string) => void
}

/**
 * Table primitives + @tanstack/react-table for column-defs/cell-renderers only — pagination stays
 * server-driven (the page component owns offset/limit and the Previous/Next controls), so this is
 * mounted with `manualPagination: true` and never paginates client-side.
 */
export default function TransactionsTable({
  items,
  loading,
  subcategorySuggestions,
  contextSuggestions,
  onCategoryChange,
  onSubcategoryChange,
  onContextChange,
}: TransactionsTableProps) {
  const columns = useMemo<ColumnDef<TxnOut>[]>(
    () => [
      {
        id: 'date',
        header: 'Date',
        cell: ({ row }) => (
          <span className="whitespace-nowrap text-muted-foreground">{formatDateTime(row.original.occurred_at)}</span>
        ),
      },
      {
        id: 'merchant',
        header: 'Merchant',
        cell: ({ row }) =>
          row.original.merchant_raw ?? row.original.merchant_norm ?? <span className="text-muted-foreground">—</span>,
      },
      {
        id: 'amount',
        header: () => <div className="text-right">Amount</div>,
        cell: ({ row }) => (
          <div className="text-right font-medium tabular-nums">{formatPaise(row.original.amount_paise)}</div>
        ),
      },
      {
        id: 'direction',
        header: 'Direction',
        cell: ({ row }) => <DirectionBadge direction={row.original.direction} />,
      },
      {
        id: 'category',
        header: 'Category',
        cell: ({ row }) => (
          <CategorySelect
            value={row.original.category}
            onChange={(category) => onCategoryChange(row.original, category)}
            className="h-8 w-auto"
          />
        ),
      },
      {
        id: 'subcategory',
        header: 'Sub-category',
        cell: ({ row }) => (
          <SubcategoryCell
            txn={row.original}
            suggestions={subcategorySuggestions}
            onCommit={onSubcategoryChange}
          />
        ),
      },
      {
        id: 'context',
        header: 'Context',
        cell: ({ row }) => (
          <ContextCell txn={row.original} suggestions={contextSuggestions} onCommit={onContextChange} />
        ),
      },
      {
        id: 'status',
        header: 'Status',
        cell: ({ row }) => <NeedsReviewBadge needsReview={row.original.needs_review} />,
      },
    ],
    [subcategorySuggestions, contextSuggestions, onCategoryChange, onSubcategoryChange, onContextChange],
  )

  const table = useReactTable({
    data: items,
    columns,
    getCoreRowModel: getCoreRowModel(),
    manualPagination: true,
    getRowId: (row) => row.client_id,
  })

  return (
    <div className="overflow-hidden rounded-lg border">
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id}>
              {headerGroup.headers.map((header) => (
                <TableHead key={header.id}>
                  {header.isPlaceholder ? null : flexRender(header.column.columnDef.header, header.getContext())}
                </TableHead>
              ))}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {table.getRowModel().rows.length ? (
            table.getRowModel().rows.map((row) => (
              <TableRow key={row.id}>
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</TableCell>
                ))}
              </TableRow>
            ))
          ) : (
            <TableRow>
              <TableCell colSpan={columns.length} className="h-24 text-center text-muted-foreground">
                {loading ? 'Loading…' : 'No transactions match these filters.'}
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  )
}
