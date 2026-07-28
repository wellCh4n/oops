"use client"

import * as React from "react"
import { Check, Loader2 } from "lucide-react"
import { cn } from "@/lib/utils"
import { Input } from "@/components/ui/input"
import { getApplicationBranches } from "@/lib/api/applications"
import { useLanguage } from "@/contexts/language-context"

interface BranchPickerProps {
  id?: string
  value: string
  onValueChange: (value: string) => void
  namespace: string
  applicationName: string
  env?: string
  placeholder?: string
  disabled?: boolean
  className?: string
}

/**
 * A text input that suggests the remote branches of the application repository. The typed value is
 * always what gets submitted, so tags and commit SHAs stay usable and a repository we cannot reach
 * simply degrades to a plain input.
 */
export function BranchPicker({
  id,
  value,
  onValueChange,
  namespace,
  applicationName,
  env,
  placeholder,
  disabled = false,
  className,
}: BranchPickerProps) {
  const { t } = useLanguage()
  const [open, setOpen] = React.useState(false)
  const [branches, setBranches] = React.useState<string[]>([])
  const [loading, setLoading] = React.useState(false)
  const [failed, setFailed] = React.useState(false)
  const [highlight, setHighlight] = React.useState(-1)
  const containerRef = React.useRef<HTMLDivElement>(null)
  const requestIdRef = React.useRef(0)

  // Branches are environment-scoped (the git credential lives on the environment), so anything
  // fetched for a previous environment is dropped.
  React.useEffect(() => {
    requestIdRef.current += 1
    setBranches([])
    setFailed(false)
    setOpen(false)
  }, [env, namespace, applicationName])

  React.useEffect(() => {
    if (!open) return
    const handlePointerDown = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener("mousedown", handlePointerDown)
    return () => document.removeEventListener("mousedown", handlePointerDown)
  }, [open])

  const loadBranches = React.useCallback(async () => {
    if (!env) return
    const requestId = ++requestIdRef.current
    setLoading(true)
    setFailed(false)
    try {
      const response = await getApplicationBranches(namespace, applicationName, env)
      if (requestIdRef.current !== requestId) return
      setBranches(response.data ?? [])
    } catch {
      if (requestIdRef.current !== requestId) return
      setBranches([])
      setFailed(true)
    } finally {
      if (requestIdRef.current === requestId) {
        setLoading(false)
      }
    }
  }, [namespace, applicationName, env])

  const openList = () => {
    if (disabled) return
    setOpen(true)
    setHighlight(-1)
    // The backend caches for a minute, so refetching on every open is what keeps the list fresh.
    loadBranches()
  }

  const query = value.trim().toLowerCase()
  const matches = branches.some((branch) => branch.toLowerCase() === query)
    ? branches
    : branches.filter((branch) => branch.toLowerCase().includes(query))

  const commit = (branch: string) => {
    onValueChange(branch)
    setOpen(false)
    setHighlight(-1)
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Escape") {
      setOpen(false)
      return
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      if (!open) {
        openList()
        return
      }
      if (matches.length === 0) return
      event.preventDefault()
      const step = event.key === "ArrowDown" ? 1 : -1
      setHighlight((current) => (current + step + matches.length) % matches.length)
      return
    }
    if (event.key === "Enter" && open && highlight >= 0 && highlight < matches.length) {
      event.preventDefault()
      commit(matches[highlight])
    }
  }

  return (
    <div ref={containerRef} className={cn("relative", className)}>
      <Input
        id={id}
        value={value}
        autoComplete="off"
        disabled={disabled}
        placeholder={placeholder}
        onChange={(e) => {
          onValueChange(e.target.value)
          setHighlight(-1)
          if (!open) openList()
        }}
        onFocus={openList}
        onKeyDown={handleKeyDown}
      />
      {open && (
        <div className="absolute top-full left-0 z-50 mt-1 w-full overflow-hidden rounded-md border bg-popover text-popover-foreground shadow-md">
          {loading && (
            <div className="flex items-center gap-2 px-3 py-2 text-sm text-muted-foreground">
              <Loader2 className="size-3 animate-spin" />
              {t("apps.publish.branchLoading")}
            </div>
          )}
          {!loading && failed && (
            <div className="px-3 py-2 text-sm text-muted-foreground">
              {t("apps.publish.branchLoadError")}
            </div>
          )}
          {!loading && !failed && matches.length === 0 && (
            <div className="px-3 py-2 text-sm text-muted-foreground">
              {t("apps.publish.branchEmpty")}
            </div>
          )}
          {!loading && matches.length > 0 && (
            <div className="max-h-60 overflow-y-auto py-1">
              {matches.map((branch, index) => (
                <button
                  key={branch}
                  type="button"
                  onMouseDown={(e) => e.preventDefault()}
                  onMouseEnter={() => setHighlight(index)}
                  onClick={() => commit(branch)}
                  className={cn(
                    "flex w-full cursor-pointer items-center gap-2 px-3 py-1.5 text-left text-sm",
                    index === highlight && "bg-accent text-accent-foreground"
                  )}
                >
                  <Check className={cn("size-4 shrink-0", branch === value ? "opacity-100" : "opacity-0")} />
                  <span className="truncate">{branch}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
