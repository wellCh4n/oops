"use client"

import * as React from "react"
import { X } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { APP_ICON_CATEGORIES } from "@/lib/app-icons"
import { useLanguage } from "@/contexts/language-context"

interface AppIconPickerProps {
  value?: string | null
  onValueChange: (icon: string | undefined) => void
  // Rendered in the trigger when nothing is picked — usually the derived color block.
  fallback?: React.ReactNode
  disabled?: boolean
  className?: string
}

export function AppIconPicker({
  value,
  onValueChange,
  fallback,
  disabled = false,
  className,
}: AppIconPickerProps) {
  const [open, setOpen] = React.useState(false)
  const { t } = useLanguage()

  const select = (icon: string | undefined) => {
    onValueChange(icon)
    setOpen(false)
  }

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger render={<Button
          type="button"
          variant="outline"
          role="combobox"
          aria-expanded={open}
          aria-label={t("apps.icon.pick")}
          disabled={disabled}
          className={cn(
            // size-9 matches Input's h-9 so the trigger lines up with the field beside it.
            "size-9 shrink-0 justify-center p-0 bg-transparent hover:bg-accent/50 cursor-pointer disabled:cursor-not-allowed",
            className
          )} />}>
        {value ? (
          <span className="select-none text-xl leading-none">{value}</span>
        ) : (
          fallback ?? <span className="text-xs text-muted-foreground">{t("apps.icon.none")}</span>
        )}
      </PopoverTrigger>
      {/* gap-0 overrides PopoverContent's default flex gap, which would otherwise
          push a gutter between the toolbar and the icon list. */}
      <PopoverContent className="w-[340px] gap-0 p-0" align="start">
        <div className="flex items-center justify-between border-b px-3 py-2">
          <span className="text-sm font-medium">{t("apps.icon.pick")}</span>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => select(undefined)}
            disabled={!value}
            className="h-7 gap-1 px-2 text-xs text-muted-foreground cursor-pointer disabled:cursor-not-allowed"
          >
            <X className="size-3" />
            {t("apps.icon.clear")}
          </Button>
        </div>
        {/* Categories scroll in one list rather than sitting behind tabs: the whole
            set is ~86 icons, so scanning beats clicking through six panes.
            No horizontal padding here — the sticky headers must span the full width,
            or icons scroll up through the gutters beside them. */}
        <div className="max-h-[300px] overflow-y-auto pb-2">
          {APP_ICON_CATEGORIES.map((category) => (
            <div key={category.labelKey}>
              {/* Fully opaque, not a translucent blur: this header is scrolled under,
                  so anything showing through reads as a rendering glitch. */}
              <div className="sticky top-0 z-10 bg-popover px-3 py-2 text-xs font-medium text-muted-foreground">
                {t(category.labelKey)}
              </div>
              <div className="grid grid-cols-8 gap-0.5 px-2 pb-1">
                {category.emojis.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    onClick={() => select(emoji)}
                    aria-label={emoji}
                    aria-pressed={value === emoji}
                    className={cn(
                      "flex size-9 select-none items-center justify-center rounded-md text-xl leading-none transition-colors cursor-pointer hover:bg-accent",
                      // ring-inset keeps the selection outline inside the button box —
                      // an outward ring gets clipped by the scroll container and by the
                      // sticky header the button passes beneath.
                      value === emoji && "bg-accent ring-1 ring-inset ring-primary"
                    )}
                  >
                    {emoji}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  )
}
