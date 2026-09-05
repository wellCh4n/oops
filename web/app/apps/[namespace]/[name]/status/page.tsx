"use client"

import { Suspense, useState, useEffect, useCallback, Fragment } from "react"
import { useParams, useRouter, useSearchParams, usePathname } from "next/navigation"
import { restartApplicationPod, getClusterDomain, watchApplicationStatus, getApplicationMetrics, getApplicationEnvironments } from "@/lib/api/applications"
import { ApplicationPodStatus, ApplicationEnvironment, ClusterDomainInfo, PodMetric } from "@/lib/api/types"
import { DataTable } from "@/components/ui/data-table"
import { Copyable } from "@/components/ui/copyable"
import { getStatusColumns } from "./columns"
import { toast } from "sonner"
import { Bell, Boxes, ExternalLink, LineChart } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ApplicationResourceDrawer } from "@/app/apps/components/application-resource-drawer"
import { ApplicationEventsDrawer } from "@/app/apps/components/application-events-drawer"
import { ApplicationMetricsDrawer } from "@/app/apps/components/application-metrics-drawer"
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
import { ContentPage } from "@/components/content-page"
import { AppDetailNav } from "@/app/apps/components/app-detail-nav"
import { TableForm } from "@/components/ui/table-form"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { Button } from "@/components/ui/button"
import { useLanguage } from "@/contexts/language-context"
import { useWorkContextStore } from "@/store/work-context"
import { shortImageName } from "@/lib/utils"

