"use client"

import { Suspense, useCallback, useEffect, useRef, useState } from "react"
import dynamic from "next/dynamic"
import { useParams, useSearchParams } from "next/navigation"
import { ContentPage } from "@/components/content-page"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { RefreshCw, WifiOff } from "lucide-react"
import { useLanguage } from "@/contexts/language-context"
import { createPodDirectory, deletePodPath, getPodFileContent, getPodFileDownloadUrl, listPodDirectory, renamePodPath, savePodFileContent, uploadPodFile } from "@/lib/api/pod-files"

const TerminalView = dynamic(() => import("@/components/terminal-view"), {
  ssr: false,
  loading: () => <div className="p-4 text-white">Loading terminal…</div>
})

const FileTree = dynamic(() => import("@/components/file-tree"), {
  ssr: false,
})

const FILE_TREE_MIN_WIDTH = 180
const FILE_TREE_MAX_WIDTH = 480
const FILE_TREE_DEFAULT_WIDTH = 260

export default function TerminalPage() {
  return (
    <Suspense fallback={null}>
      <TerminalPageContent />
    </Suspense>
  )
}

function TerminalPageContent() {
  const params = useParams()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const pod = params.pod as string
  const env = searchParams.get("env")
  const [connectionStatus, setConnectionStatus] = useState<
    "connecting" | "connected" | "disconnected"
  >("connecting")
  const [fileTreeWidth, setFileTreeWidth] = useState<number>(FILE_TREE_DEFAULT_WIDTH)
  const draggingRef = useRef(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const { t } = useLanguage()

  const listDirectory = useCallback(
    (path: string) => listPodDirectory({ namespace, name, pod, env: env!, path }),
    [namespace, name, pod, env],
  )

  const getDownloadUrl = useCallback(
    (path: string) => getPodFileDownloadUrl({ namespace, name, pod, env: env!, path }),
    [namespace, name, pod, env],
  )

  const uploadFile = useCallback(
    (parentDir: string, file: File) => {
      const dirPath = parentDir.endsWith("/") ? parentDir : `${parentDir}/`
      return uploadPodFile({ namespace, name, pod, env: env!, path: dirPath, file })
    },
    [namespace, name, pod, env],
  )

  const getFileContent = useCallback(
    async (path: string) => {
      const result = await getPodFileContent({ namespace, name, pod, env: env!, path })
      return result.content
    },
    [namespace, name, pod, env],
  )

  const saveFileContent = useCallback(
    (path: string, content: string) =>
      savePodFileContent({ namespace, name, pod, env: env!, path, content }),
    [namespace, name, pod, env],
  )

  const deletePath = useCallback(
    (path: string) => deletePodPath({ namespace, name, pod, env: env!, path }),
    [namespace, name, pod, env],
  )

  const renamePath = useCallback(
    (fromPath: string, toPath: string) =>
      renamePodPath({ namespace, name, pod, env: env!, fromPath, toPath }),
    [namespace, name, pod, env],
  )

  const createDirectory = useCallback(
    (path: string) => createPodDirectory({ namespace, name, pod, env: env!, path }),
    [namespace, name, pod, env],
  )

  const handleSplitterPointerDown = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault()
    draggingRef.current = true
    const target = e.currentTarget
    target.setPointerCapture(e.pointerId)
  }, [])

  const handleSplitterPointerMove = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current || !containerRef.current) return
    const rect = containerRef.current.getBoundingClientRect()
    const next = Math.min(
      FILE_TREE_MAX_WIDTH,
      Math.max(FILE_TREE_MIN_WIDTH, e.clientX - rect.left),
    )
    setFileTreeWidth(next)
  }, [])

  const handleSplitterPointerUp = useCallback((e: React.PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return
    draggingRef.current = false
    const target = e.currentTarget
    if (target.hasPointerCapture(e.pointerId)) {
      target.releasePointerCapture(e.pointerId)
    }
    window.dispatchEvent(new Event("resize"))
  }, [])

  useEffect(() => {
    window.dispatchEvent(new Event("resize"))
  }, [fileTreeWidth])

  if (!env) {
    return <div className="p-4">Missing env parameter</div>
  }

  const isConnected = connectionStatus === "connected"

  return (
    <ContentPage
      title={pod}
      disableGutter
      className="-m-4 w-[calc(100%+2rem)] gap-0 min-h-0 overflow-hidden self-stretch"
      bodyClassName="flex flex-1 min-h-0 flex-col pt-0 pb-0 overflow-hidden"
      actions={
        <div className="flex items-center gap-3">
          <span
            className={`size-2 rounded-full ${isConnected ? "bg-green-500" : "bg-gray-400"}`}
          />
          <Badge className="bg-orange-500 text-white">{env}</Badge>
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
        <div
          ref={containerRef}
          className="flex min-h-0 flex-1 flex-row overflow-hidden"
        >
          <div
            className="shrink-0 border-r border-sidebar-border"
            style={{ width: fileTreeWidth }}
          >
            <FileTree
              listDirectory={listDirectory}
              getDownloadUrl={getDownloadUrl}
              uploadFile={uploadFile}
              getFileContent={getFileContent}
              saveFileContent={saveFileContent}
              deletePath={deletePath}
              renamePath={renamePath}
              createDirectory={createDirectory}
            />
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
            <TerminalView
              namespace={namespace}
              name={name}
              pod={pod}
              env={env}
              onConnectionStatusChange={setConnectionStatus}
            />
          </div>
        </div>
      </div>
    </ContentPage>
  )
}
