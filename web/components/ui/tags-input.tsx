"use client"

import * as React from "react"
import { X } from "lucide-react"
import { cn } from "@/lib/utils"
import { Badge } from "@/components/ui/badge"

interface TagsInputProps {
  values: string[]
  onValuesChange: (values: string[]) => void
  /** Return an error message to reject the entry, or null to accept it. */
  validate?: (value: string) => string | null
  /** Normalize an accepted value before it is stored (e.g. trim, canonicalize). */
  transform?: (value: string) => string
  /** Called with the validation error message when an entry is rejected. */
  onError?: (message: string) => void
  placeholder?: string
  inputMode?: React.ComponentProps<"input">["inputMode"]
  removeAriaLabel?: string
  className?: string
  disabled?: boolean
}

export function TagsInput({
  values,
  onValuesChange,
  validate,
  transform,
  onError,
  placeholder,
  inputMode,
  removeAriaLabel = "Remove",
  className,
  disabled = false,
}: TagsInputProps) {
  const [inputValue, setInputValue] = React.useState("")

  function commit() {
    const trimmed = inputValue.trim()
    if (!trimmed) {
      return
    }
    if (validate) {
      const error = validate(trimmed)
      if (error) {
        onError?.(error)
        return
      }
    }
    const normalized = transform ? transform(trimmed) : trimmed
    if (!values.includes(normalized)) {
      onValuesChange([...values, normalized])
    }
    setInputValue("")
  }

  function removeAt(index: number) {
    onValuesChange(values.filter((_, valueIndex) => valueIndex !== index))
  }

  return (
    <div
      className={cn(
        "flex flex-wrap items-center gap-1.5 rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-xs",
        "focus-within:border-ring focus-within:ring-ring/50 focus-within:ring-[3px]",
        disabled && "cursor-not-allowed opacity-50",
        className
      )}
    >
      {values.map((value, index) => (
        <Badge key={`${value}-${index}`} variant="secondary" className="gap-1 pr-1 font-normal">
          {value}
          <button
            type="button"
            aria-label={removeAriaLabel}
            disabled={disabled}
            className="text-muted-foreground hover:text-foreground"
            onClick={() => removeAt(index)}
          >
            <X className="size-3" />
          </button>
        </Badge>
      ))}
      <input
        value={inputValue}
        onChange={(event) => setInputValue(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter" || event.key === ",") {
            event.preventDefault()
            commit()
          } else if (event.key === "Backspace" && !inputValue && values.length > 0) {
            removeAt(values.length - 1)
          }
        }}
        onBlur={commit}
        disabled={disabled}
        autoComplete="off"
        inputMode={inputMode}
        placeholder={values.length === 0 ? placeholder : ""}
        className="flex-1 min-w-24 bg-transparent outline-none placeholder:text-muted-foreground disabled:cursor-not-allowed"
      />
    </div>
  )
}
