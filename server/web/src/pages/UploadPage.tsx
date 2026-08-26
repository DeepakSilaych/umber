import { type ChangeEvent, type DragEvent, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { TriangleAlertIcon, UploadCloudIcon } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import ErrorAlert from '@/components/ErrorAlert'
import { ApiError, importStatement } from '@/lib/api'
import { cn } from '@/lib/utils'
import type { StatementImportResponse } from '@/lib/types'

const ACCEPTED_EXTENSIONS = ['.xlsx', '.csv', '.tsv']

function isAccepted(file: File): boolean {
  const name = file.name.toLowerCase()
  return ACCEPTED_EXTENSIONS.some((ext) => name.endsWith(ext))
}

export default function UploadPage() {
  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<StatementImportResponse | null>(null)
  const [fileName, setFileName] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  async function handleFile(file: File) {
    setError(null)
    setResult(null)
    if (!isAccepted(file)) {
      setError(`"${file.name}" is not a .xlsx, .csv, or .tsv file.`)
      return
    }
    setFileName(file.name)
    setUploading(true)
    try {
      const res = await importStatement(file)
      setResult(res)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Upload failed.')
    } finally {
      setUploading(false)
    }
  }

  function handleDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault()
    setDragging(false)
    const file = e.dataTransfer.files?.[0]
    if (file) handleFile(file)
  }

  function handleInputChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) handleFile(file)
    e.target.value = '' // allow re-selecting the same file later
  }

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-4 text-xl font-semibold tracking-tight">Upload statement</h1>
      <p className="mb-4 text-sm text-muted-foreground">
        Import a bank statement (.xlsx, .csv, or .tsv). Rows that match an existing merchant get their known category
        automatically; anything new is flagged for review.
      </p>

      <Card
        onDragOver={(e: DragEvent<HTMLDivElement>) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        onClick={() => inputRef.current?.click()}
        className={cn(
          'cursor-pointer border-2 border-dashed py-12 text-center transition-colors',
          dragging ? 'border-primary bg-accent' : 'hover:border-primary/50',
        )}
      >
        <CardContent className="flex flex-col items-center justify-center gap-3">
          <input ref={inputRef} type="file" accept=".xlsx,.csv,.tsv" onChange={handleInputChange} className="hidden" />
          <UploadCloudIcon className="size-8 text-muted-foreground" />
          <p className="text-sm font-medium">{uploading ? 'Uploading…' : 'Drop a statement here, or click to choose a file'}</p>
          <p className="text-xs text-muted-foreground">.xlsx · .csv · .tsv</p>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={(e) => {
              e.stopPropagation()
              inputRef.current?.click()
            }}
          >
            Choose file
          </Button>
        </CardContent>
      </Card>

      {error && <ErrorAlert message={error} className="mt-4" />}

      {result && (
        <Card className="mt-4">
          <CardContent>
            <h2 className="mb-3 text-sm font-medium">Import result{fileName ? ` — ${fileName}` : ''}</h2>

            {result.problem ? (
              <Alert className="border-amber-300 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300">
                <TriangleAlertIcon />
                <AlertDescription className="text-amber-800 dark:text-amber-300">{result.problem}</AlertDescription>
              </Alert>
            ) : (
              <>
                <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                  <Stat label="Rows in file" value={result.total_rows} />
                  <Stat label="Inserted" value={result.inserted} highlight />
                  <Stat label="Duplicates skipped" value={result.skipped_duplicate} />
                  <Stat label="Needs review" value={result.needs_review} warn />
                </dl>
                <Button asChild className="mt-4">
                  <Link to="/">View transactions</Link>
                </Button>
              </>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}

function Stat({ label, value, highlight, warn }: { label: string; value: number; highlight?: boolean; warn?: boolean }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd
        className={cn(
          'text-lg font-semibold',
          warn ? 'text-amber-600 dark:text-amber-400' : highlight ? 'text-umber-600 dark:text-umber-300' : 'text-foreground',
        )}
      >
        {value}
      </dd>
    </div>
  )
}
