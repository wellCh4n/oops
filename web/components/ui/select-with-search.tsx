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
          disabled={disabled}
          className={cn(
            "w-[200px] justify-between bg-transparent border-input hover:bg-transparent hover:border-input font-normal",
            className
          )}
        >
          <span
            className={cn("truncate", !value && "text-muted-foreground")}
          >
            {selectedOption?.label || value || placeholder}
          </span>
          <ChevronDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[200px] p-0" align="start">
        <Command filter={onSearch ? () => 1 : undefined}>
          <div className="flex items-center border-b px-3">
            <Search className="mr-2 h-4 w-4 shrink-0 opacity-50" />
            <CommandInput
              placeholder={searchPlaceholder}
              value={query}
              onValueChange={setQuery}
              className="flex h-9 w-full rounded-md bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50 border-0 focus:ring-0"
            />
          </div>
          <CommandList>
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
                        "h-4 w-4 shrink-0",
                        value === option.value
                          ? "opacity-100"
                          : "opacity-0"
                      )}
                    />
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
