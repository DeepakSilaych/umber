import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'

interface ToggleGroupFilterOption {
  value: string
  label: string
}

interface ToggleGroupFilterProps {
  options: ToggleGroupFilterOption[]
  value: string
  onChange: (value: string) => void
  className?: string
  /** Applied to each item — e.g. `flex-1` for the manual-entry direction picker, which splits its
   * two options evenly, unlike the auto-width needs-review/period toggles. */
  itemClassName?: string
}

/**
 * Shared replacement for the three duplicated "buttons in a bordered box" single-select toggles
 * (needs-review filter, manual-entry direction picker, stats period picker). `type="single"` with
 * a guard on empty `onValueChange` calls keeps exactly one option always selected, matching the
 * original hand-rolled behavior (Radix fires `""` when you click the already-active button).
 */
export default function ToggleGroupFilter({
  options,
  value,
  onChange,
  className,
  itemClassName,
}: ToggleGroupFilterProps) {
  return (
    <ToggleGroup
      type="single"
      variant="outline"
      value={value}
      onValueChange={(next) => {
        if (next) onChange(next)
      }}
      className={className}
    >
      {options.map((option) => (
        <ToggleGroupItem
          key={option.value}
          value={option.value}
          className={`data-[state=on]:bg-primary data-[state=on]:text-primary-foreground data-[state=on]:hover:bg-primary/90 ${itemClassName ?? ''}`}
        >
          {option.label}
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  )
}
