import { AlertCircleIcon } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'

/**
 * Shared replacement for the red-bordered error box that used to be hand-rolled (verbatim) on
 * Login, Transactions, Upload, ManualEntry, and Stats.
 */
export default function ErrorAlert({ message, className }: { message: string; className?: string }) {
  return (
    <Alert variant="destructive" className={className}>
      <AlertCircleIcon />
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  )
}
