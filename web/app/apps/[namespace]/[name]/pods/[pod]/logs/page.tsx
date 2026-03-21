"use client"

import { useEffect, useRef, useState } from "react"
import { useParams, useSearchParams } from "next/navigation"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Badge } from "@/components/ui/badge"
import { API_BASE_URL } from "@/lib/api/config"
import { getToken } from "@/lib/auth"

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

    // Use WebSocket instead of SSE
    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const baseUrl = API_BASE_URL.startsWith('http')
      ? API_BASE_URL.replace(/^http/, 'ws')
      : `${wsProtocol}//${window.location.host}${API_BASE_URL}`

    const wsUrl = `${baseUrl}/api/namespaces/${namespace}/applications/${name}/pods/${pod}/log?env=${env}&token=${getToken()}`
    let ws: WebSocket | null = null

    // Use a small timeout to prevent double connection in React Strict Mode
    const connectTimeout = setTimeout(() => {
      ws = new WebSocket(wsUrl)
      
      ws.onopen = () => {
        setConnectionStatus("connected")
        setError(null)
      }

      ws.onmessage = (event) => {
        const logLine = event.data
        setLogs((prev) => [...prev, logLine])
      }

      ws.onerror = (err) => {
        console.error("WebSocket error:", err)
        setConnectionStatus("disconnected")
        setError("WebSocket connection error")
      }

      ws.onclose = () => {
        setConnectionStatus("disconnected")
      }
    }, 100)

    return () => {
      clearTimeout(connectTimeout)
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
