"use client"

import { useCallback, useEffect, useState } from "react"
import {
  ChevronRight,
  File as FileIcon,
  FileSymlink,
  Folder,
  Loader2,
  RefreshCw,
} from "lucide-react"
import { PodFileEntry } from "@/lib/api/pod-files"
import { cn } from "@/lib/utils"
import { useLanguage } from "@/contexts/language-context"
import { toast } from "sonner"
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuTrigger,
} from "@/components/ui/context-menu"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"

export interface FileTreeProps {
  listDirectory: (path: string) => Promise<PodFileEntry[]>
  getDownloadUrl: (path: string) => string
  rootPath?: string
}

interface NodeState {
  entries: PodFileEntry[] | null
  loading: boolean
  error: string | null
  expanded: boolean
}

export default function FileTree({
  listDirectory,
  getDownloadUrl,
  rootPath = "/",
}: FileTreeProps) {
  const { t } = useLanguage()
  const [nodes, setNodes] = useState<Record<string, NodeState>>({})

  const loadDirectory = useCallback(
    async (path: string) => {
      setNodes((prev) => ({
        ...prev,
        [path]: {
          ...(prev[path] ?? { entries: null, expanded: true }),
          loading: true,
          error: null,
          expanded: true,
        },
      }))
      try {
        const entries = await listDirectory(path)
        setNodes((prev) => ({
          ...prev,
          [path]: { entries, loading: false, error: null, expanded: true },
        }))
      } catch (err) {
        setNodes((prev) => ({
          ...prev,
          [path]: {
            entries: prev[path]?.entries ?? null,
            loading: false,
            error: err instanceof Error ? err.message : "Failed to load",
            expanded: true,
          },
        }))
      }
    },
    [listDirectory],
  )

  useEffect(() => {
    loadDirectory(rootPath)
  }, [loadDirectory, rootPath])

  const toggle = useCallback(
    (entry: PodFileEntry) => {
      if (entry.type !== "DIRECTORY") return
      const existing = nodes[entry.path]
      if (existing?.expanded) {
        setNodes((prev) => ({ ...prev, [entry.path]: { ...existing, expanded: false } }))
      } else if (existing?.entries) {
        setNodes((prev) => ({ ...prev, [entry.path]: { ...existing, expanded: true } }))
      } else {
        loadDirectory(entry.path)
      }
    },
    [loadDirectory, nodes],
  )

  const rootState = nodes[rootPath]

  return (
    <div className="flex h-full min-h-0 flex-col bg-background">
      <div className="flex h-9 shrink-0 items-center justify-between gap-2 border-b border-sidebar-border px-3">
        <span className="truncate text-xs font-medium text-foreground/80">
          {t("terminal.files.title")}
        </span>
        <button
          type="button"
          onClick={() => loadDirectory(rootPath)}
          className="inline-flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          title={t("common.refresh")}
        >
          {rootState?.loading ? (
            <Loader2 className="size-3.5 animate-spin" />
          ) : (
            <RefreshCw className="size-3.5" />
          )}
        </button>
      </div>
      <div className="min-h-0 flex-1 overflow-auto py-1 font-mono text-xs">
        {rootState?.error && !rootState.entries ? (
          <div className="px-3 py-2 text-destructive">{rootState.error}</div>
        ) : !rootState?.entries && rootState?.loading ? (
          <div className="flex items-center gap-2 px-3 py-2 text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" />
            <span>{t("common.loading")}</span>
          </div>
        ) : (
          <TreeLevel
            entries={rootState?.entries ?? []}
            depth={0}
            nodes={nodes}
            onToggle={toggle}
            getDownloadUrl={getDownloadUrl}
          />
        )}
      </div>
    </div>
  )
}

interface TreeLevelProps {
  entries: PodFileEntry[]
  depth: number
  nodes: Record<string, NodeState>
  onToggle: (entry: PodFileEntry) => void
  getDownloadUrl: (path: string) => string
}

function TreeLevel({ entries, depth, nodes, onToggle, getDownloadUrl }: TreeLevelProps) {
  if (entries.length === 0) {
    return (
      <div className="px-3 py-1 italic text-muted-foreground" style={{ paddingLeft: 12 + depth * 12 }}>
        (empty)
      </div>
    )
  }
  return (
    <ul className="select-none">
      {entries.map((entry) => (
        <TreeRow
          key={entry.path}
          entry={entry}
          depth={depth}
          nodes={nodes}
          onToggle={onToggle}
          getDownloadUrl={getDownloadUrl}
        />
      ))}
    </ul>
  )
}

