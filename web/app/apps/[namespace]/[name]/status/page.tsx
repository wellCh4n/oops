"use client"

import { useState, useEffect, Fragment } from "react"
import { useParams, useRouter, useSearchParams, usePathname } from "next/navigation"
import { getApplicationStatus, restartApplicationPod, getClusterDomain } from "@/lib/api/applications"
import { fetchEnvironments } from "@/lib/api/environments"
import { ApplicationPodStatus, Environment, ClusterDomainInfo } from "@/lib/api/types"
import { DataTable } from "@/components/ui/data-table"
import { Copyable } from "@/components/ui/copyable"
import { getStatusColumns } from "./columns"
import { toast } from "sonner"
import { ExternalLink } from "lucide-react"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
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
import { useLanguage } from "@/contexts/language-context"

export default function ApplicationStatusPage() {
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
  const { t } = useLanguage()

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

  // Fetch status when environment changes and poll
  useEffect(() => {
    if (!selectedEnv) return

    const fetchStatus = async (showLoading = false) => {
      if (showLoading) setLoading(true)
      try {
        const res = await getApplicationStatus(namespace, name, selectedEnv)
        setPodStatuses(res.data ?? [])
      } catch (error) {
        console.error("Failed to fetch application status:", error)
        if (showLoading) {
          toast.error(t("apps.status.fetchError"))
          setPodStatuses([])
        }
      } finally {
        if (showLoading) setLoading(false)
      }
    }

    // Fetch cluster domain
    getClusterDomain(namespace, name, selectedEnv)
      .then(res => setClusterDomain(res.data ?? null))
      .catch(() => setClusterDomain(null))

    // Initial fetch
    fetchStatus(true)

    // Poll every 1000ms
    const intervalId = setInterval(() => fetchStatus(false), 1000)

    return () => clearInterval(intervalId)
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
      // Refresh status immediately
      const res = await getApplicationStatus(namespace, name, selectedEnv)
      setPodStatuses(res.data ?? [])
    } catch (error) {
      console.error("Failed to restart pod:", error)
      toast.error(t("apps.status.restartError"))
    } finally {
      setIsRestartDialogOpen(false)
      setPodToRestart(null)
    }
  }

  const handleViewLogs = (podName: string) => {
    window.open(`/apps/${namespace}/${name}/pods/${podName}/logs?env=${selectedEnv}`, '_blank')
  }

  const handleTerminal = (podName: string) => {
    window.open(`/apps/${namespace}/${name}/pods/${podName}/terminal?env=${selectedEnv}`, '_blank')
  }

  const columns = getStatusColumns(t, handleRestartClick, handleViewLogs, handleTerminal)

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
                  <Fragment key={index}>
                    <span className="font-medium text-foreground whitespace-nowrap">{index === 0 ? t("apps.status.externalDomain") : ""}</span>
                    <Copyable value={domain} maxLength={Infinity} />
                    <a href={domain} target="_blank" rel="noopener noreferrer" className="text-muted-foreground hover:text-foreground transition-colors">
                      <ExternalLink className="h-4 w-4" />
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
            />
          </div>
        }
      />

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
