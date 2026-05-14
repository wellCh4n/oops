"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import Link from "next/link"
import dynamic from "next/dynamic"
import { useParams } from "next/navigation"
import { Info, RefreshCw, WifiOff } from "lucide-react"
import { toast } from "sonner"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { ContentPage } from "@/components/content-page"
import { useLanguage } from "@/contexts/language-context"

import { getSandbox, SandboxInstance, SandboxInstanceStatus } from "@/lib/api/sandbox"
import { getSandboxFileDownloadUrl, listSandboxDirectory } from "@/lib/api/sandbox-files"

const SandboxTerminalView = dynamic(() => import("@/components/sandbox-terminal-view"), {
  ssr: false,
})

const FileTree = dynamic(() => import("@/components/file-tree"), {
  ssr: false,
})

const FILE_TREE_MIN_WIDTH = 180
const FILE_TREE_MAX_WIDTH = 480
const FILE_TREE_DEFAULT_WIDTH = 260

export default function SandboxDetailPage() {
  const { t } = useLanguage()
  const params = useParams<{ id: string }>()
  const sandboxId = params.id

  const [sandbox, setSandbox] = useState<SandboxInstance | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [connectionStatus, setConnectionStatus] = useState<"connecting" | "connected" | "disconnected">("connecting")
  const [fileTreeWidth, setFileTreeWidth] = useState<number>(FILE_TREE_DEFAULT_WIDTH)
  const draggingRef = useRef(false)
  const containerRef = useRef<HTMLDivElement>(null)

  const fetchSandbox = useCallback(async () => {
    if (!sandboxId) return
    try {
      const res = await getSandbox(sandboxId)
      if (!res.data) {
        setNotFound(true)
        return
      }
      setSandbox(res.data)
    } catch {
      setNotFound(true)
      toast.error(t("sandbox.detail.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [sandboxId, t])

  useEffect(() => {
    fetchSandbox()
  }, [fetchSandbox])

  useEffect(() => {
    if (!sandbox) return
    if (sandbox.status === "RUNNING" || sandbox.status === "FAILED") return
    const timer = setInterval(fetchSandbox, 5000)
    return () => clearInterval(timer)
  }, [sandbox, fetchSandbox])

  const listDirectory = useCallback(
    (path: string) => listSandboxDirectory({ id: sandboxId, path }),
    [sandboxId],
  )
  const getDownloadUrl = useCallback(
    (path: string) => getSandboxFileDownloadUrl({ id: sandboxId, path }),
    [sandboxId],
  )

  const handleSplitterPointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault()
    draggingRef.current = true
    e.currentTarget.setPointerCapture(e.pointerId)
  }, [])

  const handleSplitterPointerMove = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current || !containerRef.current) return
    const rect = containerRef.current.getBoundingClientRect()
    const next = Math.min(FILE_TREE_MAX_WIDTH, Math.max(FILE_TREE_MIN_WIDTH, e.clientX - rect.left))
    setFileTreeWidth(next)
  }, [])

  const handleSplitterPointerUp = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return
    draggingRef.current = false
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId)
    }
    window.dispatchEvent(new Event("resize"))
  }, [])

  useEffect(() => {
    window.dispatchEvent(new Event("resize"))
  }, [fileTreeWidth])

  if (loading) {
    return (
      <ContentPage
        title={
          <span className="flex items-center gap-2">
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-3 w-44" />
          </span>
        }
        disableGutter
        className="-m-4 w-[calc(100%+2rem)] gap-0 min-h-0 overflow-hidden self-stretch"
        bodyClassName="flex flex-1 min-h-0 flex-col pt-0 pb-0 overflow-hidden"
        actions={
          <div className="flex items-center gap-3">
            <Skeleton className="h-2 w-2 rounded-full" />
            <Skeleton className="h-5 w-14 rounded-full" />
          </div>
        }
      >
        <div className="flex h-full min-h-0 flex-col">
          <Skeleton className="flex-1 min-h-0 rounded-none" />
        </div>
      </ContentPage>
    )
  }

  if (notFound || !sandbox) {
    return (
      <ContentPage title={t("sandbox.title")}>
        <div className="flex flex-col items-center gap-3 py-16">
          <span className="text-muted-foreground text-sm">{t("sandbox.detail.notFound")}</span>
          <Link href="/sandboxes" className="text-sm text-primary hover:underline">
            {t("sandbox.detail.back")}
          </Link>
        </div>
      </ContentPage>
    )
  }

  const isConnected = connectionStatus === "connected"

  return (
    <ContentPage
      documentTitle={sandbox.name}
      title={
        <span className="flex items-center gap-2">
          <span className="font-mono">{sandbox.name}</span>
          <span className="text-xs text-muted-foreground font-mono">{sandbox.id}</span>
          <Tooltip>
            <TooltipTrigger asChild>
              <button type="button" className="text-muted-foreground hover:text-foreground inline-flex items-center">
                <Info className="h-3.5 w-3.5" />
              </button>
            </TooltipTrigger>
            <TooltipContent className="text-xs font-mono space-y-0.5">
              <div>{t("sandbox.col.environment")}: {sandbox.environment}</div>
              <div>{t("sandbox.col.image")}: {sandbox.image}</div>
              <div>CPU: {sandbox.cpuRequest} / {sandbox.cpuLimit}</div>
              <div>MEM: {sandbox.memoryRequest} / {sandbox.memoryLimit}</div>
              <div>ID: {sandbox.id}</div>
            </TooltipContent>
          </Tooltip>
        </span>
      }
      disableGutter
      className="-m-4 w-[calc(100%+2rem)] gap-0 min-h-0 overflow-hidden self-stretch"
      bodyClassName="flex flex-1 min-h-0 flex-col pt-0 pb-0 overflow-hidden"
      actions={
        <div className="flex items-center gap-3">
          <span className={`h-2 w-2 rounded-full ${isConnected ? "bg-green-500" : "bg-gray-400"}`} />
          <StatusBadge status={sandbox.status} />
        </div>
      }
    >
      <div className="flex h-full min-h-0 flex-col">
        {connectionStatus === "disconnected" && (
          <div
            role="status"
            className="flex shrink-0 items-center justify-between gap-3 border border-amber-200 bg-amber-50 px-3 py-2 text-amber-900 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-100"
          >
            <div className="flex min-w-0 items-center gap-2 text-sm">
              <WifiOff className="size-4 shrink-0" />
              <span className="truncate">{t("common.disconnected")}</span>
            </div>
            <Button
              variant="outline"
              size="xs"
              onClick={() => window.location.reload()}
              className="shrink-0 bg-background/80 text-foreground hover:bg-background"
            >
              <RefreshCw className="size-3" />
              {t("common.refresh")}
            </Button>
          </div>
        )}
        {sandbox.status === "RUNNING" ? (
          <div ref={containerRef} className="flex min-h-0 flex-1 flex-row overflow-hidden">
            <div
              className="shrink-0 border-r border-sidebar-border"
              style={{ width: fileTreeWidth }}
            >
              <FileTree listDirectory={listDirectory} getDownloadUrl={getDownloadUrl} />
            </div>
            <div
              role="separator"
              aria-orientation="vertical"
              onPointerDown={handleSplitterPointerDown}
              onPointerMove={handleSplitterPointerMove}
              onPointerUp={handleSplitterPointerUp}
              onPointerCancel={handleSplitterPointerUp}
              className="w-1 shrink-0 cursor-col-resize bg-sidebar-border hover:bg-primary/40"
            />
            <div className="min-w-0 flex-1">
              <SandboxTerminalView sandboxId={sandbox.id} onConnectionStatusChange={setConnectionStatus} />
            </div>
          </div>
        ) : (
          <div className="flex flex-1 items-center justify-center text-muted-foreground text-sm">
            {t("sandbox.exec.notRunning")}
          </div>
        )}
      </div>
    </ContentPage>
  )
}

function StatusBadge({ status }: { status: SandboxInstanceStatus }) {
  const { t } = useLanguage()
  let variant: "default" | "secondary" | "destructive" | "outline" = "outline"
  if (status === "RUNNING") variant = "default"
  if (status === "PENDING" || status === "TERMINATING") variant = "secondary"
  if (status === "FAILED") variant = "destructive"
  return <Badge variant={variant}>{t(`sandbox.status.${status}`)}</Badge>
}