interface TreeRowProps {
  entry: PodFileEntry
  depth: number
  nodes: Record<string, NodeState>
  onToggle: (entry: PodFileEntry) => void
  getDownloadUrl: (path: string) => string
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ["KB", "MB", "GB", "TB"]
  let value = bytes / 1024
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unitIndex]}`
}

function TreeRow({ entry, depth, nodes, onToggle, getDownloadUrl }: TreeRowProps) {
  const { t } = useLanguage()
  const isDir = entry.type === "DIRECTORY"
  const state = nodes[entry.path]
  const expanded = isDir && state?.expanded
  const indent = 8 + depth * 12
  const showSize = entry.type === "FILE" && entry.size != null

  const handleCopy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      toast.success(t("terminal.files.copied"))
    } catch {
      toast.error("Copy failed")
    }
  }

  const handleDownload = () => {
    try {
      const url = getDownloadUrl(entry.path)
      const a = Object.assign(document.createElement("a"), { href: url, download: entry.name })
      document.body.appendChild(a)
      a.click()
      a.remove()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Download failed")
    }
  }

  return (
    <li>
      <ContextMenu>
        <ContextMenuTrigger asChild>
          <div className="group/ctx">
            <TooltipProvider delayDuration={300}>
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    onClick={() => onToggle(entry)}
                    className={cn(
                      "group flex w-full items-center gap-1.5 px-2 py-0.5 text-left transition-colors hover:bg-muted group-data-[state=open]/ctx:bg-muted",
                      isDir ? "cursor-pointer" : "cursor-default text-muted-foreground",
                    )}
                    style={{ paddingLeft: indent }}
                  >
                    <span className="flex w-3.5 shrink-0 items-center justify-center">
                      {isDir ? (
                        state?.loading ? (
                          <Loader2 className="size-3 animate-spin text-muted-foreground" />
                        ) : (
                          <ChevronRight
                            className={cn(
                              "size-3 text-muted-foreground transition-transform",
                              expanded && "rotate-90",
                            )}
                          />
                        )
                      ) : null}
                    </span>
                    <span className="flex w-4 shrink-0 items-center justify-center">
                      {isDir ? (
                        <Folder className="size-3.5 text-sky-500" />
                      ) : entry.type === "SYMLINK" ? (
                        <FileSymlink className="size-3.5 text-violet-500" />
                      ) : (
                        <FileIcon className="size-3.5 text-muted-foreground" />
                      )}
                    </span>
                    <span className="min-w-0 flex-1 truncate">{entry.name}</span>
                    {showSize ? (
                      <span className="shrink-0 pl-2 tabular-nums text-[11px] text-muted-foreground/70">
                        {formatBytes(entry.size!)}
                      </span>
                    ) : null}
                  </button>
                </TooltipTrigger>
                <TooltipContent
                  side="right"
                  align="center"
                  avoidCollisions={false}
                  className="max-w-md break-all whitespace-normal"
                >
                  <div className="font-medium">{entry.name}</div>
                  <div className="text-[11px] opacity-70">{entry.path}</div>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>
        </ContextMenuTrigger>
        <ContextMenuContent>
          <ContextMenuItem onClick={() => handleCopy(entry.path)} className="cursor-pointer">
            {t("terminal.files.copyPath")}
          </ContextMenuItem>
          <ContextMenuItem onClick={() => handleCopy(entry.name)} className="cursor-pointer">
            {t("terminal.files.copyName")}
          </ContextMenuItem>
          {!isDir && (
            <ContextMenuItem onClick={handleDownload} className="cursor-pointer">
              {t("terminal.files.download")}
            </ContextMenuItem>
          )}
        </ContextMenuContent>
      </ContextMenu>
      {isDir && expanded ? (
        state?.error ? (
          <div className="px-3 py-1 text-destructive" style={{ paddingLeft: indent + 16 }}>
            {state.error}
          </div>
        ) : state?.entries ? (
          <TreeLevel
            entries={state.entries}
            depth={depth + 1}
            nodes={nodes}
            onToggle={onToggle}
            getDownloadUrl={getDownloadUrl}
          />
        ) : null
      ) : null}
    </li>
  )
}
