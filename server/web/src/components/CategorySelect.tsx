import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { CATEGORIES } from '@/lib/categories'

interface CategorySelectProps {
  value: string
  onChange: (category: string) => void
  className?: string
  disabled?: boolean
  id?: string
  includeAllOption?: boolean
}

// Radix Select doesn't allow an empty-string item value, so "all categories" is represented by
// this sentinel internally and translated back to '' at the edges — callers still see the same
// '' | <category> value contract as before.
const ALL_VALUE = '__all__'

/**
 * Dropdown constrained to the 15 canonical categories (app/categories.py `ALL`). Never free text —
 * the server rejects anything else.
 */
export default function CategorySelect({
  value,
  onChange,
  className,
  disabled,
  id,
  includeAllOption,
}: CategorySelectProps) {
  return (
    <Select
      value={value === '' ? ALL_VALUE : value}
      onValueChange={(next) => onChange(next === ALL_VALUE ? '' : next)}
      disabled={disabled}
    >
      <SelectTrigger id={id} className={className}>
        <SelectValue placeholder="Select category" />
      </SelectTrigger>
      <SelectContent>
        {includeAllOption && <SelectItem value={ALL_VALUE}>All categories</SelectItem>}
        {CATEGORIES.map((category) => (
          <SelectItem key={category} value={category}>
            {category}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
