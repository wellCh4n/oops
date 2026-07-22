"use client"

import * as React from "react"
import { ChevronDown, Search, X } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
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
  description?: string
}

interface MultiSelectWithSearchProps {
  values: string[]
  onValuesChange: (values: string[]) => void
  options: Option[]
  placeholder?: string
  searchPlaceholder?: string
  emptyText?: string
  className?: string
  disabled?: boolean
}

export function MultiSelectWithSearch({
  values,
  onValuesChange,
  options,
  placeholder = "Select...",
  searchPlaceholder = "Search...",
  emptyText = "No results found.",
  className,
  disabled = false,
}: MultiSelectWithSearchProps) {
  const [open, setOpen] = React.useState(false)
  const listboxId = React.useId()

  const selectedOptions = values
    .map((value) => options.find((option) => option.value === value))
    .filter((option): option is Option => Boolean(option))

  function toggle(value: string) {
    if (values.includes(value)) {
      onValuesChange(values.filter((v) => v !== value))
    } else {
      onValuesChange([...values, value])
    }
  }

  function remove(value: string) {
    onValuesChange(values.filter((v) => v !== value))
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-controls={listboxId}
          disabled={disabled}
          className={cn(
            "w-full justify-between bg-transparent border-input hover:bg-transparent hover:border-input font-normal min-h-9 h-auto py-1.5",
            className
          )}
        >
          <div className="flex flex-wrap gap-1 flex-1 min-w-0">
            {selectedOptions.length === 0 ? (
              <span className="text-muted-foreground">{placeholder}</span>
            ) : (
              selectedOptions.map((option) => (
                <Badge
                  key={option.value}
                  variant="secondary"
                  className="gap-1 font-normal"
                >
                  <span className="truncate max-w-[160px]">{option.label}</span>
                  <span
                    role="button"
                    tabIndex={-1}
                    aria-label={`Remove ${option.label}`}
                    onPointerDown={(event) => {
                      event.preventDefault()
                      event.stopPropagation()
                      remove(option.value)
                    }}
                    className="rounded-sm hover:bg-muted-foreground/20 p-0.5 cursor-pointer"
                  >
                    <X className="size-3" />
                  </span>
                </Badge>
              ))
            )}
          </div>
          <ChevronDown className="ml-2 size-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[--radix-popover-trigger-width] p-0" align="start">
        <Command>
          <div className="flex items-center border-b px-3">
            <Search className="mr-2 size-4 shrink-0 opacity-50" />
            <CommandInput
              placeholder={searchPlaceholder}
              className="flex h-9 w-full rounded-md bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50 border-0 focus:ring-0"
            />
          </div>
          <CommandList id={listboxId}>
            {options.length === 0 ? (
              <CommandEmpty>{emptyText}</CommandEmpty>
            ) : (
              <CommandGroup>
                {options.map((option) => {
                  const checked = values.includes(option.value)
                  return (
                    <CommandItem
                      key={option.value}
                      value={`${option.label} ${option.value}`}
                      onSelect={() => toggle(option.value)}
                      className="flex items-center gap-2 cursor-pointer"
                    >
                      <span
                        className={cn(
                          "flex size-4 shrink-0 items-center justify-center rounded border",
                          checked
                            ? "bg-primary border-primary text-primary-foreground"
                            : "border-input"
                        )}
                      >
                        {checked && <span className="size-2 rounded-sm bg-current" />}
                      </span>
                      <span className="flex-1 truncate">{option.label}</span>
                      {option.description && (
                        <span className="text-xs text-muted-foreground shrink-0">{option.description}</span>
                      )}
                    </CommandItem>
                  )
                })}
              </CommandGroup>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}
