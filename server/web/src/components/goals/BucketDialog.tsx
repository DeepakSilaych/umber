import { useEffect, useState } from 'react'
import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
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
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import ErrorAlert from '@/components/ErrorAlert'
import { ApiError, createBudgetBucket, patchBudgetBucket } from '@/lib/api'
import { CATEGORIES } from '@/lib/categories'
import { rupeesToPaise } from '@/lib/format'
import type { BudgetBucket, BucketKind } from '@/lib/types'

const KIND_OPTIONS: { value: BucketKind; label: string }[] = [
  { value: 'spend', label: 'Spend (track against a limit)' },
  { value: 'savings', label: 'Savings (leftover after spending)' },
]

const formSchema = z.object({
  name: z.string().min(1, 'Name is required'),
  target: z
    .string()
    .min(1, 'Target is required')
    .refine((v) => rupeesToPaise(v) >= 0, { message: 'Enter a valid amount' }),
  kind: z.enum(['spend', 'savings']),
  categoryKeys: z.array(z.string()),
  sortOrder: z.string(),
})
type FormValues = z.infer<typeof formSchema>

interface BucketDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** null = create mode; a bucket = edit mode. */
  bucket: BudgetBucket | null
  /** Default sort_order for a newly created bucket (usually the current bucket count). */
  nextSortOrder: number
  onSaved: () => void
}

export default function BucketDialog({
  open,
  onOpenChange,
  bucket,
  nextSortOrder,
  onSaved,
}: BucketDialogProps) {
  const [error, setError] = useState<string | null>(null)
  const isEdit = bucket !== null

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    mode: 'onChange',
    defaultValues: {
      name: '',
      target: '',
      kind: 'spend',
      categoryKeys: [],
      sortOrder: String(nextSortOrder),
    },
  })

  // Reseed the form whenever the dialog opens or the target bucket changes.
  useEffect(() => {
    if (!open) return
    setError(null)
    form.reset({
      name: bucket?.name ?? '',
      target: bucket ? String(bucket.monthly_target_paise / 100) : '',
      kind: bucket?.kind ?? 'spend',
      categoryKeys: bucket?.category_keys ?? [],
      sortOrder: String(bucket?.sort_order ?? nextSortOrder),
    })
  }, [open, bucket, nextSortOrder, form])

  async function onSubmit(values: FormValues) {
    setError(null)
    const payload = {
      name: values.name.trim(),
      monthly_target_paise: rupeesToPaise(values.target),
      category_keys: values.categoryKeys,
      kind: values.kind,
      sort_order: Number.parseInt(values.sortOrder, 10) || 0,
    }
    try {
      if (isEdit && bucket) {
        await patchBudgetBucket(bucket.id, payload)
        toast.success('Bucket updated.')
      } else {
        await createBudgetBucket(payload)
        toast.success('Bucket added.')
      }
      onSaved()
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to save bucket.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit bucket' : 'Add bucket'}</DialogTitle>
          <DialogDescription>
            A bucket rolls up one or more categories and tracks them against a monthly target.
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField
              control={form.control}
              name="name"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Name</FormLabel>
                  <FormControl>
                    <Input placeholder="e.g. Essentials" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="grid grid-cols-2 gap-3">
              <FormField
                control={form.control}
                name="target"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Monthly target (₹)</FormLabel>
                    <FormControl>
                      <Input type="number" inputMode="decimal" step="0.01" placeholder="0.00" {...field} />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name="sortOrder"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Order</FormLabel>
                    <FormControl>
                      <Input type="number" step="1" {...field} />
                    </FormControl>
                  </FormItem>
                )}
              />
            </div>

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
                        {KIND_OPTIONS.map((option) => (
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
              name="categoryKeys"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    Categories{' '}
                    <span className="font-normal text-muted-foreground">
                      (rolled into this bucket)
                    </span>
                  </FormLabel>
                  <div className="grid grid-cols-2 gap-2 rounded-lg border p-3 sm:grid-cols-3">
                    {CATEGORIES.map((category) => {
                      const checked = field.value.includes(category)
                      const id = `cat-${category}`
                      return (
                        <div key={category} className="flex items-center gap-2">
                          <Checkbox
                            id={id}
                            checked={checked}
                            onCheckedChange={(next) => {
                              field.onChange(
                                next
                                  ? [...field.value, category]
                                  : field.value.filter((c) => c !== category),
                              )
                            }}
                          />
                          <Label htmlFor={id} className="text-xs font-normal">
                            {category}
                          </Label>
                        </div>
                      )
                    })}
                  </div>
                </FormItem>
              )}
            />

            {error && <ErrorAlert message={error} />}

            <DialogFooter>
              <Button
                type="submit"
                disabled={form.formState.isSubmitting || !form.formState.isValid}
              >
                {form.formState.isSubmitting ? 'Saving…' : isEdit ? 'Save changes' : 'Add bucket'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
