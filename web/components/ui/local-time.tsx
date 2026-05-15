"use client"

import { useEffect, useState } from "react"

interface LocalTimeProps {
  value: string | number | Date | null | undefined
  fallback?: string
  className?: string
}

function format(value: LocalTimeProps["value"], fallback: string): string {
  if (value === null || value === undefined || value === "") return fallback
  const date = new Date(value)
  if (isNaN(date.getTime())) return fallback
  return date.toLocaleString()
}

// Renders a timestamp formatted in the user's locale, deferring until after
// hydration so SSR output (UTC default) doesn't mismatch the client (local TZ).
export function LocalTime({ value, fallback = "-", className }: LocalTimeProps) {
  const [mounted, setMounted] = useState(false)
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true)
  }, [])

  return (
    <span className={className} suppressHydrationWarning>
      {mounted ? format(value, fallback) : fallback}
    </span>
  )
}
