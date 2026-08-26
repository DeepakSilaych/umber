import {
  BarChart3,
  CalendarDays,
  Landmark,
  PlusCircle,
  Receipt,
  Target,
  Upload,
  type LucideIcon,
} from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Matches NavLink's `end` prop — exact-path match instead of prefix match. */
  end?: boolean
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

// Grouped sidebar structure. Phase 2 appends an "Insights" item to Analytics and a new
// "Portfolio" group (Accounts, Investments) here — the shape is deliberately set up for that,
// even though only these 4 items exist today.
export const navGroups: NavGroup[] = [
  {
    label: 'Daily',
    items: [
      { to: '/', label: 'Transactions', icon: Receipt, end: true },
      { to: '/add', label: 'Add manual entry', icon: PlusCircle },
      { to: '/upload', label: 'Upload statement', icon: Upload },
    ],
  },
  {
    label: 'Analytics',
    items: [
      { to: '/stats', label: 'Stats', icon: BarChart3 },
      { to: '/calendar', label: 'Calendar', icon: CalendarDays },
      { to: '/balances', label: 'Balances', icon: Landmark },
      { to: '/goals', label: 'Goals', icon: Target },
    ],
  },
]

export const allNavItems: NavItem[] = navGroups.flatMap((group) => group.items)
