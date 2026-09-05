"use client"

import { useCallback, useEffect, useState } from "react"
import dynamic from "next/dynamic"
const Editor = dynamic(() => import("@monaco-editor/react"), { ssr: false })
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useTheme } from "next-themes"
import { getApplicationResources } from "@/lib/api/applications"
import { ApplicationResource } from "@/lib/api/types"
import { useLanguage } from "@/contexts/language-context"

/**
 * Loads the application's live Kubernetes manifests for one environment.
 * Fetches on mount and whenever the target changes; `reload` refetches on demand.
 */
export function useApplicationResources(namespace?: string, applicationName?: string, environment?: string) {
  const [resources, setResources] = useState<ApplicationResource[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)

  const reload = useCallback(async () => {
    if (!namespace || !applicationName || !environment) return
    setLoading(true)
    setError(false)
    try {
      const res = await getApplicationResources(namespace, applicationName, environment)
      setResources(res.data ?? [])
    } catch {
      setError(true)
      setResources([])
    } finally {
      setLoading(false)
    }
  }, [namespace, applicationName, environment])

  useEffect(() => {
    reload()
  }, [reload])

  return { resources, loading, error, reload }
}

interface ApplicationResourceViewerProps {
  environment?: string
  resources: ApplicationResource[]
  loading: boolean
  error: boolean
  /** Stretch the editor to fill the parent's height instead of a fixed 480px. */
  fill?: boolean
}

/** Read-only YAML view of the resources, one tab per Kubernetes kind. */
export function ApplicationResourceViewer({ environment, resources, loading, error, fill = false }: ApplicationResourceViewerProps) {
  const { t } = useLanguage()
  const { resolvedTheme } = useTheme()
  const editorTheme = resolvedTheme === "dark" ? "vs-dark" : "vs"
  const [activeKind, setActiveKind] = useState<string>("")

  const kinds = Array.from(new Set(resources.map((resource) => resource.kind)))
  // Fall back to the first kind when the selection is stale (first load, or a refetch that dropped it).
  const effectiveKind = kinds.includes(activeKind) ? activeKind : (kinds[0] ?? "")

  const activeContent = resources
    .filter((resource) => resource.kind === effectiveKind)
    .map((resource) => `# ===== ${resource.name} =====\n${resource.data.trimEnd()}`)
    .join("\n---\n")

  return (
    <Tabs value={effectiveKind} onValueChange={setActiveKind} className={fill ? "h-full min-h-0 w-full gap-2" : "w-full"}>
      {kinds.length > 0 && (
        <TabsList className="h-auto flex-wrap">
          {kinds.map((kind) => (
            <TabsTrigger key={kind} value={kind} className="cursor-pointer">{kind}</TabsTrigger>
          ))}
        </TabsList>
      )}
      <div className={fill ? "flex min-h-0 flex-1 flex-col overflow-hidden rounded-md border" : "border rounded-md overflow-hidden"}>
        {error ? (
          <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("apps.status.resourcesError")}</div>
        ) : loading ? (
          <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("common.loading")}</div>
        ) : resources.length === 0 ? (
          <div className="px-3 py-6 text-sm text-muted-foreground text-center">{t("apps.status.resourcesEmpty")}</div>
        ) : (
          <div className={fill ? "min-h-0 flex-1" : "h-[480px]"}>
            <Editor
              height="100%"
              defaultLanguage="yaml"
              theme={editorTheme}
              path={`${environment}/${effectiveKind}`}
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
