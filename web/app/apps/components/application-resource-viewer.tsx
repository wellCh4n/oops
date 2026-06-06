"use client"

import { useCallback, useEffect, useState } from "react"
import dynamic from "next/dynamic"
const Editor = dynamic(() => import("@monaco-editor/react"), { ssr: false })
import { Button } from "@/components/ui/button"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { RefreshCw } from "lucide-react"
import { useTheme } from "next-themes"
import { getApplicationResources } from "@/lib/api/applications"
import { ApplicationResource } from "@/lib/api/types"
import { useLanguage } from "@/contexts/language-context"

interface ApplicationResourceViewerProps {
  namespace?: string
  applicationName?: string
  environmentName?: string
}

export function ApplicationResourceViewer({ namespace, applicationName, environmentName }: ApplicationResourceViewerProps) {
  const { t } = useLanguage()
  const { resolvedTheme } = useTheme()
  const editorTheme = resolvedTheme === "dark" ? "vs-dark" : "vs"
  const [resources, setResources] = useState<ApplicationResource[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [activeKind, setActiveKind] = useState<string>("")

  const loadResources = useCallback(async () => {
    if (!namespace || !applicationName || !environmentName) return
    setLoading(true)
    setError(false)
    try {
      const res = await getApplicationResources(namespace, applicationName, environmentName)
      setResources(res.data ?? [])
    } catch {
      setError(true)
      setResources([])
    } finally {
      setLoading(false)
    }
  }, [namespace, applicationName, environmentName])

  useEffect(() => {
    loadResources()
  }, [loadResources])

  const kinds = Array.from(new Set(resources.map((resource) => resource.kind)))

  useEffect(() => {
    if (kinds.length > 0 && !kinds.includes(activeKind)) {
      setActiveKind(kinds[0])
    }
  }, [kinds, activeKind])

  const activeContent = resources
    .filter((resource) => resource.kind === activeKind)
    .map((resource) => `# ===== ${resource.name} =====\n${resource.data.trimEnd()}`)
    .join("\n---\n")

  return (
    <Tabs value={activeKind} onValueChange={setActiveKind} className="w-full">
      <div className="flex items-center gap-2">
        {kinds.length > 0 && (
          <TabsList className="h-auto flex-wrap">
            {kinds.map((kind) => (
              <TabsTrigger key={kind} value={kind} className="cursor-pointer">{kind}</TabsTrigger>
            ))}
          </TabsList>
        )}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="ml-auto h-7 px-2 text-muted-foreground"
          onClick={loadResources}
          disabled={loading || !applicationName}
        >
          <RefreshCw className={`size-3.5 ${loading ? "animate-spin" : ""}`} />
          {t("apps.status.resourcesRefresh")}
        </Button>
      </div>
      <div className="border rounded-md overflow-hidden">
        {error ? (
          <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("apps.status.resourcesError")}</div>
        ) : loading ? (
          <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("common.loading")}</div>
        ) : resources.length === 0 ? (
          <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("apps.status.resourcesEmpty")}</div>
        ) : (
          <div className="h-[480px]">
            <Editor
              height="100%"
              defaultLanguage="yaml"
              theme={editorTheme}
              path={`${environmentName}/${activeKind}`}
              value={activeContent}
              options={{
                readOnly: true,
                minimap: { enabled: false },
                lineNumbers: "on",
                scrollBeyondLastLine: false,
                automaticLayout: true,
                padding: { top: 10 },
              }}
            />
          </div>
        )}
      </div>
    </Tabs>
  )
}
