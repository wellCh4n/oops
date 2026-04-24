"use client"

import { useState } from "react"
import dynamic from "next/dynamic"
import { useParams, useSearchParams } from "next/navigation"
import { ContentPage } from "@/components/content-page"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { RefreshCw, WifiOff } from "lucide-react"
import { useLanguage } from "@/contexts/language-context"

const TerminalView = dynamic(() => import("@/components/terminal-view"), {
  ssr: false,
  loading: () => <div className="p-4 text-white">Loading terminal...</div>
})

export default function TerminalPage() {
  const params = useParams()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const pod = params.pod as string
  const env = searchParams.get("env")
  const [connectionStatus, setConnectionStatus] = useState<
    "connecting" | "connected" | "disconnected"
  >("connecting")
  const { t } = useLanguage()

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
            className={`h-2 w-2 rounded-full ${isConnected ? "bg-green-500" : "bg-gray-400"}`}
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
        <TerminalView
          namespace={namespace}
          name={name}
          pod={pod}
          env={env}
          onConnectionStatusChange={setConnectionStatus}
        />
      </div>
    </ContentPage>
  )
}
