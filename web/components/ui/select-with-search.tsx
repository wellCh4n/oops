"use client"

import * as React from "react"
import { Check, ChevronDown, Search } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"

interface Option {
  value: string
  label: string
  namespace?: string
  name?: string
  colorBackground?: string
  color?: string
  accentColor?: string
  dotColor?: string
}

interface SelectWithSearchProps {
  value?: string
  onValueChange?: (value: string) => void
  options: Option[]
  placeholder?: string
  searchPlaceholder?: string
  emptyText?: string
  className?: string
  disabled?: boolean
  onSearch?: (query: string) => Promise<Option[]>
  onOptionSelect?: (option: Option) => void
}

export function SelectWithSearch({
  value,
  onValueChange,
  options: initialOptions,
  placeholder = "Select...",
  searchPlaceholder = "Search...",
  emptyText = "No results found.",
  className,
  disabled = false,
  onSearch,
  onOptionSelect,
}: SelectWithSearchProps) {
  const [open, setOpen] = React.useState(false)
  const [query, setQuery] = React.useState("")
  const [options, setOptions] = React.useState<Option[]>(initialOptions)
  const searchIdRef = React.useRef(0)
  const listboxId = React.useId()

  const selectedOption =
    options.find((o) => o.value === value) ??
    initialOptions.find((o) => o.value === value)

  React.useEffect(() => {
    setOptions(initialOptions)
  }, [initialOptions])

  const doSearch = React.useCallback(
    async (q: string) => {
      if (!onSearch) return
      const currentId = ++searchIdRef.current
      try {
        const results = await onSearch(q)
        if (searchIdRef.current === currentId) {
          setOptions(results)
        }
      } catch {
        if (searchIdRef.current === currentId) {
          setOptions([])
        }
      }
    },
    [onSearch]
  )


  React.useEffect(() => {
    if (!onSearch || !open) return
    const timer = setTimeout(() => doSearch(query), 300)
    return () => clearTimeout(timer)
  }, [query, onSearch, open, doSearch])

  return (
    <Popover
      open={open}
      onOpenChange={(o) => {
        setOpen(o)
        if (!o) setQuery("")
      }}
    >
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-controls={listboxId}
          disabled={disabled}
          className={cn(
            "w-[200px] justify-between bg-transparent border-input hover:bg-transparent hover:border-input font-normal",
            className
          )}
        >
          <span
            className={cn("truncate flex items-center gap-2 min-w-0", !value && "text-muted-foreground")}
          >
            <OptionColorMark option={selectedOption} />
            <span className="truncate">{selectedOption?.label || value || placeholder}</span>
          </span>
          <ChevronDown className="ml-2 size-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        className="w-auto min-w-[var(--radix-popover-trigger-width)] max-w-[min(480px,calc(var(--radix-popover-content-available-width)-8px))] p-0"
        align="start"
      >
        <Command filter={onSearch ? () => 1 : undefined}>
          <div className="flex items-center border-b px-3">
            <Search className="mr-2 size-4 shrink-0 opacity-50" />
            <CommandInput
              placeholder={searchPlaceholder}
              value={query}
              onValueChange={setQuery}
              className="flex h-9 w-full rounded-md bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50 border-0 focus:ring-0"
            />
          </div>
          <CommandList id={listboxId}>
            {options.length === 0 ? (
              <CommandEmpty>{emptyText}</CommandEmpty>
            ) : (
              <CommandGroup>
                {options.map((option) => (
                  <CommandItem
                    key={option.value}
                    value={option.value}
                    onSelect={() => {
                      onValueChange?.(option.value)
                      onOptionSelect?.(option)
                      setOpen(false)
                      setQuery("")
                    }}
                    className="flex items-center gap-2 cursor-pointer"
                  >
                    <Check
                      className={cn(
                        "size-4 shrink-0",
                        value === option.value
                          ? "opacity-100"
                          : "opacity-0"
                      )}
                    />
                    <OptionColorMark option={option} />
                    <span className="flex-1 truncate">{option.label}</span>
                  </CommandItem>
                ))}
              </CommandGroup>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}

function OptionColorMark({ option }: { option?: Option }) {
  const background = getOptionColorBackground(option)

  if (!background) {
    return null
  }

  return (
    <span
      aria-hidden
      className="inline-block size-4 shrink-0 rounded-[4px] border border-black/10 shadow-[inset_0_0_0_1px_rgb(255_255_255_/_0.28)] dark:border-white/15"
      style={{ background }}
    />
  )
}

function getOptionColorBackground(option?: Option): string | undefined {
  if (!option) {
    return undefined
  }

  if (option.colorBackground) {
    return option.colorBackground
  }

  const primaryColor = option.color ?? option.dotColor
  if (!primaryColor) {
    return undefined
  }

  if (!option.accentColor) {
    return primaryColor
  }

  return `linear-gradient(135deg, ${primaryColor} 0 64%, ${option.accentColor} 64% 100%)`
}
