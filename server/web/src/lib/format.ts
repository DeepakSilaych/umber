const inrFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
})

/** amount_paise is integer paise end to end server-side; only ever divide by 100 for display. */
export function formatPaise(paise: number): string {
  return inrFormatter.format(paise / 100)
}

/** Same as formatPaise but keeps sign explicit (useful for net figures that can be negative). */
export function formatSignedPaise(paise: number): string {
  const formatted = inrFormatter.format(Math.abs(paise) / 100)
  return paise < 0 ? `-${formatted}` : formatted
}

const dateTimeFormatter = new Intl.DateTimeFormat('en-IN', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

const dateFormatter = new Intl.DateTimeFormat('en-IN', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
})

export function formatDateTime(epochMs: number): string {
  return dateTimeFormatter.format(new Date(epochMs))
}

export function formatDate(epochMs: number): string {
  return dateFormatter.format(new Date(epochMs))
}

/** yyyy-mm-dd for <input type="date"> value attributes, in the browser's local timezone. */
export function toDateInputValue(epochMs: number): string {
  const d = new Date(epochMs)
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd}`
}

/** Parses an <input type="date"> value (yyyy-mm-dd) into epoch ms at local midnight. */
export function dateInputToEpochMs(value: string): number {
  const [y, m, d] = value.split('-').map(Number)
  return new Date(y, (m ?? 1) - 1, d ?? 1).getTime()
}

/** Same, but at the last millisecond of that local day — for inclusive "to" filters. */
export function dateInputToEndOfDayEpochMs(value: string): number {
  const [y, m, d] = value.split('-').map(Number)
  return new Date(y, (m ?? 1) - 1, d ?? 1, 23, 59, 59, 999).getTime()
}

/** Converts a rupee-denominated form input (string, may have a decimal part) to integer paise. */
export function rupeesToPaise(rupees: string): number {
  const value = Number.parseFloat(rupees)
  if (Number.isNaN(value)) return 0
  return Math.round(value * 100)
}
