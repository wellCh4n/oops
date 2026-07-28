"use client"

import { appIdentityBackground, type AppColorSeed } from "@/lib/app-color"
import { normalizeAppIcon } from "@/lib/app-icons"
import { cn } from "@/lib/utils"

interface AppIdentityMarkProps {
  seed: AppColorSeed
  className?: string
}

// Both variants share one footprint and one border treatment, so a list mixing
// icon-bearing and plain applications still reads as a single column of marks —
// and each mark echoes the bordered square of the picker that sets it.
const MARK_BASE = "size-5 shrink-0 rounded-[4px] border border-black/10 dark:border-white/15"

// A picked emoji stands in for the derived color block entirely — emojis carry
// their own color, and laying one over a gradient just muddies both.
export function AppIdentityMark({ seed, className }: AppIdentityMarkProps) {
  const icon = typeof seed === "string" ? undefined : normalizeAppIcon(seed.icon)

  if (icon) {
    return (
      <span
        aria-hidden
        className={cn(
          MARK_BASE,
          // 16px fills the 18px inner box, so the glyph carries the same visual
          // weight as the gradient block, which covers its square edge to edge.
          "inline-flex items-center justify-center overflow-hidden bg-muted/40 text-[16px] leading-none",
          // The emoji stands in for an image, so it must not behave like text:
          // no caret, no drag-select, and nothing picked up by a copied selection.
          "select-none",
          className
        )}
      >
        {icon}
      </span>
    )
  }

  return (
    <span
      aria-hidden
      className={cn(
        MARK_BASE,
        "inline-block shadow-[inset_0_0_0_1px_rgb(255_255_255_/_0.28)]",
        className
      )}
      style={{ background: appIdentityBackground(seed) }}
    />
  )
}