function formatUptime(startedAt: string | null | undefined): string {
  if (!startedAt) return "-"
  const startTime = new Date(startedAt).getTime()
  if (isNaN(startTime)) return "-"
  const diffMs = Date.now() - startTime
  if (diffMs < 0) return "-"
  const totalSeconds = Math.floor(diffMs / 1000)
  const days = Math.floor(totalSeconds / 86400)
  const hours = Math.floor((totalSeconds % 86400) / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${minutes}m`
  return `${minutes}m`
}

export default function ApplicationStatusPage() {
  return (
    <Suspense fallback={null}>
      <ApplicationStatusContent />
    </Suspense>
  )
}

function ApplicationStatusContent() {
  const params = useParams()
  const router = useRouter()
  const searchParams = useSearchParams()
  const pathname = usePathname()

  const namespace = params.namespace as string
  const name = params.name as string
  const envParam = searchParams.get("environment")

  const [loading, setLoading] = useState(true)
  const [podStatuses, setPodStatuses] = useState<ApplicationPodStatus[]>([])
  const [environments, setEnvironments] = useState<ApplicationEnvironment[]>([])
  const [selectedEnv, setSelectedEnv] = useState<string>("")
  const [isRestartDialogOpen, setIsRestartDialogOpen] = useState(false)
  const [podToRestart, setPodToRestart] = useState<string | null>(null)
  const [clusterDomain, setClusterDomain] = useState<ClusterDomainInfo | null>(null)
  const [metricsByPod, setMetricsByPod] = useState<Record<string, PodMetric>>({})
  const [metricsLoading, setMetricsLoading] = useState(false)
  const [metricsDrawerOpen, setMetricsDrawerOpen] = useState(false)
  const [resourceDrawerOpen, setResourceDrawerOpen] = useState(false)
  const [eventsDrawerOpen, setEventsDrawerOpen] = useState(false)
  const { t } = useLanguage()
  const enterApp = useWorkContextStore((state) => state.enterApp)
  const setWorkContext = useWorkContextStore((state) => state.setContext)
  const contextEnv = useWorkContextStore((state) => state.env)

  useEffect(() => {
    enterApp({ namespace, name })
  }, [namespace, name, enterApp])

  // Fetch environments on mount
  useEffect(() => {
    const loadEnvironments = async () => {
      try {
        const res = await getApplicationEnvironments(namespace, name)
        if (res.data && Array.isArray(res.data) && res.data.length > 0) {
          setEnvironments(res.data)

          // Determine initial environment: the URL first, then the environment
          // carried in the work context, and only then the first one.
          let initialEnv = res.data[0].environment
          if (envParam && res.data.some(environment => environment.environment === envParam)) {
            initialEnv = envParam
          } else if (!envParam && contextEnv && res.data.some(environment => environment.environment === contextEnv)) {
            initialEnv = contextEnv
          }

          setSelectedEnv(initialEnv)
          setWorkContext({ env: initialEnv })

          // Sync URL if needed
          if (initialEnv !== envParam) {
            const newParams = new URLSearchParams(searchParams.toString())
            newParams.set("environment", initialEnv)
            router.replace(`${pathname}?${newParams.toString()}`)
          }
        }
      } catch (error) {
        console.error("Failed to fetch environments:", error)
        toast.error(t("apps.status.fetchEnvError"))
      }
    }
    loadEnvironments()
  // `contextEnv` is only a seed for the first render — reacting to it would
  // fight the user's own tab switches.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [namespace, name, envParam, pathname, router, searchParams, t])

  // Subscribe to pod status via SSE when environment changes
  useEffect(() => {
    if (!selectedEnv) return

    setLoading(true)
    setPodStatuses([])

    // Fetch cluster domain (one-shot)
    getClusterDomain(namespace, name, selectedEnv)
      .then(res => setClusterDomain(res.data ?? null))
      .catch(() => setClusterDomain(null))

    const stopWatch = watchApplicationStatus(namespace, name, selectedEnv, {
      events: {
        status: (statuses) => {
          setPodStatuses(statuses)
          setLoading(false)
        },
      },
      onError: () => {
        // Browser will auto-reconnect; just clear the initial loading state
        setLoading(false)
      },
      onTerminate: () => {
        toast.error(t("apps.status.watchTerminated"))
      },
    })

    return stopWatch
  }, [namespace, name, selectedEnv, t])

  // Live resource usage (metrics-server), keyed by pod name for the status table.
  const loadMetrics = useCallback(async () => {
    if (!selectedEnv) {
      setMetricsByPod({})
      return
    }
    setMetricsLoading(true)
    try {
      const res = await getApplicationMetrics(namespace, name, selectedEnv)
      const map: Record<string, PodMetric> = {}
      for (const metric of res.data ?? []) {
        map[metric.podName] = metric
      }
      setMetricsByPod(map)
    } catch {
      setMetricsByPod({})
    } finally {
      setMetricsLoading(false)
    }
  }, [namespace, name, selectedEnv])

  // metrics-server samples roughly every 15s, so polling faster gains no fresher data.
  useEffect(() => {
    loadMetrics()
    const timer = setInterval(loadMetrics, 15000)
    return () => clearInterval(timer)
  }, [loadMetrics])

  const handleTabChange = (value: string) => {
    setSelectedEnv(value)
    setWorkContext({ env: value })
    const newParams = new URLSearchParams(searchParams.toString())
    newParams.set("environment", value)
    router.push(`${pathname}?${newParams.toString()}`)
  }

  const handleRestartClick = (podName: string) => {
    setPodToRestart(podName)
    setIsRestartDialogOpen(true)
  }

  const confirmRestart = async () => {
    if (!podToRestart) return

    try {
      await restartApplicationPod(namespace, name, podToRestart, selectedEnv)
      toast.success(t("apps.status.restartSuccess"))
      // Status table will pick up the new pod state via the SSE watch
    } catch (error) {
      console.error("Failed to restart pod:", error)
      toast.error(t("apps.status.restartError"))
    } finally {
      setIsRestartDialogOpen(false)
      setPodToRestart(null)
    }
  }

  const columns = getStatusColumns(t, handleRestartClick, {
    namespace,
    applicationName: name,
    env: selectedEnv,
  }, metricsByPod, loadMetrics, metricsLoading)

  const renderExpandedRow = (pod: ApplicationPodStatus) => {
    const containers = pod.containers ?? []
    return (
      <div className="flex flex-col gap-2">
        <div className="text-sm font-medium text-muted-foreground">
          {t("apps.status.containers")} ({containers.length})
        </div>
        <div className="rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="h-8 px-3">{t("apps.status.containerName")}</TableHead>
                <TableHead className="h-8 px-3">{t("apps.status.containerImage")}</TableHead>
                <TableHead className="h-8 px-3">{t("apps.status.containerReady")}</TableHead>
                <TableHead className="h-8 px-3">{t("apps.status.containerRestarts")}</TableHead>
                <TableHead className="h-8 px-3">{t("apps.status.containerUptime")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {containers.map((container) => (
                <TableRow key={container.name}>
                  <TableCell className="px-3 py-2 font-medium">{container.name}</TableCell>
                  <TableCell className="px-3 py-2 text-muted-foreground max-w-xs">
                    <Tooltip>
                      <TooltipTrigger render={<span className="truncate inline-block max-w-full align-bottom" />}>
                        {shortImageName(container.image)}
                      </TooltipTrigger>
                      <TooltipContent className="max-w-160 break-all font-mono text-xs">
                        {container.image}
                      </TooltipContent>
                    </Tooltip>
                  </TableCell>
                  <TableCell className="px-3 py-2">
                    <Badge variant={container.ready ? "info" : "destructive"}>
                      {container.ready ? "Ready" : "Not Ready"}
                    </Badge>
                  </TableCell>
                  <TableCell className={container.restartCount > 0 ? "px-3 py-2 text-destructive font-medium" : "px-3 py-2"}>
                    {container.restartCount}
                  </TableCell>
                  <TableCell className="px-3 py-2 text-muted-foreground">
                    {formatUptime(container.startedAt)}
                  </TableCell>
                </TableRow>
              ))}
              {containers.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} className="h-16 text-center text-muted-foreground">
                    {t("common.noData")}
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </div>
      </div>
    )
  }

  return (
    <ContentPage
      title={t("apps.status.title")}
      actions={<AppDetailNav namespace={namespace} name={name} active="status" />}
    >
      <TableForm
        options={
          <div className="space-y-2">
            {environments.length > 0 && (
              <div className="flex items-center justify-between gap-2">
                <Tabs value={selectedEnv} onValueChange={handleTabChange}>
                  <TabsList>
                    {environments.map((env) => (
                      <TabsTrigger key={env.environment} value={env.environment} className="px-8">
                        {env.environment}
                      </TabsTrigger>
                    ))}
                  </TabsList>
                </Tabs>
                <div className="flex items-center gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setResourceDrawerOpen(true)}
                    disabled={!selectedEnv}
                  >
                    <Boxes />
                    {t("apps.status.resources")}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setEventsDrawerOpen(true)}
                    disabled={!selectedEnv}
                  >
                    <Bell />
                    {t("apps.events.title")}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setMetricsDrawerOpen(true)}
                    disabled={!selectedEnv}
                  >
                    <LineChart />
                    {t("apps.metrics.open")}
                  </Button>
                </div>
              </div>
            )}
            {clusterDomain?.internalDomain && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <span className="font-medium text-foreground">{t("apps.status.internalDomain")} </span>
                <Copyable value={clusterDomain.internalDomain} maxLength={Infinity} />
              </div>
            )}
            {clusterDomain?.externalDomains && clusterDomain.externalDomains.length > 0 && (
              <div className="grid grid-cols-[auto_auto] gap-x-2 gap-y-1 items-center w-fit text-sm text-muted-foreground">
                {clusterDomain.externalDomains.map((domain, index) => (
                  <Fragment key={domain}>
                    <span className="font-medium text-foreground whitespace-nowrap">{index === 0 ? t("apps.status.externalDomain") : ""}</span>
                    <span className="flex items-center gap-1.5">
                      <Copyable value={domain} maxLength={Infinity} />
                      <a href={domain} target="_blank" rel="noopener noreferrer" className="text-muted-foreground hover:text-foreground transition-colors">
                        <ExternalLink className="size-4" />
                      </a>
                    </span>
                  </Fragment>
                ))}
              </div>
            )}
          </div>
        }
        table={
          <div className="rounded-md">
            <DataTable
              columns={columns}
              data={podStatuses}
              loading={loading}
              renderExpandedRow={renderExpandedRow}
            />
          </div>
        }
      />

      {selectedEnv && (
        <>
          <ApplicationResourceDrawer
            open={resourceDrawerOpen}
            onOpenChange={setResourceDrawerOpen}
            namespace={namespace}
            applicationName={name}
            environment={selectedEnv}
          />
          <ApplicationEventsDrawer
            open={eventsDrawerOpen}
            onOpenChange={setEventsDrawerOpen}
            namespace={namespace}
            applicationName={name}
            environment={selectedEnv}
          />
          <ApplicationMetricsDrawer
            open={metricsDrawerOpen}
            onOpenChange={setMetricsDrawerOpen}
            namespace={namespace}
            applicationName={name}
            environment={selectedEnv}
          />
        </>
      )}

      <AlertDialog open={isRestartDialogOpen} onOpenChange={setIsRestartDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("apps.status.confirmRestart")}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("apps.status.restartDescPrefix")}{podToRestart}{t("apps.status.restartDescSuffix")}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("common.cancel")}</AlertDialogCancel>
            <AlertDialogAction onClick={confirmRestart}>{t("common.confirm")}</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </ContentPage>
  )
}
