"use client"

import { useEffect, useState } from "react"
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

  useEffect(() => {
    if (!env || !namespace || !name || !pod) return

    const eventSource = new EventSource(
      `${API_BASE_URL}/api/namespaces/${namespace}/applications/${name}/pods/${pod}/log?env=${env}`
    )

    eventSource.addEventListener("log", (event) => {
      const messageEvent = event as MessageEvent
      setLogs((prev) => [...prev, messageEvent.data])
    })

    eventSource.onmessage = (event) => {
      setLogs((prev) => [...prev, event.data])
    }

    eventSource.onerror = (err) => {
      console.error("SSE error:", err)
      // Only set error if connection fails initially or persistently
      // eventSource.close() // Don't close immediately on minor errors, but maybe specific ones
    }

    return () => {
      eventSource.close()
    }
  }, [namespace, name, pod, env])

  if (!env) {
    return <div className="p-4">Missing env parameter</div>
  }

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center gap-4 pb-4 border-b text-sm">
        <Badge variant="outline">Pod: {pod}</Badge>
        <Badge variant="outline">Environment: {env}</Badge>
      </div>
      
      <div className="flex-1 bg-black text-white p-4 font-mono text-xs overflow-hidden">
        <ScrollArea className="h-full w-full">
          {logs.map((log, index) => (
            <div key={index} className="whitespace-pre-wrap break-all">
              {log}
            </div>
          ))}
          {logs.length === 0 && !error && (
            <div className="text-gray-500 italic">Waiting for logs...</div>
          )}
        </ScrollArea>
      </div>
    </div>
  )
}
