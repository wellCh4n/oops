"use client"

import { Suspense, useState, useEffect, Fragment } from "react"
import { useParams, useRouter, useSearchParams, usePathname } from "next/navigation"
import { restartApplicationPod, getClusterDomain, watchApplicationStatus } from "@/lib/api/applications"
import { fetchEnvironments } from "@/lib/api/environments"
import { ApplicationPodStatus, Environment, ClusterDomainInfo } from "@/lib/api/types"
import { DataTable } from "@/components/ui/data-table"
import { Copyable } from "@/components/ui/copyable"
import { getStatusColumns } from "./columns"
import { toast } from "sonner"
import { ChevronRight, ExternalLink } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { ApplicationResourceViewer } from "@/app/apps/components/application-resource-viewer"
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
import { useLanguage } from "@/contexts/language-context"
import { useRecentAppStore } from "@/store/recent-app"
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
  const envParam = searchParams.get("env")

  const [loading, setLoading] = useState(true)
  const [podStatuses, setPodStatuses] = useState<ApplicationPodStatus[]>([])
  const [environments, setEnvironments] = useState<Environment[]>([])
  const [selectedEnv, setSelectedEnv] = useState<string>("")
  const [isRestartDialogOpen, setIsRestartDialogOpen] = useState(false)
  const [podToRestart, setPodToRestart] = useState<string | null>(null)
  const [clusterDomain, setClusterDomain] = useState<ClusterDomainInfo | null>(null)
  const [resourcesOpen, setResourcesOpen] = useState(false)
  const { t } = useLanguage()
  const { setRecentApp } = useRecentAppStore()

  useEffect(() => {
    setRecentApp({ namespace, name })
  }, [namespace, name, setRecentApp])

  // Fetch environments on mount
  useEffect(() => {
    const loadEnvironments = async () => {
      try {
        const res = await fetchEnvironments()
        if (res.data && Array.isArray(res.data) && res.data.length > 0) {
          setEnvironments(res.data)

          // Determine initial environment
          let initialEnv = res.data[0].name
          if (envParam && res.data.some(e => e.name === envParam)) {
            initialEnv = envParam
          }

          setSelectedEnv(initialEnv)

          // Sync URL if needed
          if (initialEnv !== envParam) {
            const newParams = new URLSearchParams(searchParams.toString())
            newParams.set("env", initialEnv)
            router.replace(`${pathname}?${newParams.toString()}`)
          }
        }
      } catch (error) {
        console.error("Failed to fetch environments:", error)
        toast.error(t("apps.status.fetchEnvError"))
      }
    }
    loadEnvironments()
  }, [envParam, pathname, router, searchParams, t])

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

  const handleTabChange = (value: string) => {
    setSelectedEnv(value)
    const newParams = new URLSearchParams(searchParams.toString())
    newParams.set("env", value)
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
  })

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
                      <TooltipTrigger asChild>
                        <span className="truncate inline-block max-w-full align-bottom">{shortImageName(container.image)}</span>
                      </TooltipTrigger>
                      <TooltipContent className="max-w-160 break-all font-mono text-xs">
                        {container.image}
                      </TooltipContent>
                    </Tooltip>
                  </TableCell>
                  <TableCell className="px-3 py-2">
                    <Badge variant={container.ready ? "default" : "destructive"}>
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
    <ContentPage title={t("apps.status.title")}>
      <TableForm
        options={
          <div className="space-y-2">
            {environments.length > 0 && (
              <Tabs value={selectedEnv} onValueChange={handleTabChange} className="w-full">
                <TabsList>
                  {environments.map((env) => (
                    <TabsTrigger key={env.id} value={env.name} className="px-8">
                      {env.name}
                    </TabsTrigger>
                  ))}
                </TabsList>
              </Tabs>
            )}
            {clusterDomain?.internalDomain && (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <span className="font-medium text-foreground">{t("apps.status.internalDomain")} </span>
                <Copyable value={clusterDomain.internalDomain} maxLength={Infinity} />
              </div>
            )}
            {clusterDomain?.externalDomains && clusterDomain.externalDomains.length > 0 && (
              <div className="grid grid-cols-[auto_auto_auto] gap-x-2 gap-y-1 items-center w-fit text-sm text-muted-foreground">
                {clusterDomain.externalDomains.map((domain, index) => (
                  <Fragment key={domain}>
                    <span className="font-medium text-foreground whitespace-nowrap">{index === 0 ? t("apps.status.externalDomain") : ""}</span>
                    <Copyable value={domain} maxLength={Infinity} />
                    <a href={domain} target="_blank" rel="noopener noreferrer" className="text-muted-foreground hover:text-foreground transition-colors">
                      <ExternalLink className="size-4" />
                    </a>
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

      <Collapsible open={resourcesOpen} onOpenChange={setResourcesOpen} className="mt-4">
        <CollapsibleTrigger className="flex items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors cursor-pointer">
          <ChevronRight className={`size-4 transition-transform ${resourcesOpen ? "rotate-90" : ""}`} />
          {t("apps.status.resources")}
        </CollapsibleTrigger>
        <CollapsibleContent className="pt-2">
          <ApplicationResourceViewer namespace={namespace} applicationName={name} environmentName={selectedEnv} />
        </CollapsibleContent>
      </Collapsible>

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
