"use client"

import { RefreshCw, WifiOff } from "lucide-react"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

interface ConnectionLostBannerProps {
  /** Translated message shown next to the disconnect icon. */
  message: string
  /** Translated label for the retry button. */
  retryLabel: string
  /** Retry handler; defaults to a full page reload. */
  onRetry?: () => void
  /** Extra classes for the banner container (e.g. `rounded-md`). */
  className?: string
}

/**
 * Warning banner shown when a live WebSocket/SSE connection drops
 * (pipeline logs, pod logs, pod terminal, sandbox terminal).
 * Uses the `--warning` theme token rather than hardcoded amber shades.
 */
export function ConnectionLostBanner({
  message,
  retryLabel,
  onRetry,
  className,
}: ConnectionLostBannerProps) {
  return (
    <div
      role="status"
      className={cn(
        "flex shrink-0 items-center justify-between gap-3 border border-warning/40 bg-warning/10 px-3 py-2 text-foreground",
        className
      )}
    >
      <div className="flex min-w-0 items-center gap-2 text-sm">
        <WifiOff className="size-4 shrink-0 text-warning" />
        <span className="truncate">{message}</span>
      </div>
      <Button
        variant="outline"
        size="xs"
        onClick={onRetry ?? (() => window.location.reload())}
        className="shrink-0 bg-background/80 text-foreground hover:bg-background"
      >
        <RefreshCw className="size-3" />
        {retryLabel}
      </Button>
    </div>
  )
}
