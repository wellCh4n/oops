"use client"

import { appIdentityBackground, type AppColorSeed } from "@/lib/app-color"
import { cn } from "@/lib/utils"

interface AppIdentityMarkProps {
  seed: AppColorSeed
  className?: string
}

export function AppIdentityMark({ seed, className }: AppIdentityMarkProps) {
  return (
    <span
      aria-hidden
      className={cn(
        "inline-block size-4 shrink-0 rounded-[4px] border border-black/10 shadow-[inset_0_0_0_1px_rgb(255_255_255_/_0.28)] dark:border-white/15",
        className
      )}
      style={{ background: appIdentityBackground(seed) }}
    />
  )
}
