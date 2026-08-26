import { useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Form, FormControl, FormField, FormItem, FormLabel, FormMessage } from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import ErrorAlert from '@/components/ErrorAlert'
import { ApiError, createAccount } from '@/lib/api'
import { rupeesToPaise } from '@/lib/format'
import type { Account, AccountKind } from '@/lib/types'

export const ACCOUNT_KIND_OPTIONS: { value: AccountKind; label: string }[] = [
  { value: 'BANK', label: 'Bank' },
  { value: 'CREDIT_CARD', label: 'Credit card' },
  { value: 'WALLET', label: 'Wallet' },
  { value: 'CASH', label: 'Cash' },
  { value: 'OTHER', label: 'Other' },
]

const formSchema = z.object({
  label: z.string().min(1, 'Label is required'),
  bankName: z.string(),
  kind: z.enum(['BANK', 'CREDIT_CARD', 'WALLET', 'CASH', 'OTHER']),
  accountTail: z.string(),
  openingBalance: z.string(),
})
type FormValues = z.infer<typeof formSchema>

interface AddAccountDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (account: Account) => void
}

export default function AddAccountDialog({ open, onOpenChange, onCreated }: AddAccountDialogProps) {
  const [error, setError] = useState<string | null>(null)

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    mode: 'onChange',
    defaultValues: {
      label: '',
      bankName: '',
      kind: 'BANK',
      accountTail: '',
      openingBalance: '',
    },
  })

  async function onSubmit(values: FormValues) {
    setError(null)
    try {
      const account = await createAccount({
        label: values.label.trim(),
        bank_name: values.bankName.trim() || null,
        kind: values.kind,
        account_tail: values.accountTail.trim() || null,
        opening_balance_paise: values.openingBalance ? rupeesToPaise(values.openingBalance) : 0,
      })
      toast.success('Account added.')
      onCreated(account)
      form.reset()
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to add account.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Add account</DialogTitle>
          <DialogDescription>Track a bank, card, wallet, or cash balance.</DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField
              control={form.control}
              name="label"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Label</FormLabel>
                  <FormControl>
                    <Input placeholder="e.g. HDFC Savings" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="bankName"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    Bank <span className="font-normal text-muted-foreground">(optional)</span>
                  </FormLabel>
                  <FormControl>
                    <Input placeholder="e.g. HDFC Bank" {...field} />
                  </FormControl>
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="kind"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Kind</FormLabel>
                  <FormControl>
                    <Select value={field.value} onValueChange={field.onChange}>
                      <SelectTrigger className="w-full">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {ACCOUNT_KIND_OPTIONS.map((option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </FormControl>
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="accountTail"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    Account tail{' '}
                    <span className="font-normal text-muted-foreground">(last 4, optional)</span>
                  </FormLabel>
                  <FormControl>
                    <Input placeholder="e.g. 1234" {...field} />
                  </FormControl>
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="openingBalance"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    Opening balance (₹){' '}
                    <span className="font-normal text-muted-foreground">(optional)</span>
                  </FormLabel>
                  <FormControl>
                    <Input type="number" inputMode="decimal" step="0.01" placeholder="0.00" {...field} />
                  </FormControl>
                </FormItem>
              )}
            />

            {error && <ErrorAlert message={error} />}

            <DialogFooter>
              <Button
                type="submit"
                disabled={form.formState.isSubmitting || !form.formState.isValid}
              >
                {form.formState.isSubmitting ? 'Adding…' : 'Add account'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
