"use client"

import { ChevronLeft, ChevronRight } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { useLanguage } from "@/contexts/language-context"
import { cn } from "@/lib/utils"

const DEFAULT_PAGE_SIZE_OPTIONS = [10, 20, 50]

/** Pages always rendered around the current one, on each side */
const SIBLING_COUNT = 1

type PageItem = number | "ellipsis-start" | "ellipsis-end"

/**
 * Builds the page button list: first page, last page, a window around the current
 * page, and ellipsis for the gaps — e.g. `1 … 4 5 6 … 20`.
 */
function buildPageItems(page: number, totalPages: number): PageItem[] {
  // First + last + current + 2 siblings + 2 ellipsis
  const maxItems = SIBLING_COUNT * 2 + 5
  if (totalPages <= maxItems) {
    return Array.from({ length: totalPages }, (_, index) => index + 1)
  }

  const windowStart = Math.max(2, page - SIBLING_COUNT)
  const windowEnd = Math.min(totalPages - 1, page + SIBLING_COUNT)
  const items: PageItem[] = [1]

  if (windowStart > 2) {
    items.push("ellipsis-start")
  }
  for (let current = windowStart; current <= windowEnd; current++) {
    items.push(current)
  }
  if (windowEnd < totalPages - 1) {
    items.push("ellipsis-end")
  }
  items.push(totalPages)

  return items
}

interface PaginationProps {
  /** Current page, 1-based */
  page: number
  totalPages: number
  onPageChange: (page: number) => void
  /** Total record count. Omit to hide the total label */
  total?: number
  /** Page size. Omit together with onSizeChange to hide the page size selector */
  size?: number
  onSizeChange?: (size: number) => void
  sizeOptions?: number[]
  loading?: boolean
  className?: string
}

export function Pagination({
  page,
  totalPages,
  onPageChange,
  total,
  size,
  onSizeChange,
  sizeOptions = DEFAULT_PAGE_SIZE_OPTIONS,
  loading,
  className,
}: PaginationProps) {
  const { t } = useLanguage()
  const pageItems = buildPageItems(page, totalPages)

  return (
    <div className={cn("flex items-center justify-end gap-4 mt-2 flex-wrap", className)}>
      {total !== undefined && (
        <span className="text-sm text-muted-foreground">
          {t("common.totalItems").replace("${total}", String(total))}
        </span>
      )}
      {size !== undefined && onSizeChange && (
        <div className="flex items-center gap-2">
          <span className="text-sm text-muted-foreground">{t("common.pageSize")}</span>
          <Select
            value={String(size)}
            onValueChange={(value) => onSizeChange(Number(value))}
            disabled={loading}
          >
            <SelectTrigger className="w-[70px] h-8 cursor-pointer disabled:cursor-not-allowed">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {sizeOptions.map((option) => (
                <SelectItem key={option} value={String(option)}>
                  {option}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <span className="text-sm text-muted-foreground">{t("common.pageSizeSuffix")}</span>
        </div>
      )}
      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="sm"
          className="h-8"
          disabled={page <= 1 || loading}
          onClick={() => onPageChange(page - 1)}
        >
          <ChevronLeft className="size-4" />
          {t("common.prevPage")}
        </Button>
        {pageItems.map((item) =>
          typeof item === "number" ? (
            <Button
              key={item}
              variant={item === page ? "default" : "outline"}
              size="sm"
              // Minimum width rather than fixed: a three-digit page number needs room.
              className="h-8 min-w-8 px-2 tabular-nums"
              aria-current={item === page ? "page" : undefined}
              disabled={loading}
              onClick={() => onPageChange(item)}
            >
              {item}
            </Button>
          ) : (
            <span key={item} className="w-8 text-center text-sm text-muted-foreground select-none">
              …
            </span>
          )
        )}
        <Button
          variant="outline"
          size="sm"
          className="h-8"
          disabled={page >= totalPages || loading}
          onClick={() => onPageChange(page + 1)}
        >
          {t("common.nextPage")}
          <ChevronRight className="size-4" />
        </Button>
      </div>
    </div>
  )
}
