"use client"

import { toast } from "sonner"
import { cn } from "@/lib/utils"

interface CopyableProps {
  value: string
  copyValue?: string
  maxLength?: number
  className?: string
  displayClassName?: string
}

export function Copyable({ value, copyValue, maxLength = 10, className, displayClassName }: CopyableProps) {
  const handleCopy = async () => {
    await navigator.clipboard.writeText(copyValue ?? value)
    toast.success("已复制")
  }

  const display =
    value.length > maxLength
      ? `${value.slice(0, 5)}...${value.slice(-5)}`
      : value

  return (
    <button
      onClick={handleCopy}
      className={cn(
        "font-mono text-sm cursor-pointer hover:underline decoration-dashed underline-offset-2",
        displayClassName,
        className
      )}
    >
      {display}
    </button>
  )
}
