"use client"

import { useEffect, useRef, useState } from "react"
import { useParams, useSearchParams } from "next/navigation"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Badge } from "@/components/ui/badge"
import { API_BASE_URL } from "@/lib/api/config"

export default function ApplicationPodLogsPage() {
  const params = useParams()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const pod = params.pod as string
  const env = searchParams.get("env")

  const [logs, setLogs] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [connectionStatus, setConnectionStatus] = useState<
    "connecting" | "connected" | "disconnected"
  >("connecting")
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!env || !namespace || !name || !pod) return

    const eventSource = new EventSource(
      `${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/pods/${pod}/log?env=${env}`
    )

    eventSource.onopen = () => {
      setConnectionStatus("connected")
    }

    eventSource.addEventListener("log", (event) => {
      const messageEvent = event as MessageEvent
      setLogs((prev) => [...prev, messageEvent.data])
    })

    eventSource.onmessage = (event) => {
      setLogs((prev) => [...prev, event.data])
    }

    eventSource.onerror = (err) => {
      console.error("SSE error:", err)
      setConnectionStatus("disconnected")
      setError((prev) => prev ?? "SSE error")
      // Only set error if connection fails initially or persistently
      // eventSource.close() // Don't close immediately on minor errors, but maybe specific ones
    }

    return () => {
      eventSource.close()
    }
  }, [namespace, name, pod, env])

  useEffect(() => {
    requestAnimationFrame(() => {
      bottomRef.current?.scrollIntoView({ block: "end" })
    })
  }, [logs])

  if (!env) {
    return <div className="p-4">Missing env parameter</div>
  }

  const isConnected = connectionStatus === "connected"

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex-1 min-h-0 rounded-md border bg-background shadow-sm overflow-hidden flex flex-col">
        <div className="flex items-center justify-between px-3 py-2 border-b">
          <div className="flex items-center gap-3 min-w-0">
            <span
              className={`h-2 w-2 rounded-full ${isConnected ? "bg-green-500" : "bg-gray-400"}`}
            />
            <div className="text-xs font-medium text-muted-foreground shrink-0">
              日志
            </div>
            <Badge className="bg-orange-500 text-white">{env}</Badge>
            <div className="text-sm font-semibold text-foreground truncate">
              {pod}
            </div>
          </div>
        </div>

        <div className="flex-1 min-h-0 bg-black p-4 overflow-hidden font-mono text-xs text-white">
          <ScrollArea className="h-full w-full">
            {logs.map((log, index) => (
              <div key={index} className="whitespace-pre-wrap break-all">
                {log}
              </div>
            ))}
            <div ref={bottomRef} />
            {logs.length === 0 && !error && (
              <div className="text-gray-500 italic">Waiting for logs...</div>
            )}
            {error && logs.length === 0 && (
              <div className="text-gray-500 italic">{error}</div>
            )}
          </ScrollArea>
        </div>
      </div>
    </div>
  )
}
