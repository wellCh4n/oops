"use client"

import { useState, useEffect, useCallback, Suspense } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { DataTable } from "@/components/ui/data-table"
import { getPipelines, stopPipeline, deployPipeline, rollbackPipeline, getCurrentImage } from "@/lib/api/pipelines"
import { getApplications, getApplicationBuildEnvConfigs } from "@/lib/api/applications"
import { Pipeline, Application } from "@/lib/api/types"
import { getPipelineColumns } from "./columns"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { RotateCcw, Layers, LayoutGrid, Server } from "lucide-react"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { Pagination } from "@/components/ui/pagination"
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
import { useWorkContext } from "@/contexts/work-context"
import { ALL_NAMESPACES } from "@/store/work-context"
import { usePageSize } from "@/store/page-size"
import { appIdentityBackground } from "@/lib/app-color"

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

  const {
    namespaces,
    loadNamespaces,
    namespace: selectedNamespace,
    appName: selectedApp,
    appNamespace,
    env: selectedEnv,
    selectNamespace,
    selectApp,
    selectEnv,
    resolveApp,
  } = useWorkContext()

  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [loading, setLoading] = useState(false)
  const [initialLoad, setInitialLoad] = useState(true)
  const [totalPages, setTotalPages] = useState(0)
  const [total, setTotal] = useState(0)
  const [deployTarget, setDeployTarget] = useState<Pipeline | null>(null)
  const [stopTarget, setStopTarget] = useState<Pipeline | null>(null)
  const [stopping, setStopping] = useState(false)
  const [rollbackTarget, setRollbackTarget] = useState<Pipeline | null>(null)
  const [rolling, setRolling] = useState(false)
  const [currentPipelineId, setCurrentPipelineId] = useState<string | null>(null)

  const [applications, setApplications] = useState<Application[]>([])
  const [environments, setEnvironments] = useState<string[]>([])

  const [initialized, setInitialized] = useState(false)
  const { t } = useLanguage()

  const page = Number(searchParams.get("page") ?? "1")
  const { size, rememberSize } = usePageSize(searchParams.get("size"))
  // An empty context environment means "no filter" here, which this page spells "all".
  const effectiveEnv = selectedEnv || "all"
  const selectedApplication = applications.find(app => app.name === selectedApp)
  const selectedAppValue = selectedApplication?.id ?? selectedApp
  // Under the "all" scope the app carries its own namespace — the context knows
  // it, and the loaded list refines it. The scope itself is never rewritten.
  const activeNamespace = selectedApplication?.namespace || appNamespace

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

  // Load applications when namespace changes. Nothing is auto-selected here:
  // if the context has no app, the user picks one rather than landing on an
  // arbitrary first entry that only looks like it was remembered.
  useEffect(() => {
    if (!selectedNamespace) {
      setApplications([])
      return
    }
    const load = async () => {
      try {
        const res = await getApplications(selectedNamespace)
        const apps = res.data?.data ?? []
        setApplications(apps)
        const contextApp = apps.find(app => app.name === selectedApp)
        if (contextApp) {
          resolveApp({
            namespace: contextApp.namespace,
            name: contextApp.name,
            description: contextApp.description,
            ownerName: contextApp.ownerName,
            icon: contextApp.icon,
          })
        }
      } catch {
        toast.error(t("pipelines.fetchAppsError"))
        setApplications([])
      }
    }
    load()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace, selectedApp])

  // Load environments when app changes
  useEffect(() => {
    if (!activeNamespace || !selectedApp) {
      setEnvironments([])
      return
    }
    const load = async () => {
      try {
        const res = await getApplicationBuildEnvConfigs(activeNamespace, selectedApp)
        if (res.data) {
          setEnvironments(res.data.reduce<string[]>((acc, c) => {
            if (c.environmentName) acc.push(c.environmentName)
            return acc
          }, []))
        }
      } catch {
        setEnvironments([])
      }
    }
    load()
  }, [activeNamespace, selectedApp])

  // Fetch pipelines
  const fetchPipelines = useCallback(async () => {
    if (!activeNamespace || !selectedApp) {
      setPipelines([])
      setInitialLoad(false)
      return
    }
    setLoading(true)
    try {
      const res = await getPipelines(activeNamespace, selectedApp, effectiveEnv, page, size)
      if (res.data) {
        setPipelines(res.data.data)
        setTotalPages(res.data.totalPages)
        setTotal(res.data.total)
      }
    } catch {
      toast.error(t("pipelines.fetchError"))
    } finally {
      setLoading(false)
      setInitialLoad(false)
    }
  }, [activeNamespace, selectedApp, effectiveEnv, page, size, t])

  useEffect(() => {
    if (initialized) fetchPipelines()
  }, [initialized, fetchPipelines])

  // Load the current live image to highlight which pipeline is deployed.
  // Only meaningful when a concrete environment is selected.
  useEffect(() => {
    if (!activeNamespace || !selectedApp || effectiveEnv === "all") {
      setCurrentPipelineId(null)
      return
    }
    const load = async () => {
      try {
        const res = await getCurrentImage(activeNamespace, selectedApp, effectiveEnv)
        const image = res.data
        setCurrentPipelineId(image ? image.split(":").pop() ?? null : null)
      } catch {
        setCurrentPipelineId(null)
      }
    }
    load()
  }, [activeNamespace, selectedApp, effectiveEnv])

  const handleStop = (pipeline: Pipeline) => {
    setStopTarget(pipeline)
  }

  const confirmStop = async () => {
    if (!stopTarget) return
    setStopping(true)
    try {
      await stopPipeline(stopTarget.namespace, stopTarget.applicationName, stopTarget.id)
      toast.success(t("pipelines.stopSuccess"))
      fetchPipelines()
    } catch {
      toast.error(t("pipelines.stopError"))
    } finally {
      setStopping(false)
      setStopTarget(null)
    }
  }

  const handleDeploy = (pipeline: Pipeline) => {
    setDeployTarget(pipeline)
  }

  const handleRollback = (pipeline: Pipeline) => {
    setRollbackTarget(pipeline)
  }

  const confirmRollback = async () => {
    if (!rollbackTarget) return
    setRolling(true)
    try {
      await rollbackPipeline(rollbackTarget.namespace, rollbackTarget.applicationName, rollbackTarget.id)
      toast.success(t("pipelines.rollbackSuccess"))
      fetchPipelines()
    } catch {
      toast.error(t("pipelines.rollbackError"))
    } finally {
      setRolling(false)
      setRollbackTarget(null)
    }
  }

  const confirmDeploy = async () => {
    if (!deployTarget) return
    try {
      await deployPipeline(deployTarget.namespace, deployTarget.applicationName, deployTarget.id)
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
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Layers className="size-4" />{t("pipelines.nsLabel")}</span>
                <SelectWithSearch
                  value={selectedNamespace}
                  onValueChange={(namespace: string) => selectNamespace(namespace, { page: "1" })}
                  options={[{ value: ALL_NAMESPACES, label: t("common.allNamespaces") }, ...namespaces.map(ns => ({ value: ns.id, label: ns.name }))]}
                  placeholder={t("pipelines.selectNs")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><LayoutGrid className="size-4" />{t("pipelines.appLabel")}</span>
                <SelectWithSearch
                  value={selectedAppValue}
                  onOptionSelect={(option) => {
                    if (!option.namespace || !option.name) return
                    const app = applications.find(a => a.id === option.value)
                    selectApp(app
                      ? { namespace: app.namespace, name: app.name, description: app.description, ownerName: app.ownerName, icon: app.icon }
                      : { namespace: option.namespace, name: option.name },
                      { page: "1" })
                  }}
                  options={applications.map(app => ({
                    value: app.id,
                    label: selectedNamespace === ALL_NAMESPACES ? `${app.name} (${app.namespace})` : app.name,
                    namespace: app.namespace,
                    name: app.name,
                    icon: app.icon,
                    colorBackground: appIdentityBackground(app),
                  }))}
                  onSearch={selectedNamespace ? async (query) => {
                    const res = await getApplications(selectedNamespace, query || undefined, 1, 20)
                    return (res.data?.data ?? []).map(app => ({
                      value: app.id,
                      label: selectedNamespace === ALL_NAMESPACES ? `${app.name} (${app.namespace})` : app.name,
                      namespace: app.namespace,
                      name: app.name,
                      icon: app.icon,
                      colorBackground: appIdentityBackground(app),
                    }))
                  } : undefined}
                  placeholder={t("pipelines.selectApp")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                  disabled={!selectedNamespace}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Server className="size-4" />{t("pipelines.envLabel")}</span>
                <SelectWithSearch
                  value={effectiveEnv}
                  onValueChange={(env: string) => selectEnv(env === "all" ? "" : env, { page: "1" })}
                  options={[{ value: "all", label: t("pipelines.allEnv") }, ...environments.map(env => ({ value: env, label: env }))]}
                  placeholder={t("pipelines.selectEnv")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                  disabled={!activeNamespace || !selectedApp}
                />
              </div>
            </div>
            <Button variant="outline" onClick={fetchPipelines} disabled={loading || !activeNamespace || !selectedApp}>
              <RotateCcw className={`size-4 ${loading ? 'animate-spin' : ''}`} />
              {t("pipelines.refresh")}
            </Button>
          </div>
        }
        table={
          !selectedApp ? (
            <div className="py-16 text-center text-muted-foreground text-sm border rounded-md border-dashed">
              {t("pipelines.selectAppHint")}
            </div>
          ) : (
          <>
            <div className="overflow-x-auto">
              <DataTable columns={getPipelineColumns(t, handleStop, handleDeploy, handleRollback, currentPipelineId)} data={pipelines} loading={initialLoad} />
            </div>
            {selectedApp && (
              <Pagination
                page={page}
                totalPages={totalPages}
                total={total}
                onPageChange={(next) => updateParams({ page: String(next) })}
                size={size}
                onSizeChange={(next) => {
                  rememberSize(next)
                  updateParams({ size: String(next), page: "1" })
                }}
                loading={loading}
              />
            )}
          </>
          )
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
      <AlertDialog open={!!stopTarget} onOpenChange={(open) => { if (!open && !stopping) setStopTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("pipelines.stopConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("pipelines.stopConfirmDesc")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={stopping}>{t("common.cancel")}</AlertDialogCancel>
            <Button onClick={confirmStop} disabled={stopping}>
              {stopping ? t("pipelines.stopping") : t("pipelines.stopConfirm")}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <AlertDialog open={!!rollbackTarget} onOpenChange={(open) => { if (!open && !rolling) setRollbackTarget(null) }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("pipelines.rollbackConfirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>{t("pipelines.rollbackConfirmDesc")}</AlertDialogDescription>
          </AlertDialogHeader>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-md border bg-muted/40 p-3 text-sm">
            <dt className="text-muted-foreground">{t("pipelines.rollback.namespace")}</dt>
            <dd className="font-medium break-all">{rollbackTarget?.namespace}</dd>
            <dt className="text-muted-foreground">{t("pipelines.rollback.application")}</dt>
            <dd className="font-medium break-all">{rollbackTarget?.applicationName}</dd>
            <dt className="text-muted-foreground">{t("pipelines.rollback.environment")}</dt>
            <dd className="font-medium break-all">{rollbackTarget?.environment}</dd>
            <dt className="text-muted-foreground">{t("pipelines.rollback.image")}</dt>
            <dd className="font-medium break-all">{rollbackTarget?.artifact}</dd>
            <dt className="text-muted-foreground">{t("pipelines.rollback.publishedAt")}</dt>
            <dd className="font-medium">
              {rollbackTarget?.createdTime
                ? new Date(rollbackTarget.createdTime).toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
                : "-"}
            </dd>
          </dl>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={rolling}>{t("common.cancel")}</AlertDialogCancel>
            <Button onClick={confirmRollback} disabled={rolling}>
              {rolling ? t("pipelines.rolling") : t("pipelines.rollbackConfirm")}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
