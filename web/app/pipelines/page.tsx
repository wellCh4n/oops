"use client"

import { useState, useEffect, useCallback, Suspense } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { DataTable } from "@/components/ui/data-table"
import { getPipelinesInScope, stopPipeline, deployPipeline, rollbackPipeline, getCurrentImage } from "@/lib/api/pipelines"
import { getApplications, getApplicationEnvironments } from "@/lib/api/applications"
import { fetchEnvironments } from "@/lib/api/environments"
import { useOperatorFilterStore } from "@/store/operator-filter"
import { Pipeline, Application } from "@/lib/api/types"
import { getPipelineColumns } from "./columns"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { RotateCcw, Layers, LayoutGrid, Server, User } from "lucide-react"
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
import { ALL_NAMESPACES, useNamespaces } from "@/hooks/use-namespaces"
import { usePageSize } from "@/store/page-size"
import { appIdentityBackground } from "@/lib/app-color"

// The application dropdown's "no filter" entry. Real options use application ids, so this
// cannot collide with one.
const ALL_APPS = "all"

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

  const { namespaces } = useNamespaces()

  // Every filter lives in the URL and nowhere else. `?app=` is a bare name under a
  // concrete namespace and `namespace/name` under the "all" scope, so a shared link
  // still says which application it meant.
  const selectedNamespace = searchParams.get("namespace") || ALL_NAMESPACES
  const appParam = searchParams.get("app") ?? ""
  const appSeparator = appParam.indexOf("/")
  const selectedApp = appSeparator >= 0 ? appParam.slice(appSeparator + 1) : appParam
  const appNamespace = appSeparator >= 0 ? appParam.slice(0, appSeparator) : ""
  const selectedEnv = searchParams.get("environment") ?? ""

  // "Mine / all", mirroring the application list's owner filter: the URL wins, the
  // remembered preference fills in on a first visit and is then written to the URL.
  const mineUrl = searchParams.get("mine")
  const { mine: mineStore, setMine } = useOperatorFilterStore()
  const mine = mineUrl !== null ? mineUrl !== "false" : mineStore

  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [loading, setLoading] = useState(false)
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

  const { t } = useLanguage()

  const page = Number(searchParams.get("page") ?? "1")
  const { size, rememberSize } = usePageSize(searchParams.get("size"))
  // An absent environment means "no filter" here, which this page spells "all".
  const effectiveEnv = selectedEnv || "all"
  const selectedApplication = applications.find(app => app.name === selectedApp
    && (!appNamespace || app.namespace === appNamespace))
  const selectedAppValue = selectedApplication?.id ?? (selectedApp || ALL_APPS)
  // The concrete namespace of the selected application: the scope itself unless
  // that is "all", in which case the URL carries it alongside the app name.
  const activeNamespace = selectedNamespace === ALL_NAMESPACES
    ? (appNamespace || selectedApplication?.namespace || "")
    : selectedNamespace

  const updateParams = useCallback((updates: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates).forEach(([k, v]) => {
      if (v) params.set(k, v)
      else params.delete(k)
    })
    router.replace(`/pipelines?${params.toString()}`)
  }, [router, searchParams])

  useEffect(() => {
    if (mineUrl === null) {
      const params = new URLSearchParams(searchParams.toString())
      params.set("mine", mineStore ? "true" : "false")
      router.replace(`/pipelines?${params.toString()}`)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mineUrl])

  // The application dropdown's options. Nothing is auto-selected: with no app in
  // the URL the list simply spans the whole scope.
  useEffect(() => {
    const load = async () => {
      try {
        const res = await getApplications(selectedNamespace)
        setApplications(res.data?.data ?? [])
      } catch {
        toast.error(t("pipelines.fetchAppsError"))
        setApplications([])
      }
    }
    load()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedNamespace])

  // The environment dropdown's options: the selected app's environments, or every
  // configured environment when the list spans applications.
  useEffect(() => {
    const load = async () => {
      try {
        if (selectedApp && activeNamespace) {
          const res = await getApplicationEnvironments(activeNamespace, selectedApp)
          setEnvironments((res.data ?? []).reduce<string[]>((acc, environment) => {
            if (environment.environment) acc.push(environment.environment)
            return acc
          }, []))
        } else {
          const res = await fetchEnvironments()
          setEnvironments((res.data ?? []).map((environment) => environment.name))
        }
      } catch {
        setEnvironments([])
      }
    }
    load()
  }, [activeNamespace, selectedApp])

  // Fetch pipelines. With an application selected the scope narrows to its own
  // namespace, so two same-named applications in different namespaces never mix.
  const fetchPipelines = useCallback(async () => {
    setLoading(true)
    try {
      const scope = selectedApp && activeNamespace ? activeNamespace : selectedNamespace
      const res = await getPipelinesInScope(scope, { app: selectedApp, environment: effectiveEnv, mine }, page, size)
      if (res.data) {
        setPipelines(res.data.data)
        setTotalPages(res.data.totalPages)
        setTotal(res.data.total)
      }
    } catch {
      toast.error(t("pipelines.fetchError"))
    } finally {
      setLoading(false)
    }
  }, [selectedNamespace, activeNamespace, selectedApp, effectiveEnv, mine, page, size, t])

  useEffect(() => {
    fetchPipelines()
  }, [fetchPipelines])

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
                  // Changing the scope drops the app and env filters: they belonged to the old one.
                  onValueChange={(namespace: string) => updateParams({ namespace: namespace === ALL_NAMESPACES ? "" : namespace, app: "", environment: "", page: "1" })}
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
                    if (option.value === ALL_APPS) {
                      updateParams({ app: "", environment: "", page: "1" })
                      return
                    }
                    if (!option.namespace || !option.name) return
                    const appValue = selectedNamespace === ALL_NAMESPACES ? `${option.namespace}/${option.name}` : option.name
                    // The environment belongs to the app, so changing app resets it.
                    updateParams({ app: appValue, environment: "", page: "1" })
                  }}
                  options={[{ value: ALL_APPS, label: t("pipelines.allApps") }, ...applications.map(app => ({
                    value: app.id,
                    label: selectedNamespace === ALL_NAMESPACES ? `${app.name} (${app.namespace})` : app.name,
                    namespace: app.namespace,
                    name: app.name,
                    icon: app.icon,
                    colorBackground: appIdentityBackground(app),
                  }))]}
                  onSearch={async (query) => {
                    const res = await getApplications(selectedNamespace, query || undefined, 1, 20)
                    return (res.data?.data ?? []).map(app => ({
                      value: app.id,
                      label: selectedNamespace === ALL_NAMESPACES ? `${app.name} (${app.namespace})` : app.name,
                      namespace: app.namespace,
                      name: app.name,
                      icon: app.icon,
                      colorBackground: appIdentityBackground(app),
                    }))
                  }}
                  placeholder={t("pipelines.allApps")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Server className="size-4" />{t("pipelines.envLabel")}</span>
                <SelectWithSearch
                  value={effectiveEnv}
                  onValueChange={(env: string) => updateParams({ environment: env === "all" ? "" : env, page: "1" })}
                  options={[{ value: "all", label: t("pipelines.allEnv") }, ...environments.map(env => ({ value: env, label: env }))]}
                  placeholder={t("pipelines.selectEnv")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><User className="size-4" />{t("pipelines.operatorFilter")}</span>
                <div className="inline-flex rounded-lg border bg-muted p-0.5 h-9">
                  {[false, true].map((mineOption) => (
                    <button
                      key={String(mineOption)}
                      type="button"
                      onClick={() => {
                        setMine(mineOption)
                        updateParams({ mine: mineOption ? "true" : "false", page: "1" })
                      }}
                      className={`px-3 py-1 text-sm font-medium rounded-md transition-all cursor-pointer ${
                        mine === mineOption
                          ? "bg-background shadow-sm text-foreground"
                          : "text-muted-foreground hover:text-foreground"
                      }`}
                    >
                      {mineOption ? t("pipelines.operatorMine") : t("pipelines.operatorAll")}
                    </button>
                  ))}
                </div>
              </div>
            </div>
            <Button variant="outline" onClick={fetchPipelines} disabled={loading}>
              <RotateCcw className={`size-4 ${loading ? 'animate-spin' : ''}`} />
              {t("pipelines.refresh")}
            </Button>
          </div>
        }
        table={
          <>
            <div className="overflow-x-auto">
              <DataTable
                columns={getPipelineColumns(t, handleStop, handleDeploy, handleRollback, currentPipelineId, effectiveEnv !== "all", !selectedApp)}
                data={pipelines}
                loading={loading}
              />
            </div>
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
