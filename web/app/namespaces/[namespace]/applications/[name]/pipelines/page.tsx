"use client"

import { useCallback, useEffect, useState } from "react"
import { useParams, useRouter, useSearchParams } from "next/navigation"
import { ChevronLeft, ChevronRight, Layers, LayoutGrid, RotateCcw, Server } from "lucide-react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { DataTable } from "@/components/ui/data-table"
import { SelectWithSearch } from "@/components/ui/select-with-search"
import { TableForm } from "@/components/ui/table-form"
import { ContentPage } from "@/components/content-page"
import { getPipelineColumns } from "@/app/pipelines/columns"
import { getPipelines, stopPipeline, deployPipeline } from "@/lib/api/pipelines"
import { getApplications, getApplicationBuildEnvConfigs } from "@/lib/api/applications"
import { Application, Pipeline } from "@/lib/api/types"
import { useLanguage } from "@/contexts/language-context"
import { useNamespaceStore } from "@/store/namespace"
import { useRecentAppStore } from "@/store/recent-app"
import {
  applicationPipelinePath,
  applicationPipelinesPath,
  applicationsPath,
} from "@/lib/routes"
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

export default function PipelinesPage() {
  const params = useParams()
  const router = useRouter()
  const searchParams = useSearchParams()
  const namespace = params.namespace as string
  const name = params.name as string
  const { t } = useLanguage()

  const namespaces = useNamespaceStore((state) => state.namespaces)
  const loadNamespaces = useNamespaceStore((state) => state.load)
  const setSelectedNamespace = useNamespaceStore((state) => state.setSelectedNamespace)
  const { recentApp, setRecentApp } = useRecentAppStore()

  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [loading, setLoading] = useState(false)
  const [totalPages, setTotalPages] = useState(0)
  const [deployTarget, setDeployTarget] = useState<Pipeline | null>(null)
  const [applications, setApplications] = useState<Application[]>([])
  const [environments, setEnvironments] = useState<string[]>([])

  const selectedEnv = searchParams.get("env") ?? "all"
  const page = Number(searchParams.get("page") ?? "1")
  const size = Number(searchParams.get("size") ?? "10")

  const buildRoute = useCallback((targetNamespace: string, targetName: string, updates?: Record<string, string>) => {
    const params = new URLSearchParams(searchParams.toString())
    Object.entries(updates ?? {}).forEach(([key, value]) => {
      if (value) params.set(key, value)
      else params.delete(key)
    })
    const query = params.toString()
    const pathname = applicationPipelinesPath(targetNamespace, targetName)
    return query ? `${pathname}?${query}` : pathname
  }, [searchParams])

  const updateParams = useCallback((updates: Record<string, string>) => {
    router.replace(buildRoute(namespace, name, updates))
  }, [buildRoute, name, namespace, router])

  useEffect(() => {
    loadNamespaces()
    setSelectedNamespace(namespace)
    setRecentApp({ namespace, name })
  }, [loadNamespaces, name, namespace, setRecentApp, setSelectedNamespace])

  useEffect(() => {
    if (!namespace) {
      setApplications([])
      return
    }
    getApplications(namespace)
      .then((res) => {
        setApplications(res.data?.data ?? [])
      })
      .catch(() => {
        toast.error(t("pipelines.fetchAppsError"))
        setApplications([])
      })
  }, [namespace, t])

  useEffect(() => {
    if (!namespace || !name) {
      setEnvironments([])
      return
    }
    getApplicationBuildEnvConfigs(namespace, name)
      .then((res) => {
        setEnvironments(res.data?.map((config) => config.environmentName).filter((env): env is string => !!env) ?? [])
      })
      .catch(() => setEnvironments([]))
  }, [namespace, name])

  const fetchPipelines = useCallback(async () => {
    if (!namespace || !name) {
      setPipelines([])
      return
    }
    setLoading(true)
    try {
      const res = await getPipelines(namespace, name, selectedEnv, page, size)
      if (res.data) {
        setPipelines(res.data.data)
        setTotalPages(res.data.totalPages)
      }
    } catch {
      toast.error(t("pipelines.fetchError"))
      setPipelines([])
    } finally {
      setLoading(false)
    }
  }, [name, namespace, page, selectedEnv, size, t])

  useEffect(() => {
    fetchPipelines()
  }, [fetchPipelines])

  const handleNamespaceChange = (targetNamespace: string) => {
    if (recentApp?.namespace === targetNamespace) {
      router.push(buildRoute(targetNamespace, recentApp.name, { env: "all", page: "1" }))
      return
    }
    router.push(applicationsPath(targetNamespace))
  }

  const handleApplicationChange = (targetName: string) => {
    const app = applications.find((item) => item.name === targetName)
    if (app) {
      setRecentApp({
        namespace: app.namespace,
        name: app.name,
        description: app.description,
        ownerName: app.ownerName,
      })
      router.push(buildRoute(app.namespace, app.name, { env: "all", page: "1" }))
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
                  value={namespace}
                  onValueChange={handleNamespaceChange}
                  options={namespaces.map((ns) => ({ value: ns.id, label: ns.name }))}
                  placeholder={t("pipelines.selectNs")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><LayoutGrid className="w-4 h-4" />{t("pipelines.appLabel")}</span>
                <SelectWithSearch
                  value={name}
                  onValueChange={handleApplicationChange}
                  options={applications.map((app) => ({ value: app.name, label: app.name }))}
                  placeholder={t("pipelines.selectApp")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                  disabled={!namespace}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-medium leading-none whitespace-nowrap flex items-center gap-1.5"><Server className="w-4 h-4" />{t("pipelines.envLabel")}</span>
                <SelectWithSearch
                  value={selectedEnv}
                  onValueChange={(value) => updateParams({ env: value, page: "1" })}
                  options={[{ value: "all", label: t("pipelines.allEnv") }, ...environments.map((env) => ({ value: env, label: env }))]}
                  placeholder={t("pipelines.selectEnv")}
                  searchPlaceholder={t("common.search")}
                  emptyText={t("common.noResults")}
                  className="w-[200px]"
                  disabled={!namespace || !name}
                />
              </div>
            </div>
            <Button variant="outline" onClick={fetchPipelines} disabled={loading || !name}>
              <RotateCcw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
              {t("pipelines.refresh")}
            </Button>
          </div>
        }
        table={
          <>
            <div className="overflow-x-auto">
              <DataTable
                columns={getPipelineColumns(
                  t,
                  (pipeline) => router.push(applicationPipelinePath(namespace, name, pipeline.id)),
                  async (pipeline) => {
                    try {
                      await stopPipeline(namespace, name, pipeline.id)
                      toast.success(t("pipelines.stopSuccess"))
                      fetchPipelines()
                    } catch {
                      toast.error(t("pipelines.stopError"))
                    }
                  },
                  (pipeline) => setDeployTarget(pipeline)
                )}
                data={pipelines}
                loading={loading}
              />
            </div>
            <div className="flex items-center justify-end gap-4 mt-2">
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">{t("common.pageSize")}</span>
                <Select
                  value={String(size)}
                  onValueChange={(value) => updateParams({ size: value, page: "1" })}
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
                  <ChevronRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          </>
        }
      />

      <AlertDialog open={!!deployTarget} onOpenChange={(open) => !open && setDeployTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("pipelines.confirmTitle")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("pipelines.confirmDescPrefix")}<strong>{deployTarget?.environment}</strong>{t("pipelines.confirmDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={async () => {
                if (!deployTarget) return
                try {
                  await deployPipeline(namespace, name, deployTarget.id)
                  toast.success(t("pipelines.deploySuccess"))
                  fetchPipelines()
                } catch {
                  toast.error(t("pipelines.deployError"))
                } finally {
                  setDeployTarget(null)
                }
              }}
            >
              {t("pipelines.confirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
