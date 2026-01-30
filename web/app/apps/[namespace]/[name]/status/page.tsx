"use client"

import { useState, useEffect } from "react"
import { useParams, useRouter, useSearchParams, usePathname } from "next/navigation"
import { getApplicationStatus, restartApplicationPod } from "@/lib/api/applications"
import { fetchEnvironments } from "@/lib/api/environments"
import { ApplicationPodStatus, Environment } from "@/lib/api/types"
import { DataTable } from "@/components/ui/data-table"
import { getStatusColumns } from "./columns"
import { Skeleton } from "@/components/ui/skeleton"
import { toast } from "sonner"
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
        toast.error("Failed to fetch environments")
      }
    }
    loadEnvironments()
  }, [])

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
          toast.error("Failed to fetch application status")
          setPodStatuses([])
        }
      } finally {
        if (showLoading) setLoading(false)
      }
    }

    // Initial fetch
    fetchStatus(true)

    // Poll every 1000ms
    const intervalId = setInterval(() => fetchStatus(false), 1000)

    return () => clearInterval(intervalId)
  }, [namespace, name, selectedEnv])

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
      toast.success("Pod restarted successfully")
      // Refresh status immediately
      const res = await getApplicationStatus(namespace, name, selectedEnv)
      setPodStatuses(res.data ?? [])
    } catch (error) {
      console.error("Failed to restart pod:", error)
      toast.error("Failed to restart pod")
    } finally {
      setIsRestartDialogOpen(false)
      setPodToRestart(null)
    }
  }

  const handleViewLogs = (podName: string) => {
    router.push(`/apps/${namespace}/${name}/pods/${podName}/logs?env=${selectedEnv}`)
  }

  const handleTerminal = (podName: string) => {
    window.open(`/apps/${namespace}/${name}/pods/${podName}/terminal?env=${selectedEnv}`, '_blank')
  }

  const columns = getStatusColumns(handleRestartClick, handleViewLogs, handleTerminal)

  return (
    <div className="flex-1 space-y-4">
      <div className="flex items-center justify-between space-y-2">
        <h2 className="text-3xl font-bold tracking-tight">运行状态</h2>
      </div>
      
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

      <div className="rounded-md">
        {loading ? (
          <div className="p-4 space-y-4">
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
            <Skeleton className="h-12 w-full" />
          </div>
        ) : (
          <DataTable 
            columns={columns} 
            data={podStatuses} 
          />
        )}
      </div>

      <AlertDialog open={isRestartDialogOpen} onOpenChange={setIsRestartDialogOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认重启 Pod?</AlertDialogTitle>
            <AlertDialogDescription>
              此操作将重启 Pod {podToRestart}。该操作无法撤销。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={confirmRestart}>确认</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
