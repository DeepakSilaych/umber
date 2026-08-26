// Mirrors app/categories.py `ALL` exactly — same order, same strings. Do not edit without
// checking the server copy; category values round-trip verbatim through the API.
export const CATEGORIES = [
  'Food & Dining',
  'Groceries',
  'Transport',
  'Shopping',
  'Bills & Utilities',
  'Entertainment',
  'Health',
  'Education',
  'Rent & Housing',
  'Investments',
  'Transfers',
  'Cash',
  'Travel',
  'Income',
  'Other',
] as const

export type Category = (typeof CATEGORIES)[number]

export const DIRECTIONS = ['DEBIT', 'CREDIT'] as const
export type Direction = (typeof DIRECTIONS)[number]
