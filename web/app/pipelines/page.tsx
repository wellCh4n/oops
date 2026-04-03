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
import { useNamespaceStore } from "@/store/namespace"

export default function PipelinesPage() {
  return (
    <Suspense>
      <PipelinesContent />
    </Suspense>
  )
}

function PipelinesContent() {
  const router = useRouter()
  const searchParams = useSearchParams()

  const namespaces = useNamespaceStore((s) => s.namespaces)
  const selectedNamespace = useNamespaceStore((s) => s.selectedNamespace)
  const setSelectedNamespace = useNamespaceStore((s) => s.setSelectedNamespace)
  const loadNamespaces = useNamespaceStore((s) => s.load)

  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [loading, setLoading] = useState(false)
  const [hasNext, setHasNext] = useState(false)
  const [deployTarget, setDeployTarget] = useState<Pipeline | null>(null)

  const [applications, setApplications] = useState<Application[]>([])
  const [environments, setEnvironments] = useState<string[]>([])

  const [initialized, setInitialized] = useState(false)
  const { t } = useLanguage()

  const selectedApp = searchParams.get("app") ?? ""
  const selectedEnv = searchParams.get("env") ?? "all"
  const page = Number(searchParams.get("page") ?? "0")
  const size = 20

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
    setSelectedNamespace(ns)
    updateParams({ app: "", env: "all", page: "0" })
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
          setApplications(res.data)
          if (!searchParams.get("app") && res.data.length > 0) {
            updateParams({ app: res.data[0].name, page: "0" })
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
        setPipelines(res.data)
        setHasNext(res.data.length === size)
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
                <Select value={selectedNamespace} onValueChange={handleNamespaceChange}>
                  <SelectTrigger className="w-[200px]">
                    <SelectValue placeholder={t("pipelines.selectNs")} />
                  </SelectTrigger>
                  <SelectContent>
                    {namespaces.map(ns => (
                      <SelectItem key={ns.id} value={ns.id}>{ns.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><LayoutGrid className="w-4 h-4" />{t("pipelines.appLabel")}</span>
                <Select value={selectedApp} onValueChange={(v) => updateParams({ app: v, env: "all", page: "0" })} disabled={!selectedNamespace}>
                  <SelectTrigger className="w-[200px]">
                    <SelectValue placeholder={t("pipelines.selectApp")} />
                  </SelectTrigger>
                  <SelectContent>
                    {applications.map(app => (
                      <SelectItem key={app.name} value={app.name}>{app.name}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Server className="w-4 h-4" />{t("pipelines.envLabel")}</span>
                <Select value={selectedEnv} onValueChange={(v) => updateParams({ env: v, page: "0" })} disabled={!selectedNamespace || !selectedApp}>
                  <SelectTrigger className="w-[200px]">
                    <SelectValue placeholder={t("pipelines.selectEnv")} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="all">{t("pipelines.allEnv")}</SelectItem>
                    {environments.map(env => (
                      <SelectItem key={env} value={env}>{env}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
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
            <DataTable columns={getPipelineColumns(t, handleView, handleStop, handleDeploy)} data={pipelines} loading={loading} />
            {selectedApp && (
              <div className="flex items-center justify-end gap-2 mt-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={page === 0 || loading}
                  onClick={() => updateParams({ page: String(page - 1) })}
                >
                  <ChevronLeft className="h-4 w-4" />
                  {t("pipelines.prevPage")}
                </Button>
                <span className="text-sm text-muted-foreground">{t("pipelines.pagePrefix")}{page + 1}{t("pipelines.pageSuffix")}</span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!hasNext || loading}
                  onClick={() => updateParams({ page: String(page + 1) })}
                >
                  {t("pipelines.nextPage")}
                  <ChevronRight className="ml-2 h-4 w-4" />
                </Button>
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
