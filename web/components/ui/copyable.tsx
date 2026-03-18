"use client"

import { toast } from "sonner"
import { cn } from "@/lib/utils"

interface CopyableProps {
  value: string
  className?: string
  displayClassName?: string
}

export function Copyable({ value, className, displayClassName }: CopyableProps) {
  const handleCopy = async () => {
    await navigator.clipboard.writeText(value)
    toast.success("已复制")
  }

  return (
    <button
      onClick={handleCopy}
      className={cn(
        "font-mono text-sm cursor-pointer hover:underline decoration-dashed underline-offset-2",
        displayClassName,
        className
      )}
      title="点击复制"
    >
      {value}
    </button>
  )
}
