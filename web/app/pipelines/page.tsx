"use client"

import { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { DataTable } from "@/components/ui/data-table"
import { getPipelines, stopPipeline } from "@/lib/api/pipelines"
import { getApplications, getApplicationConfigs } from "@/lib/api/applications"
import { fetchNamespaces } from "@/lib/api/namespaces"
import { Pipeline, Application } from "@/lib/api/types"
import { getPipelineColumns } from "@/app/apps/[namespace]/[name]/pipelines/columns"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { RotateCcw, ChevronLeft, ChevronRight } from "lucide-react"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

export default function PipelinesPage() {
  const router = useRouter()
  const [pipelines, setPipelines] = useState<Pipeline[]>([])
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [hasNext, setHasNext] = useState(false)
  
  const [namespaces, setNamespaces] = useState<{id: string, name: string}[]>([])
  const [selectedNamespace, setSelectedNamespace] = useState<string>("")
  
  const [applications, setApplications] = useState<Application[]>([])
  const [selectedApp, setSelectedApp] = useState<string>("")
  
  const [environments, setEnvironments] = useState<string[]>([])
  const [selectedEnv, setSelectedEnv] = useState<string>("all")

  // Fetch namespaces on mount
  useEffect(() => {
    const loadNamespaces = async () => {
      try {
        const res = await fetchNamespaces()
        if (res.data && Array.isArray(res.data)) {
          const nsList = res.data.map((ns: string) => ({ id: ns, name: ns }))
          setNamespaces(nsList)
          if (nsList.length > 0) {
            setSelectedNamespace(nsList[0].id)
          }
        }
      } catch (error) {
        toast.error("Failed to fetch namespaces")
      }
    }
    loadNamespaces()
  }, [])

  // Fetch applications when namespace changes
  useEffect(() => {
    const loadApplications = async () => {
      if (!selectedNamespace) {
        setApplications([])
        setSelectedApp("")
        setEnvironments([])
        setSelectedEnv("all")
        return
      }
      try {
        const res = await getApplications(selectedNamespace)
        if (res.data) {
          setApplications(res.data)
          if (res.data.length > 0) {
            setSelectedApp(res.data[0].name)
          } else {
             setSelectedApp("")
          }
        }
      } catch (error) {
        toast.error("Failed to fetch applications")
        setApplications([])
        setSelectedApp("")
      }
    }
    loadApplications()
  }, [selectedNamespace])

  useEffect(() => {
    const loadEnvironments = async () => {
        if (!selectedNamespace || !selectedApp) {
            setEnvironments([])
            setSelectedEnv("all")
            return
        }
        try {
            const res = await getApplicationConfigs(selectedNamespace, selectedApp)
            if (res.data) {
                setEnvironments(res.data.map(c => c.environmentName))
                setSelectedEnv("all")
            }
        } catch (error) {
            console.error("Failed to fetch environments:", error)
            setEnvironments([])
            setSelectedEnv("all")
        }
    }
    loadEnvironments()
  }, [selectedNamespace, selectedApp])

  const fetchPipelines = async () => {
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
    } catch (error) {
      toast.error("Failed to fetch pipelines")
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchPipelines()
  }, [selectedNamespace, selectedApp, selectedEnv, page, size])

  const handleView = (pipeline: Pipeline) => {
    router.push(`/apps/${selectedNamespace}/${selectedApp}/pipelines/${pipeline.id}`)
  }

  const handleStop = async (pipeline: Pipeline) => {
    try {
      await stopPipeline(selectedNamespace, selectedApp, pipeline.id)
      toast.success("Pipeline stop requested")
      fetchPipelines()
    } catch (error) {
      toast.error("Failed to stop pipeline")
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium whitespace-nowrap">命名空间:</span>
            <Select value={selectedNamespace} onValueChange={setSelectedNamespace}>
              <SelectTrigger className="w-[200px]">
                <SelectValue placeholder="选择命名空间" />
              </SelectTrigger>
              <SelectContent>
                {namespaces.map(ns => (
                  <SelectItem key={ns.id} value={ns.id}>{ns.name}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex items-center gap-2">
            <span className="text-sm font-medium whitespace-nowrap">应用:</span>
            <Select value={selectedApp} onValueChange={setSelectedApp} disabled={!selectedNamespace}>
              <SelectTrigger className="w-[200px]">
                <SelectValue placeholder="选择应用" />
              </SelectTrigger>
              <SelectContent>
                {applications.map(app => (
                  <SelectItem key={app.name} value={app.name}>{app.name}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex items-center gap-2">
            <span className="text-sm font-medium whitespace-nowrap">环境:</span>
            <Select value={selectedEnv} onValueChange={setSelectedEnv} disabled={!selectedNamespace || !selectedApp}>
              <SelectTrigger className="w-[200px]">
                <SelectValue placeholder="选择环境" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">全部</SelectItem>
                {environments.map(env => (
                  <SelectItem key={env} value={env}>{env}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
        <Button variant="outline" size="sm" onClick={fetchPipelines} disabled={loading || !selectedApp}>
          <RotateCcw className={`mr-2 h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          刷新
        </Button>
      </div>
      
      {!selectedApp && (
          <div className="text-center py-10 text-muted-foreground border rounded-md border-dashed">
            请先选择应用查看流水线
          </div>
      )}

      {selectedApp && (
         <>
           <DataTable columns={getPipelineColumns(handleView, handleStop)} data={pipelines} />
           <div className="flex items-center justify-end gap-2 mt-2">
             <Button
               variant="outline"
               size="sm"
               disabled={page === 0 || loading}
               onClick={() => setPage((p) => Math.max(0, p - 1))}
             >
               <ChevronLeft className="mr-2 h-4 w-4" />
               上一页
             </Button>
             <span className="text-sm text-muted-foreground">第 {page + 1} 页</span>
             <Button
               variant="outline"
               size="sm"
               disabled={!hasNext || loading}
               onClick={() => setPage((p) => p + 1)}
             >
               下一页
               <ChevronRight className="ml-2 h-4 w-4" />
             </Button>
           </div>
         </>
      )}
    </div>
  )
}
