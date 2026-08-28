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
import DateRangeFilter from '@/components/DateRangeFilter'
import ErrorAlert from '@/components/ErrorAlert'
import { ApiError, tagContext } from '@/lib/api'
import { CATEGORIES } from '@/lib/categories'
import { dateInputToEndOfDayEpochMs, dateInputToEpochMs } from '@/lib/format'
import type { TagContextResponse } from '@/lib/types'

const formSchema = z
  .object({
    name: z.string().min(1, 'Name is required'),
    // yyyy-mm-dd strings (same contract as DateRangeFilter); both required to tag a range.
    from: z.string().min(1),
    to: z.string().min(1),
    categories: z.array(z.string()),
  })
  .refine((v) => v.from && v.to, {
    message: 'Pick a start and end date',
    path: ['from'],
  })
type FormValues = z.infer<typeof formSchema>

interface AddTripDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Called with the tag result after a successful tag, so the parent can refresh. */
  onCreated: (result: TagContextResponse) => void
}

/**
 * Creates a trip by tagging every (untagged) transaction in a date range — optionally scoped to a
 * few top-level categories — with a free-text context name. Mirrors POST /v1/transactions/tag-context.
 */
export default function AddTripDialog({ open, onOpenChange, onCreated }: AddTripDialogProps) {
  const [error, setError] = useState<string | null>(null)

  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    mode: 'onChange',
    defaultValues: { name: '', from: '', to: '', categories: [] },
  })

  useEffect(() => {
    if (!open) return
    setError(null)
    form.reset({ name: '', from: '', to: '', categories: [] })
  }, [open, form])

  async function onSubmit(values: FormValues) {
    setError(null)
    const context = values.name.trim()
    try {
      const result = await tagContext({
        context,
        from_ms: dateInputToEpochMs(values.from),
        to_ms: dateInputToEndOfDayEpochMs(values.to),
        categories: values.categories.length ? values.categories : undefined,
      })
      toast.success(
        `Tagged ${result.updated} transaction${result.updated === 1 ? '' : 's'} as ${result.context}`,
      )
      onCreated(result)
      onOpenChange(false)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to tag trip.')
    }
  }

  const fromValue = form.watch('from')
  const toValue = form.watch('to')

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Add trip</DialogTitle>
          <DialogDescription>
            Group a trip or occasion by tagging every untagged transaction in a date range with a name.
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
                    <Input placeholder="e.g. mumbai_trip" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="from"
              render={() => (
                <FormItem className="flex flex-col">
                  <FormLabel>Date range</FormLabel>
                  <DateRangeFilter
                    from={fromValue}
                    to={toValue}
                    onChange={(f, t) => {
                      form.setValue('from', f, { shouldValidate: true, shouldDirty: true })
                      form.setValue('to', t, { shouldValidate: true, shouldDirty: true })
                    }}
                    className="w-full"
                  />
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="categories"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>
                    Categories{' '}
                    <span className="font-normal text-muted-foreground">
                      (scope to these; leave empty for all)
                    </span>
                  </FormLabel>
                  <div className="grid grid-cols-2 gap-2 rounded-lg border p-3 sm:grid-cols-3">
                    {CATEGORIES.map((category) => {
                      const checked = field.value.includes(category)
                      const id = `trip-cat-${category}`
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
              <Button type="submit" disabled={form.formState.isSubmitting || !form.formState.isValid}>
                {form.formState.isSubmitting ? 'Tagging…' : 'Add trip'}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
