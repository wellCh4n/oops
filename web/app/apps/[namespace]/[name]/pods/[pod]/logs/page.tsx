"use client"

import { Suspense, useEffect, useRef, useState } from "react"
import { useParams, useSearchParams } from "next/navigation"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Badge } from "@/components/ui/badge"
import { ConnectionLostBanner } from "@/components/connection-lost-banner"
import { API_BASE_URL } from "@/lib/api/config"
import { getToken } from "@/lib/auth"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"

interface LogLine {
  id: number
  text: string
}

function ApplicationPodLogsContent() {
  const params = useParams()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const pod = params.pod as string
  const env = searchParams.get("environment")

  const [logs, setLogs] = useState<LogLine[]>([])
  const [error, setError] = useState<string | null>(null)
  const [connectionStatus, setConnectionStatus] = useState<
    "connecting" | "connected" | "disconnected"
  >("connecting")
  const bottomRef = useRef<HTMLDivElement>(null)
  const logIdRef = useRef(0)
  const { t } = useLanguage()

  useEffect(() => {
    if (!env || !namespace || !name || !pod) return

    // Use WebSocket instead of SSE
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const baseUrl = API_BASE_URL.startsWith('http')
      ? API_BASE_URL.replace(/^http/, 'ws')
      : `${wsProtocol}//${window.location.host}${API_BASE_URL}`

    const wsUrl = `${baseUrl}/api/namespaces/${namespace}/applications/${name}/pods/${pod}/log?environment=${env}&token=${getToken()}`
    let ws: WebSocket | null = null

    let heartbeatInterval: ReturnType<typeof setInterval> | null = null

    // Use a small timeout to prevent double connection in React Strict Mode
    const connectTimeout = setTimeout(() => {
      ws = new WebSocket(wsUrl)

      ws.onopen = () => {
        setConnectionStatus("connected")
        setError(null)
        heartbeatInterval = setInterval(() => {
          if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send("ping")
          }
        }, 10000)
      }

      ws.onmessage = (event: MessageEvent<string>) => {
        if (event.data === "pong") return
        const text = event.data
        const id = ++logIdRef.current
        setLogs((prev) => [...prev, { id, text }])
      }

      ws.onerror = (err) => {
        console.error("WebSocket error:", err)
        setConnectionStatus("disconnected")
        setError("WebSocket connection error")
      }

      ws.onclose = () => {
        setConnectionStatus("disconnected")
        if (heartbeatInterval) clearInterval(heartbeatInterval)
      }
    }, 100)

    return () => {
      clearTimeout(connectTimeout)
      if (heartbeatInterval) clearInterval(heartbeatInterval)
      if (ws) {
        ws.close()
      }
    }
  }, [namespace, name, pod, env])

  useEffect(() => {
    requestAnimationFrame(() => {
      bottomRef.current?.scrollIntoView({ block: "end" })
    })
  }, [logs])

  if (!env) {
    return <div className="p-4">{t("pods.missingEnv")}</div>
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
            className={`size-2 rounded-full ${isConnected ? "bg-success" : "bg-muted-foreground"}`}
          />
          <Badge className="bg-orange-500 text-white">{env}</Badge>
        </div>
      }
    >
      <div className="flex h-full min-h-0 flex-col">
        {connectionStatus === "disconnected" && (
          <ConnectionLostBanner
            message={t("common.disconnected")}
            retryLabel={t("common.refresh")}
          />
        )}

        {/* The padding lives inside the scroll viewport so it scrolls away with the content;
            outside it, it would be a fixed blank band above and below the lines. */}
        <div className="flex-1 min-h-0 bg-background text-foreground overflow-hidden font-mono text-xs">
          <ScrollArea className="h-full w-full">
            <div className="p-4">
              {logs.map((log) => (
                <div key={log.id} className="whitespace-pre-wrap break-all">
                  {log.text}
                </div>
              ))}
              {/* Blinks only while the stream is connected: a cursor promises another line. */}
              {isConnected && (
                <div aria-hidden>
                  <span className="inline-block h-[1.1em] w-2 translate-y-[0.15em] bg-foreground/70 animate-caret-blink motion-reduce:animate-none" />
                </div>
              )}
              {error && logs.length === 0 && (
                <div className="text-destructive italic">{error}</div>
              )}
            </div>
            {/* Last thing in the viewport, after the padding, so following the log lands on the
                true bottom rather than one padding short of it. */}
            <div ref={bottomRef} />
          </ScrollArea>
        </div>
      </div>
    </ContentPage>
  )
}

export default function ApplicationPodLogsPage() {
  return (
    <Suspense fallback={null}>
      <ApplicationPodLogsContent />
    </Suspense>
  )
}
