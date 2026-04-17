"use client"

import { useState, useEffect, useCallback, Suspense } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { DataTable } from "@/components/ui/data-table"
import { getPipelines, stopPipeline, deployPipeline } from "@/lib/api/pipelines"
import { getApplications, getApplicationBuildEnvConfigs } from "@/lib/api/applications"
import { Pipeline, Application } from "@/lib/api/types"
import { getPipelineColumns } from "./columns"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { RotateCcw, ChevronLeft, ChevronRight, Layers, LayoutGrid, Server } from "lucide-react"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { ContentPage } from "@/components/content-page"
import { TableForm } from "@/components/ui/table-form"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { useLanguage } from "@/contexts/language-context"
import { NamespaceParamProvider, useNamespace } from "@/contexts/namespace-context"
import { useRecentAppStore } from "@/store/recent-app"

export default function PipelinesPage() {
  return (
    <Suspense>
      <NamespaceParamProvider>
        <PipelinesContent />
      </NamespaceParamProvider>
    </Suspense>
  )
}

function PipelinesContent() {
  const router = useRouter()
  const searchParams = useSearchParams()

  const { namespaces, selectedNamespace, loadNamespaces } = useNamespace()

  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [loading, setLoading] = useState(false)
  const [totalPages, setTotalPages] = useState(0)
  const [deployTarget, setDeployTarget] = useState<Pipeline | null>(null)

  const [applications, setApplications] = useState<Application[]>([])
  const [environments, setEnvironments] = useState<string[]>([])

  const [initialized, setInitialized] = useState(false)
  const { t } = useLanguage()
  const { recentApp, setRecentApp } = useRecentAppStore()

  const selectedApp = searchParams.get("app") ?? ""
  const selectedEnv = searchParams.get("env") ?? "all"
  const page = Number(searchParams.get("page") ?? "1")
  const size = Number(searchParams.get("size") ?? "10")

  const updateParams = useCallback((updates: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates).forEach(([k, v]) => {
      if (v) params.set(k, v)
      else params.delete(k)
    })
    router.replace(`/pipelines?${params.toString()}`)
  }, [router, searchParams])

  // Load global namespaces once
  useEffect(() => {
    const load = async () => {
      await loadNamespaces()
      setInitialized(true)
    }
    load()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const handleNamespaceChange = (ns: string) => {
    updateParams({ namespace: ns, app: "", env: "all" })
  }

  // Load applications when namespace changes
  useEffect(() => {
    if (!selectedNamespace) {
      setApplications([])
      return
    }
    const load = async () => {
      try {
        const res = await getApplications(selectedNamespace)
        if (res.data) {
          setApplications(res.data.data)
          if (!searchParams.get("app") && res.data.data.length > 0) {
            const recent = recentApp && recentApp.namespace === selectedNamespace
              ? res.data.data.find(a => a.name === recentApp.name)
              : undefined
            const targetApp = recent ?? res.data.data[0]
            updateParams({ app: targetApp.name })
          }
        }
      } catch {
        toast.error(t("pipelines.fetchAppsError"))
        setApplications([])
      }
    }
    load()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace])

  // Load environments when app changes
  useEffect(() => {
    if (!selectedNamespace || !selectedApp) {
      setEnvironments([])
      return
    }
    const load = async () => {
      try {
        const res = await getApplicationBuildEnvConfigs(selectedNamespace, selectedApp)
        if (res.data) {
          setEnvironments(res.data.map(c => c.environmentName).filter((name): name is string => !!name))
        }
      } catch {
        setEnvironments([])
      }
    }
    load()
  }, [selectedNamespace, selectedApp])

  // Fetch pipelines
  const fetchPipelines = useCallback(async () => {
    if (!selectedNamespace || !selectedApp) {
      setPipelines([])
      return
    }
    setLoading(true)
    try {
      const res = await getPipelines(selectedNamespace, selectedApp, selectedEnv, page, size)
      if (res.data) {
        setPipelines(res.data.data)
        setTotalPages(res.data.totalPages)
      }
    } catch {
      toast.error(t("pipelines.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [selectedNamespace, selectedApp, selectedEnv, page, size, t])

  useEffect(() => {
    if (initialized) fetchPipelines()
  }, [initialized, fetchPipelines])

  const handleView = (pipeline: Pipeline) => {
    router.push(`/apps/${selectedNamespace}/${selectedApp}/pipelines/${pipeline.id}`)
  }

  const handleStop = async (pipeline: Pipeline) => {
    try {
      await stopPipeline(selectedNamespace, selectedApp, pipeline.id)
      toast.success(t("pipelines.stopSuccess"))
      fetchPipelines()
    } catch {
      toast.error(t("pipelines.stopError"))
    }
  }

  const handleDeploy = (pipeline: Pipeline) => {
    setDeployTarget(pipeline)
  }

  const confirmDeploy = async () => {
    if (!deployTarget) return
    try {
      await deployPipeline(selectedNamespace, selectedApp, deployTarget.id)
      toast.success(t("pipelines.deploySuccess"))
      fetchPipelines()
    } catch {
      toast.error(t("pipelines.deployError"))
    } finally {
      setDeployTarget(null)
    }
  }

  return (
    <ContentPage title={t("pipelines.title")}>
      <TableForm
        options={
          <div className="flex items-end justify-between flex-wrap gap-4">
            <div className="flex items-center gap-4 flex-wrap">
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Layers className="w-4 h-4" />{t("pipelines.nsLabel")}</span>
                <SelectWithSearch
                  value={selectedNamespace}
                  onValueChange={handleNamespaceChange}
                  options={namespaces.map(ns => ({ value: ns.id, label: ns.name }))}
                  placeholder={t("pipelines.selectNs")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><LayoutGrid className="w-4 h-4" />{t("pipelines.appLabel")}</span>
                <SelectWithSearch
                  value={selectedApp}
                  onValueChange={(v: string) => {
                    const app = applications.find(a => a.name === v)
                    if (app) {
                      setRecentApp({
                        namespace: app.namespace,
                        name: app.name,
                        description: app.description,
                        ownerName: app.ownerName,
                      })
                    }
                    updateParams({ app: v, env: "all", page: "1" })
                  }}
                  options={applications.map(app => ({ value: app.name, label: app.name }))}
                  onSearch={selectedNamespace ? async (query) => {
                    const res = await getApplications(selectedNamespace, query || undefined, 1, 20)
                    return (res.data?.data ?? []).map(app => ({ value: app.name, label: app.name }))
                  } : undefined}
                  placeholder={t("pipelines.selectApp")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                  disabled={!selectedNamespace}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Server className="w-4 h-4" />{t("pipelines.envLabel")}</span>
                <SelectWithSearch
                  value={selectedEnv}
                  onValueChange={(v: string) => updateParams({ env: v, page: "1" })}
                  options={[{ value: "all", label: t("pipelines.allEnv") }, ...environments.map(env => ({ value: env, label: env }))]}
                  placeholder={t("pipelines.selectEnv")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                  disabled={!selectedNamespace || !selectedApp}
                />
              </div>
            </div>
            <Button variant="outline" onClick={fetchPipelines} disabled={loading || !selectedApp}>
              <RotateCcw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
              {t("pipelines.refresh")}
            </Button>
          </div>
        }
        table={
          <>
            <div className="overflow-x-auto">
              <DataTable columns={getPipelineColumns(t, handleView, handleStop, handleDeploy)} data={pipelines} loading={loading} />
            </div>
            {selectedApp && (
              <div className="flex items-center justify-end gap-4 mt-2">
                <div className="flex items-center gap-2">
                  <span className="text-sm text-muted-foreground">{t("common.pageSize")}</span>
                  <Select
                    value={String(size)}
                    onValueChange={(v) => updateParams({ size: v, page: "1" })}
                    disabled={loading}
                  >
                    <SelectTrigger className="w-[70px] h-8">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="10">10</SelectItem>
                      <SelectItem value="20">20</SelectItem>
                      <SelectItem value="50">50</SelectItem>
                    </SelectContent>
                  </Select>
                  <span className="text-sm text-muted-foreground">{t("common.pageSizeSuffix")}</span>
                </div>
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={page === 1 || loading}
                    onClick={() => updateParams({ page: String(page - 1) })}
                  >
                    <ChevronLeft className="h-4 w-4" />
                    {t("pipelines.prevPage")}
                  </Button>
                  <span className="text-sm text-muted-foreground">
                    {t("pipelines.pagePrefix")}{page}{t("pipelines.pageSuffix")} / {t("common.totalPages").replace("${total}", String(totalPages))}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={page >= totalPages || loading}
                    onClick={() => updateParams({ page: String(page + 1) })}
                  >
                    {t("pipelines.nextPage")}
                    <ChevronRight className="ml-2 h-4 w-4" />
                  </Button>
                </div>
              </div>
            )}
          </>
        }
      />
      <AlertDialog open={!!deployTarget} onOpenChange={(open) => { if (!open) setDeployTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("pipelines.confirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("pipelines.confirmDescPrefix")}<strong>{deployTarget?.environment}</strong>{t("pipelines.confirmDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={confirmDeploy}>{t("pipelines.confirm")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
