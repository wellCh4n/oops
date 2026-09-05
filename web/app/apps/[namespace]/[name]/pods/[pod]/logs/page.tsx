"use client"

import { Suspense, useEffect, useRef, useState } from "react"
import { useParams, useSearchParams } from "next/navigation"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Badge } from "@/components/ui/badge"
import { ConnectionLostBanner } from "@/components/connection-lost-banner"
import { streamPodLog } from "@/lib/api/applications"
import { useLanguage } from "@/contexts/language-context"
import { ContentPage } from "@/components/content-page"

interface LogRow {
  id: number
  text: string
}

// "ended" is the container's output being over — the server said so; "lost" is the connection
// giving out with the browser's own reconnects exhausted.
type StreamStatus = "connecting" | "connected" | "ended" | "lost"

function ApplicationPodLogsContent() {
  const params = useParams()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const pod = params.pod as string
  const env = searchParams.get("environment")

  const [logs, setLogs] = useState<LogRow[]>([])
  const [error, setError] = useState<string | null>(null)
  const [status, setStatus] = useState<StreamStatus>("connecting")
  const bottomRef = useRef<HTMLDivElement>(null)
  const logIdRef = useRef(0)
  const { t } = useLanguage()

  useEffect(() => {
    if (!env || !namespace || !name || !pod) return

    // A dropped connection is the browser's own reconnect, resuming from the last event id, so
    // the rows already shown are never replayed and simply keep growing.
    return streamPodLog(namespace, name, pod, env, {
      onOpen: () => setStatus("connected"),
      onLog: (batch) => {
        const rows = batch.lines.map((line) => ({ id: ++logIdRef.current, text: line.text }))
        setLogs((prev) => prev.concat(rows))
      },
      onError: setError,
      onEnd: () => setStatus("ended"),
      onTerminate: () => setStatus("lost"),
    })
  }, [namespace, name, pod, env])

  useEffect(() => {
    requestAnimationFrame(() => {
      bottomRef.current?.scrollIntoView({ block: "end" })
    })
  }, [logs, status, error])

  if (!env) {
    return <div className="p-4">{t("pods.missingEnv")}</div>
  }

  const isConnected = status === "connected"

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
        {status === "lost" && (
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
              {/* The line's stamp is kept only for resuming the stream, not shown: an application
                  log usually carries its own time, and a second one in front would just push the
                  text over. */}
              {logs.map((log) => (
                <div key={log.id} className="whitespace-pre-wrap break-all">
                  {log.text}
                </div>
              ))}
              {error && <div className="text-destructive italic">{error}</div>}
              {status === "ended" && !error && (
                <div className="text-muted-foreground italic">{t("pods.logEnded")}</div>
              )}
              {/* Blinks only while the stream is connected: a cursor promises another line. */}
              {isConnected && (
                <div aria-hidden>
                  <span className="inline-block h-[1.1em] w-2 translate-y-[0.15em] bg-foreground/70 animate-caret-blink motion-reduce:animate-none" />
                </div>
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
