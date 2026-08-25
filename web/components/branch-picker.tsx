"use client"

import * as React from "react"
import { Check, Loader2 } from "lucide-react"
import { cn } from "@/lib/utils"
import { Input } from "@/components/ui/input"
import { getApplicationBranches, type GitBranch } from "@/lib/api/applications"
import { useLanguage } from "@/contexts/language-context"

interface BranchPickerProps {
  id?: string
  value: string
  onValueChange: (value: string) => void
  /** Called with the branch that was picked, or with the matching one once the list is loaded. */
  onBranchResolved?: (branch: GitBranch | null) => void
  /** Reports whether the branch list is currently being fetched. */
  onLoadingChange?: (loading: boolean) => void
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
  onBranchResolved,
  onLoadingChange,
  namespace,
  applicationName,
  env,
  placeholder,
  disabled = false,
  className,
}: BranchPickerProps) {
  const { t } = useLanguage()
  const [open, setOpen] = React.useState(false)
  const [branches, setBranches] = React.useState<GitBranch[]>([])
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

  // Load once per environment so the commit next to the input shows without opening the list.
  React.useEffect(() => {
    if (!env || disabled) return
    loadBranches()
  }, [env, disabled, loadBranches])

  React.useEffect(() => {
    onLoadingChange?.(loading)
  }, [loading, onLoadingChange])

  const openList = () => {
    if (disabled) return
    setOpen(true)
    setHighlight(-1)
    // The backend caches for a minute, so refetching on every open is what keeps the list fresh.
    loadBranches()
  }

  const query = value.trim().toLowerCase()
  const exact = branches.find((branch) => branch.name.toLowerCase() === query) ?? null
  const matches = exact
    ? branches
    : branches.filter((branch) => branch.name.toLowerCase().includes(query))

  // Whatever is typed, tell the parent which known branch it currently names so it can show
  // that branch's tip commit next to the input.
  const resolvedRef = React.useRef<GitBranch | null | undefined>(undefined)
  React.useEffect(() => {
    if (!onBranchResolved) return
    if (resolvedRef.current === exact) return
    resolvedRef.current = exact
    onBranchResolved(exact)
  }, [exact, onBranchResolved])

  const commit = (branch: GitBranch) => {
    onValueChange(branch.name)
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
                  key={branch.name}
                  type="button"
                  onMouseDown={(e) => e.preventDefault()}
                  onMouseEnter={() => setHighlight(index)}
                  onClick={() => commit(branch)}
                  className={cn(
                    "flex w-full cursor-pointer items-start gap-2 px-3 py-1.5 text-left text-sm",
                    index === highlight && "bg-accent text-accent-foreground"
                  )}
                >
                  <Check className={cn("mt-0.5 size-4 shrink-0", branch.name === value ? "opacity-100" : "opacity-0")} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{branch.name}</span>
                    <BranchCommitLine branch={branch} className="block truncate text-xs text-muted-foreground" />
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * One-line summary of a branch tip: short SHA, then the subject, author and relative age when the
 * backend managed to read the commit itself.
 */
export function BranchCommitLine({ branch, className }: { branch: GitBranch; className?: string }) {
  const { t } = useLanguage()
  const parts: string[] = [branch.commitId.slice(0, 7)]
  if (branch.commitMessage) parts.push(branch.commitMessage)
  const meta: string[] = []
  if (branch.commitAuthor) meta.push(branch.commitAuthor)
  if (branch.committedAt) meta.push(formatRelativeTime(branch.committedAt, t))
  return (
    <span className={className} title={[...parts, ...meta].join(" · ")}>
      {parts.join(" ")}
      {meta.length > 0 && <span className="opacity-70"> · {meta.join(" · ")}</span>}
    </span>
  )
}

function formatRelativeTime(iso: string, t: (key: string) => string): string {
  const diffMs = Date.now() - new Date(iso).getTime()
  const minutes = Math.max(0, Math.floor(diffMs / 60000))
  const ago = (key: string, count: number) => t(key).replace("{count}", String(count))
  if (minutes < 60) return ago("apps.publish.commitMinutesAgo", minutes)
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return ago("apps.publish.commitHoursAgo", hours)
  const days = Math.floor(hours / 24)
  if (days < 30) return ago("apps.publish.commitDaysAgo", days)
  return new Date(iso).toLocaleDateString()
}
