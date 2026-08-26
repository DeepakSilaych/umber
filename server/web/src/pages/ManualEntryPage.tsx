import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import CategorySelect from '@/components/CategorySelect'
import ErrorAlert from '@/components/ErrorAlert'
import ToggleGroupFilter from '@/components/ToggleGroupFilter'
import { ApiError, createTransaction } from '@/lib/api'
import { CATEGORIES, DIRECTIONS, type Direction } from '@/lib/categories'
import { dateInputToEpochMs, rupeesToPaise, toDateInputValue } from '@/lib/format'

const formSchema = z.object({
  occurredAt: z.string().min(1, 'Date is required'),
  amount: z
    .string()
    .min(1, 'Amount is required')
    .refine((v) => rupeesToPaise(v) > 0, { message: 'Enter an amount greater than 0' }),
  direction: z.string().min(1),
  category: z.string().min(1),
  merchantRaw: z.string(),
})
type FormValues = z.infer<typeof formSchema>

const DIRECTION_OPTIONS = DIRECTIONS.map((d) => ({
  value: d,
  label: d === 'DEBIT' ? 'Debit (spend)' : 'Credit (income)',
}))

export default function ManualEntryPage() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    mode: 'onChange',
    defaultValues: {
      occurredAt: toDateInputValue(Date.now()),
      amount: '',
      direction: 'DEBIT',
      category: CATEGORIES[0],
      merchantRaw: '',
    },
  })

  async function onSubmit(values: FormValues) {
    setError(null)
    try {
      await createTransaction({
        occurred_at: dateInputToEpochMs(values.occurredAt),
        amount_paise: rupeesToPaise(values.amount),
        direction: values.direction as Direction,
        category: values.category,
        merchant_raw: values.merchantRaw.trim() || null,
      })
      toast.success('Transaction saved.', {
        action: { label: 'View transactions', onClick: () => navigate('/') },
      })
      form.reset({ ...values, amount: '', merchantRaw: '' })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to save transaction.')
    }
  }

  return (
    <div className="mx-auto max-w-lg">
      <h1 className="mb-1 text-xl font-semibold tracking-tight">Add manual entry</h1>
      <p className="mb-4 text-sm text-muted-foreground">For cash spending or anything with no SMS or statement trail.</p>

      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4 rounded-lg border bg-card p-5">
          <FormField
            control={form.control}
            name="occurredAt"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Date</FormLabel>
                <FormControl>
                  <Input type="date" required {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="amount"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Amount (₹)</FormLabel>
                <FormControl>
                  <Input type="number" inputMode="decimal" min="0.01" step="0.01" placeholder="0.00" {...field} />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="direction"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Direction</FormLabel>
                <FormControl>
                  <ToggleGroupFilter
                    options={DIRECTION_OPTIONS}
                    value={field.value}
                    onChange={field.onChange}
                    className="w-full"
                    itemClassName="flex-1"
                  />
                </FormControl>
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="category"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Category</FormLabel>
                <FormControl>
                  <CategorySelect value={field.value} onChange={field.onChange} className="w-full" />
                </FormControl>
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="merchantRaw"
            render={({ field }) => (
              <FormItem>
                <FormLabel>
                  Merchant <span className="font-normal text-muted-foreground">(optional)</span>
                </FormLabel>
                <FormControl>
                  <Input placeholder="e.g. Street vendor" {...field} />
                </FormControl>
              </FormItem>
            )}
          />

          {error && <ErrorAlert message={error} />}

          <Button type="submit" className="w-full" disabled={form.formState.isSubmitting || !form.formState.isValid}>
            {form.formState.isSubmitting ? 'Saving…' : 'Save transaction'}
          </Button>
        </form>
      </Form>
    </div>
  )
}
